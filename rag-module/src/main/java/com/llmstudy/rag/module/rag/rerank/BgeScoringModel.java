package com.llmstudy.rag.module.rag.rerank;

import ai.djl.huggingface.tokenizers.Encoding;
import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OnnxTensorLike;
import ai.onnxruntime.OnnxValue;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import com.llmstudy.rag.config.RerankerProperties;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.model.scoring.ScoringModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 基于 ONNX Runtime 与 DJL Hugging Face Tokenizer 的本地 BGE ReRanker。
 *
 * <p>实现 LangChain4j {@link ScoringModel#scoreAll(List, String)}：输入用户原问题和
 * 全部候选正文，对每个 (问题, 候选) 对做 pair 分词，分批送入 ONNX 模型推理，读取
 * 分类 logits 并经过 sigmoid 转换为 [0,1] 相关度分数。</p>
 *
 * <p>模型与 tokenizer 均采用<b>惰性加载</b>：构造 Bean 不触碰文件系统，首次评分时才
 * 创建 ONNX session；模型禁用、文件缺失、加载失败或推理异常时抛出统一异常，由调用方
 * 回退原排序，保证检索可用性。单例 session 串行执行推理，应用关闭时释放 native 资源。</p>
 */
@Component
public class BgeScoringModel implements ScoringModel, AutoCloseable {

    private static final Logger log =
            LoggerFactory.getLogger(BgeScoringModel.class);

    /** BGE 分类模型固定使用的输入名。 */
    private static final String INPUT_IDS = "input_ids";
    private static final String ATTENTION_MASK = "attention_mask";
    private static final String TOKEN_TYPE_IDS = "token_type_ids";

    private final RerankerProperties properties;
    private final ResourceLoader resourceLoader;

    /** 惰性初始化的 ONNX session 与 tokenizer，volatile 保证多线程可见性。 */
    private volatile OrtSession session;
    private volatile HuggingFaceTokenizer tokenizer;
    /** tokenizer 中的 padding token id，不能假定为 0（当前模型为 1）。 */
    private volatile long padTokenId;
    /** 推理输出名，模型加载成功后缓存，避免每次推理遍历输出集合。 */
    private volatile String outputName;
    /** 加载失败后不再重试，避免每次评分都重复尝试加载不存在的模型。 */
    private volatile boolean loadFailed;

    /** 串行化首次加载，保证 session/tokenizer 只创建一次。 */
    private final Object loadLock = new Object();
    /** JAR 内资源需解压成临时文件供 native 库读取。 */
    private final List<Path> temporaryResources = new ArrayList<>();

    public BgeScoringModel(RerankerProperties properties,
                           ResourceLoader resourceLoader) {
        this.properties = properties;
        this.resourceLoader = resourceLoader;
    }

    /**
     * 对每个候选与用户原问题组成的 pair 打分，返回与候选顺序一致的分数列表。
     *
     * <p>禁用时抛出异常由检索器回退原排序；输入为空时直接返回空结果。
     * 按配置的 batchSize 分批推理，批内按最长序列动态 padding。</p>
     *
     * @param segments 全部候选正文
     * @param query    用户原始问题
     * @return 与 segments 一一对应的 [0,1] 相关度分数
     */
    @Override
    public Response<List<Double>> scoreAll(List<TextSegment> segments, String query) {
        // 模型禁用时直接抛异常，由检索器记录原因并回退原排序。
        if (!properties.isEnabled()) {
            throw new IllegalStateException("BGE ReRanker 已禁用");
        }
        if (query == null || query.isBlank()
                || segments == null || segments.isEmpty()) {
            return Response.from(List.of());
        }
        // 惰性加载模型与 tokenizer；文件缺失或加载失败时抛出统一异常。
        OrtSession ortSession = getOrCreateSession();
        HuggingFaceTokenizer hfTokenizer = getOrCreateTokenizer();

        List<Double> scores = new ArrayList<>(segments.size());
        int batchSize = properties.getBatchSize();
        if (batchSize <= 0) {
            throw new IllegalStateException("rag.reranker.batch-size 必须大于 0");
        }
        // 分批推理：每批最多 batchSize 个候选，批内按最长序列动态 padding。
        for (int start = 0; start < segments.size(); start += batchSize) {
            List<TextSegment> batch = segments.subList(
                    start, Math.min(start + batchSize, segments.size()));
            float[] batchLogits = scoreBatch(ortSession, hfTokenizer, query, batch);
            for (float logit : batchLogits) {
                scores.add(sigmoid(logit));
            }
        }
        return Response.from(scores);
    }

    /**
     * 对一批候选执行 ONNX 推理，返回每个候选的原始 logit。
     *
     * <p>先对 (query, candidate) pair 分词并记录批内最大长度，再动态 padding 到该
     * 长度构建张量；模型声明 token_type_ids 时补充零张量。所有张量用 try-with-resources
     * 释放，避免 native 内存泄漏。</p>
     *
     * @param ortSession 已加载的 ONNX session
     * @param hfTokenizer 已加载的 DJL tokenizer
     * @param query       用户原问题
     * @param batch       本批候选
     * @return 每个候选的 logit
     */
    private float[] scoreBatch(OrtSession ortSession, HuggingFaceTokenizer hfTokenizer,
                               String query, List<TextSegment> batch) {
        // 对批内每个候选做 (query, candidate) pair 分词，记录批内最大序列长度。
        List<long[]> idsList = new ArrayList<>(batch.size());
        List<long[]> masksList = new ArrayList<>(batch.size());
        List<long[]> typeIdsList = new ArrayList<>(batch.size());
        int maxSeqLen = 0;
        for (TextSegment segment : batch) {
            // 只截断第二段（候选正文），保留完整用户原问题。
            Encoding encoding = hfTokenizer.encode(query, segment.text());
            long[] ids = encoding.getIds() == null ? new long[0] : encoding.getIds();
            long[] masks = encoding.getAttentionMask() == null
                    ? new long[0] : encoding.getAttentionMask();
            long[] typeIds = encoding.getTypeIds() == null
                    ? new long[ids.length] : encoding.getTypeIds();
            if (ids.length == 0 || masks.length != ids.length
                    || typeIds.length != ids.length) {
                throw new IllegalStateException("BGE ReRanker tokenizer 输出长度异常");
            }
            maxSeqLen = Math.max(maxSeqLen, ids.length);
            idsList.add(ids);
            masksList.add(masks);
            typeIdsList.add(typeIds);
        }
        maxSeqLen = Math.max(1, maxSeqLen);

        // 动态 padding：把批内各序列补齐到 maxSeqLen，构建 [batch, maxSeqLen] 张量。
        long[][] inputIds = new long[batch.size()][maxSeqLen];
        long[][] attentionMask = new long[batch.size()][maxSeqLen];
        long[][] tokenTypeIds = new long[batch.size()][maxSeqLen];
        for (int i = 0; i < batch.size(); i++) {
            Arrays.fill(inputIds[i], padTokenId);
            long[] ids = idsList.get(i);
            long[] masks = masksList.get(i);
            System.arraycopy(ids, 0, inputIds[i], 0, ids.length);
            System.arraycopy(masks, 0, attentionMask[i], 0, masks.length);
            System.arraycopy(typeIdsList.get(i), 0, tokenTypeIds[i], 0, ids.length);
        }

        OrtEnvironment environment = OrtEnvironment.getEnvironment();
        Map<String, OnnxTensorLike> inputs = new LinkedHashMap<>();
        // input_ids 与 attention_mask 是 BGE 模型必选输入，用完即释放。
        try (OnnxTensor idsTensor = OnnxTensor.createTensor(environment, inputIds);
             OnnxTensor maskTensor = OnnxTensor.createTensor(environment, attentionMask)) {
            inputs.put(INPUT_IDS, idsTensor);
            inputs.put(ATTENTION_MASK, maskTensor);

            // 模型声明 token_type_ids 时传入 tokenizer 产生的张量；未声明则省略。
            OnnxTensor typeIdsTensor = null;
            try {
                if (ortSession.getInputNames().contains(TOKEN_TYPE_IDS)) {
                    typeIdsTensor = OnnxTensor.createTensor(
                            environment, tokenTypeIds);
                    inputs.put(TOKEN_TYPE_IDS, typeIdsTensor);
                }
                // 单例 session 串行执行推理，避免多线程并发推理过度占用 CPU。
                OrtSession.Result result;
                synchronized (ortSession) {
                    result = ortSession.run(inputs);
                }
                try (result) {
                    OnnxValue output = result.get(outputName)
                            .orElseThrow(() -> new IllegalStateException(
                                    "BGE ReRanker 输出缺失: " + outputName));
                    return extractLogits(output);
                }
            } finally {
                // 显式释放可选的 token_type_ids 张量。
                if (typeIdsTensor != null) {
                    typeIdsTensor.close();
                }
            }
        } catch (OrtException e) {
            throw new IllegalStateException("BGE ReRanker 推理失败", e);
        }
    }

    /**
     * 从模型输出中提取每个样本的 logit。
     *
     * <p>分类模型输出 shape 通常为 [batch, 1]，取每行第一个值；个别导出模型输出
     * 展平为 [batch] 时直接使用。</p>
     *
     * @param output 模型输出
     * @return 每个候选的 logit
     * @throws OrtException 读取输出值时抛出
     */
    private float[] extractLogits(OnnxValue output) throws OrtException {
        Object value = output.getValue();
        if (value instanceof float[][] matrix) {
            float[] logits = new float[matrix.length];
            for (int i = 0; i < matrix.length; i++) {
                logits[i] = matrix[i][0];
            }
            return logits;
        }
        if (value instanceof float[] flat) {
            return flat;
        }
        throw new IllegalStateException(
                "BGE ReRanker 输出类型不支持: " + value.getClass().getName());
    }

    /**
     * 惰性加载并复用 ONNX session；加载失败后不再重试。
     *
     * @return 单例 ONNX session
     */
    private OrtSession getOrCreateSession() {
        if (loadFailed) {
            throw new IllegalStateException("BGE ReRanker 模型加载已失败, 不再重试");
        }
        OrtSession local = session;
        if (local != null) {
            return local;
        }
        synchronized (loadLock) {
            if (session != null) {
                return session;
            }
            try {
                Path modelFile = resolveToPath(properties.getModelPath(),
                        "model_quantized.onnx");
                // CPU 推理启用全量优化。
                try (OrtSession.SessionOptions options = new OrtSession.SessionOptions()) {
                    options.setOptimizationLevel(
                            OrtSession.SessionOptions.OptLevel.ALL_OPT);
                    session = OrtEnvironment.getEnvironment()
                            .createSession(modelFile.toString(), options);
                }
                // 缓存唯一输出名，BGE 分类模型只有一个 logits 输出。
                if (session.getOutputNames().isEmpty()) {
                    throw new IllegalStateException("BGE ReRanker 模型没有输出");
                }
                outputName = session.getOutputNames().iterator().next();
                log.info("BGE ReRanker 模型加载完成: {}", modelFile);
            } catch (Exception e) {
                loadFailed = true;
                throw new IllegalStateException(
                        "BGE ReRanker 模型加载失败: "
                                + properties.getModelPath(), e);
            }
            return session;
        }
    }

    /**
     * 惰性加载并复用 DJL Hugging Face tokenizer。
     *
     * @return 单例 tokenizer
     */
    private HuggingFaceTokenizer getOrCreateTokenizer() {
        HuggingFaceTokenizer local = tokenizer;
        if (local != null) {
            return local;
        }
        synchronized (loadLock) {
            if (tokenizer != null) {
                return tokenizer;
            }
            try {
                Path tokenizerFile = resolveToPath(properties.getTokenizerPath(),
                        "tokenizer.json");
                tokenizer = HuggingFaceTokenizer.builder()
                        .optTokenizerPath(tokenizerFile)
                        .optTruncation(true)
                        .optMaxLength(properties.getMaxLength())
                        // pair 分词只截断候选正文，完整保留用户原问题。
                        .optTruncateSecondOnly()
                        .build();
                Encoding padEncoding = tokenizer.encode("<pad>", false, false);
                if (padEncoding.getIds() == null || padEncoding.getIds().length != 1) {
                    tokenizer.close();
                    tokenizer = null;
                    throw new IllegalStateException("tokenizer 缺少唯一 <pad> token");
                }
                padTokenId = padEncoding.getIds()[0];
            } catch (Exception e) {
                loadFailed = true;
                throw new IllegalStateException(
                        "BGE ReRanker tokenizer 加载失败: "
                                + properties.getTokenizerPath(), e);
            }
            return tokenizer;
        }
    }

    /**
     * 将 classpath、file URL 或普通文件路径统一解析为 native 库可读的 Path。
     * classpath 资源位于打包 JAR 内时，会复制到临时文件并在 Bean 销毁时清理。
     */
    private Path resolveToPath(String location, String defaultFilename) throws IOException {
        if (location == null || location.isBlank()) {
            throw new IOException("资源路径为空");
        }
        if (!location.startsWith(ResourceLoader.CLASSPATH_URL_PREFIX)
                && !location.startsWith("file:")) {
            Path path = Path.of(location).toAbsolutePath().normalize();
            if (Files.isDirectory(path)) {
                path = path.resolve(defaultFilename);
            }
            if (!Files.isRegularFile(path) || !Files.isReadable(path)) {
                throw new IOException("资源文件不存在或不可读: " + path);
            }
            return path;
        }

        Resource resource = resourceLoader.getResource(location);
        if (!resource.exists() || !resource.isReadable()) {
            throw new IOException("资源不存在或不可读: " + location);
        }
        if (resource.isFile()) {
            return resource.getFile().toPath().toAbsolutePath().normalize();
        }

        String suffix = defaultFilename.contains(".")
                ? defaultFilename.substring(defaultFilename.lastIndexOf('.')) : ".tmp";
        Path temporaryFile = Files.createTempFile("bge-reranker-", suffix);
        try (InputStream input = resource.getInputStream()) {
            Files.copy(input, temporaryFile, StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) {
            Files.deleteIfExists(temporaryFile);
            throw e;
        }
        temporaryResources.add(temporaryFile);
        return temporaryFile;
    }

    /**
     * 将 logit 通过 sigmoid 映射到 [0,1]。
     *
     * @param logit 模型输出的原始 logit
     * @return [0,1] 区间的相关度分数
     */
    private double sigmoid(float logit) {
        return 1.0 / (1.0 + Math.exp(-logit));
    }

    /**
     * 应用关闭时释放 ONNX session 与 tokenizer 的 native 资源。
     *
     * <p>由 Spring 在 Bean 销毁阶段调用（实现 AutoCloseable）。</p>
     */
    @Override
    public void close() {
        synchronized (loadLock) {
            OrtSession localSession = session;
            if (localSession != null) {
                try {
                    localSession.close();
                } catch (OrtException e) {
                    // 关闭失败仅记录日志，不影响应用正常退出。
                    log.warn("关闭 BGE ReRanker ONNX session 失败", e);
                }
                session = null;
            }
            HuggingFaceTokenizer localTokenizer = tokenizer;
            if (localTokenizer != null) {
                localTokenizer.close();
                tokenizer = null;
            }
            for (Path temporaryResource : temporaryResources) {
                try {
                    Files.deleteIfExists(temporaryResource);
                } catch (IOException e) {
                    log.warn("删除 BGE ReRanker 临时资源失败: {}", temporaryResource, e);
                }
            }
            temporaryResources.clear();
        }
    }
}
