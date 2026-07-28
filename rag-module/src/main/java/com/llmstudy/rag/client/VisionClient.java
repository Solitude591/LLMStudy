package com.llmstudy.rag.client;

import com.llmstudy.rag.config.VisionProperties;
import com.llmstudy.rag.dto.DocumentParseResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * OpenAI-compatible 视觉模型客户端，为图片生成中文描述。
 *
 * <p>图片以 Base64 data URL 提交，不依赖模型能访问 MinIO，
 * 因此私有部署的 MinIO 也能正常工作。</p>
 *
 * <p>失败策略：单张图片失败不影响整篇解析，调用方用 PDF 原文图注兜底。
 * 这与「整篇失败」相比更符合生产需要——200 页文档因第 199 张图超时而全部作废，
 * 会浪费掉 MinerU 已消耗的解析时间和费用。</p>
 */
@Component
public class VisionClient {

    private static final Logger log = LoggerFactory.getLogger(VisionClient.class);

    private static final String PROMPT = """
            请用简洁的中文描述这张图片的内容，用于文档检索。要求：
            1. 如果是流程图或架构图，说明它表达的流程或结构；
            2. 如果是数据图表，说明图表类型、坐标轴含义和主要趋势；
            3. 如果是表格截图，概括表格主题和关键字段；
            4. 只输出描述本身，不要加"这张图片展示了"之类的开场白；
            5. 控制在 100 字以内，单段纯文本，不要换行和 Markdown 标记。
            """;

    private final HttpClient httpClient;
    private final VisionProperties properties;
    private final JsonMapper objectMapper;

    public VisionClient(HttpClient httpClient, VisionProperties properties, JsonMapper objectMapper) {
        this.httpClient = httpClient;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    public boolean isEnabled() {
        return properties.isEnabled();
    }

    /**
     * 批量生成图片描述，按配置的并发数并行调用。
     *
     * @param images key 为图片路径，value 为图片数据
     * @return key 为图片路径，value 为描述；生成失败的图片不会出现在结果中
     */
    public Map<String, String> describeAll(Map<String, DocumentParseResult.ImageResource> images) {
        // 未完整配置视觉接口时直接降级，不让可选能力阻塞文档主解析流程。
        if (!isEnabled()) {
            log.info("视觉模型未配置，跳过图片描述生成（将使用 PDF 原文图注）");
            return Map.of();
        }
        if (images == null || images.isEmpty()) {
            return Map.of();
        }

        // 并发数至少为 1，且不超过实际图片数，避免创建无意义的空闲线程。
        int concurrency = Math.max(1, Math.min(properties.getConcurrency(), images.size()));
        Map<String, String> descriptions = new LinkedHashMap<>();
        long startMs = System.currentTimeMillis();

        // 使用本次批处理独立的固定线程池，限制同时发往模型服务的请求数量。
        ExecutorService executor = Executors.newFixedThreadPool(concurrency);
        try {
            Map<String, Future<String>> futures = new LinkedHashMap<>();
            for (Map.Entry<String, DocumentParseResult.ImageResource> entry : images.entrySet()) {
                // 每张图片独立提交任务，单张失败只影响自身，不取消其他图片的处理。
                Callable<String> task = () -> describeWithRetry(entry.getValue());
                futures.put(entry.getKey(), executor.submit(task));
            }

            for (Map.Entry<String, Future<String>> entry : futures.entrySet()) {
                try {
                    // 按原图片顺序收集结果，使返回映射和输入顺序保持一致，便于日志与排查。
                    String description = entry.getValue().get();
                    if (description != null && !description.isBlank()) {
                        descriptions.put(entry.getKey(), description);
                    }
                } catch (Exception e) {
                    log.warn("图片描述生成失败，将使用原文图注兜底: image={}, error={}",
                            entry.getKey(), e.getMessage());
                }
            }
        } finally {
            executor.shutdownNow();
        }

        log.info("图片描述生成完成: 成功={}/{}, 并发={}, 耗时={}ms",
                descriptions.size(), images.size(), concurrency,
                System.currentTimeMillis() - startMs);
        return descriptions;
    }

    /**
     * 为单张图片生成描述，失败按配置重试。
     */
    private String describeWithRetry(DocumentParseResult.ImageResource image) {
        RuntimeException lastError = null;
        // maxRetries 表示首次失败后的额外重试次数，因此总尝试次数需要加 1。
        int attempts = Math.max(1, properties.getMaxRetries() + 1);

        for (int attempt = 1; attempt <= attempts; attempt++) {
            try {
                return describe(image);
            } catch (RuntimeException e) {
                lastError = e;
                if (attempt < attempts) {
                    log.debug("图片描述第 {}/{} 次尝试失败: image={}, error={}",
                            attempt, attempts, image.getPath(), e.getMessage());
                }
            }
        }
        throw lastError != null ? lastError
                : new RuntimeException("图片描述生成失败: " + image.getPath());
    }

    /**
     * 调用视觉模型生成单张图片的描述。
     */
    private String describe(DocumentParseResult.ImageResource image) {
        // 将二进制图片内联为 data URL，模型服务无需能够访问 MinIO 或项目所在内网。
        String dataUrl = "data:" + image.getContentType() + ";base64,"
                + Base64.getEncoder().encodeToString(image.getData());

        String body = buildRequestBody(dataUrl);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(properties.getApiUrl()))
                .timeout(Duration.ofSeconds(properties.getTimeoutSeconds()))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + properties.getApiKey())
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new RuntimeException("视觉模型调用失败: HTTP " + response.statusCode()
                        + ", body=" + truncate(response.body()));
            }

            // 按 OpenAI-compatible 响应格式读取 choices[0].message.content。
            JsonNode root = objectMapper.readTree(response.body());
            JsonNode content = root.path("choices").path(0).path("message").path("content");
            if (content.isMissingNode() || content.isNull()) {
                throw new RuntimeException("视觉模型响应缺少 content: " + truncate(response.body()));
            }

            String description = sanitizeDescription(content.asString());
            if (description.isBlank()) {
                throw new RuntimeException("视觉模型返回空描述");
            }
            return description;
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("视觉模型请求异常: " + e.getMessage(), e);
        }
    }

    /**
     * 构造 OpenAI-compatible 多模态请求体。
     * 由 Jackson 序列化，避免手写 JSON 时 Base64 内容破坏转义。
     */
    private String buildRequestBody(String dataUrl) {
        Map<String, Object> textPart = Map.of("type", "text", "text", PROMPT);
        Map<String, Object> imagePart = Map.of(
                "type", "image_url",
                "image_url", Map.of("url", dataUrl));
        Map<String, Object> message = Map.of(
                "role", "user",
                "content", java.util.List.of(textPart, imagePart));
        Map<String, Object> payload = Map.of(
                "model", properties.getModel(),
                "messages", java.util.List.of(message),
                "max_tokens", properties.getMaxTokens(),
                "temperature", properties.getTemperature());

        return objectMapper.writeValueAsString(payload);
    }

    /**
     * 描述文本规范化：压平换行，避免破坏 Markdown 的 alt 文本结构。
     */
    private String sanitizeDescription(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.replaceAll("\\s*[\\r\\n]+\\s*", " ")
                .replaceAll("\\s{2,}", " ")
                .trim();
    }

    private String truncate(String value) {
        if (value == null) {
            return "";
        }
        return value.length() <= 500 ? value : value.substring(0, 500) + "...";
    }
}
