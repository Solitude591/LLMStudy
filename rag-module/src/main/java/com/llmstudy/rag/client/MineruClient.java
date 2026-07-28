package com.llmstudy.rag.client;

import com.llmstudy.rag.config.MineruProperties;
import com.llmstudy.rag.dto.MineruContentElement;
import com.llmstudy.rag.dto.DocumentParseResult;
import com.llmstudy.rag.dto.MineruTaskResultResponse;
import com.llmstudy.rag.dto.MineruTaskSubmitResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * MinerU 云端 API 客户端。
 * <p>
 * 异步任务模式：提交任务，轮询拿到结果 ZIP 地址，再从 ZIP 中提取
 * full.md、content_list.json 和 images/ 下的全部图片。
 */
@Component
public class MineruClient {

    private static final Logger log = LoggerFactory.getLogger(MineruClient.class);

    /** ZIP 中被视为图片的扩展名 */
    private static final Map<String, String> IMAGE_CONTENT_TYPES = Map.of(
            "jpg", "image/jpeg",
            "jpeg", "image/jpeg",
            "png", "image/png",
            "gif", "image/gif",
            "bmp", "image/bmp",
            "webp", "image/webp",
            "tiff", "image/tiff",
            "svg", "image/svg+xml"
    );

    private final HttpClient httpClient;
    private final MineruProperties properties;
    private final JsonMapper objectMapper;

    public MineruClient(HttpClient httpClient, MineruProperties properties, JsonMapper objectMapper) {
        this.httpClient = httpClient;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    /**
     * 一键提交、等待完成、下载结果 ZIP，并返回其中的全部解析产物。
     *
     * @param fileUrl MinIO 公网可访问的文件 URL
     * @return 包含 markdown、content_list 和图片的完整产物
     */
    public DocumentParseResult parse(String fileUrl) {
        // MinerU 使用异步任务模型：提交接口只返回任务 ID，不能直接得到解析内容。
        String taskId = submitTask(fileUrl);
        // 持续查询任务状态，直到服务端生成可下载的完整产物 ZIP。
        MineruTaskResultResponse result = waitForResult(taskId);
        // 最后将厂商 ZIP 转换为项目内部统一的 Markdown、结构元素和图片资源。
        return downloadArtifacts(result.getFullZipUrl());
    }


    /**
     * 提交解析任务，返回 task_id。
     */
    public String submitTask(String fileUrl) {
        // 先规范化 URL，确保中文、空格等字符不会导致 MinerU 的 URL 校验失败。
        String normalizedFileUrl = normalizeFileUrl(fileUrl);
        // 请求体只包含文件地址和模型版本；文件本身由 MinerU 从 MinIO 地址拉取。
        String body = """
                {"url": "%s", "model_version": "%s"}
                """.formatted(escapeJson(normalizedFileUrl), properties.getModelVersion());

        log.info("提交 MinerU 解析任务: url={}, model={}",
                normalizedFileUrl, properties.getModelVersion());

        // Token 通过 Bearer 认证传递，单次提交设置独立超时，避免网络异常永久占用请求线程。
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(properties.getApiUrl()))
                .timeout(Duration.ofMinutes(2))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + properties.getToken())
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            log.info("MinerU 任务提交响应: status={}", response.statusCode());

            // 先检查 HTTP 层，再反序列化业务响应，避免把网关错误页当作 JSON 解析。
            requireSuccessStatus(response.statusCode(), response.body(), "任务提交");

            MineruTaskSubmitResponse result = objectMapper.readValue(
                    response.body(), MineruTaskSubmitResponse.class);

            // HTTP 200 不代表任务一定创建成功，还必须检查业务 code 和 task_id。
            if (!result.isSuccess() || result.getData() == null
                    || result.getData().getTaskId() == null
                    || result.getData().getTaskId().isBlank()) {
                throw new RuntimeException("MinerU 任务提交失败: code=" + result.getCode()
                        + ", msg=" + result.getMsg());
            }
            String taskId = result.getData().getTaskId();
            log.info("MinerU 任务已提交: taskId={}", taskId);
            return taskId;
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("MinerU 请求异常", e);
        }
    }

    /**
     * 轮询等待任务完成，返回完整结果。
     * 首次请求先检测任务是否存在，不存在则立即失败，不浪费轮询时间。
     */
    public MineruTaskResultResponse waitForResult(String taskId) {
        String pollUrl = properties.getApiUrl() + "/" + taskId;
        // 由最大等待时间和轮询间隔换算尝试次数，使总等待时间可通过配置控制。
        int maxAttempts = properties.getMaxWaitSeconds() / properties.getPollIntervalSeconds();

        boolean firstCheck = true;

        for (int i = 0; i < maxAttempts; i++) {
            // 首次检查不 sleep，先确认任务存在；后续轮询才等待
            if (!firstCheck) {
                sleep(properties.getPollIntervalSeconds());
            }
            firstCheck = false;

            // 每轮都创建新的 GET 请求，避免复用已经消费过的 HttpRequest 响应体。
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(pollUrl))
                    .timeout(Duration.ofSeconds(30))
                    .header("Authorization", "Bearer " + properties.getToken())
                    .GET()
                    .build();

            try {
                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                log.debug("MinerU 轮询响应: status={}, body={}", response.statusCode(), response.body());

                // 404 或无内容 → 任务不存在
                if (response.statusCode() == 404 || response.body() == null || response.body().isBlank()) {
                    throw new RuntimeException("MinerU 任务不存在: taskId=" + taskId);
                }
                requireSuccessStatus(response.statusCode(), response.body(), "任务查询");

                // 将厂商响应转换为带状态判断方法的 DTO，调用处无需散落字符串比较逻辑。
                MineruTaskResultResponse result = objectMapper.readValue(
                        response.body(), MineruTaskResultResponse.class);

                // 任务不存在（code 非 0 且非 running/done）
                if (result.doesNotExist()) {
                    throw new RuntimeException("MinerU 任务不存在: taskId=" + taskId
                            + ", msg=" + result.getMsg());
                }

                if (result.isFailed()) {
                    throw new RuntimeException("MinerU 解析失败: taskId=" + taskId
                            + ", msg=" + result.getErrorMessage());
                }
                if (result.isFinished()) {
                    // done 状态必须同时提供 ZIP 地址，否则没有可供后续处理的实际产物。
                    if (result.getFullZipUrl() == null || result.getFullZipUrl().isBlank()) {
                        throw new RuntimeException("MinerU 解析完成但未返回 full_zip_url: taskId=" + taskId);
                    }
                    log.info("MinerU 解析完成: taskId={}, zipUrl={}", taskId, result.getFullZipUrl());
                    return result;
                }
                log.debug("MinerU 任务运行中: taskId={}, 已检查{}次, state={}",
                        taskId, i + 1, result.getData() != null ? result.getData().getState() : "unknown");
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                log.warn("MinerU 轮询异常: taskId={}, error={}", taskId, e.getMessage());
            }
        }
        throw new RuntimeException("MinerU 解析超时: taskId=" + taskId
                + ", 已等待" + properties.getMaxWaitSeconds() + "秒");
    }

    /**
     * 下载 MinerU 结果 ZIP，一次遍历提取 full.md、content_list.json 和全部图片。
     *
     * <p>ZIP 只能顺序读取，因此单次遍历收集所有需要的条目，避免重复下载。</p>
     */
    private DocumentParseResult downloadArtifacts(String zipUrl) {
        // ZIP 可能包含大体积图片，因此响应体以流的方式处理，不先整体加载到内存。
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(zipUrl))
                .timeout(Duration.ofMinutes(5))
                .GET()
                .build();

        try {
            HttpResponse<InputStream> response = httpClient.send(
                    request, HttpResponse.BodyHandlers.ofInputStream());

            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                try (InputStream body = response.body()) {
                    String errorBody = new String(body.readNBytes(8192), StandardCharsets.UTF_8);
                    throw new RuntimeException("MinerU 结果 ZIP 下载失败: HTTP "
                            + response.statusCode() + ", body=" + errorBody);
                }
            }

            // full.md 是必需产物；content_list 和图片用于提高后续结构化分片及检索质量。
            String markdown = null;
            String contentListJson = null;
            Map<String, DocumentParseResult.ImageResource> images = new LinkedHashMap<>();
            long totalImageBytes = 0;
            int skippedImages = 0;

            try (InputStream body = response.body();
                 ZipInputStream zip = new ZipInputStream(body, StandardCharsets.UTF_8)) {
                ZipEntry entry;
                while ((entry = zip.getNextEntry()) != null) {
                    if (entry.isDirectory()) {
                        zip.closeEntry();
                        continue;
                    }

                    // 在读取内容前先校验条目路径，阻断绝对路径和 ../ 路径穿越。
                    String entryName = normalizeEntryName(entry.getName());
                    if (entryName == null) {
                        log.warn("跳过非法 ZIP 条目: {}", entry.getName());
                        zip.closeEntry();
                        continue;
                    }

                    // 每类核心文件只接受第一个匹配项，避免重复条目覆盖已经确认的产物。
                    if (markdown == null && isEntry(entryName, "full.md")) {
                        markdown = new String(
                                readEntry(zip, properties.getMaxMarkdownBytes(), entryName),
                                StandardCharsets.UTF_8);
                    } else if (contentListJson == null && isContentListEntry(entryName)) {
                        contentListJson = new String(
                                readEntry(zip, properties.getMaxContentListBytes(), entryName),
                                StandardCharsets.UTF_8);
                    } else {
                        String contentType = imageContentType(entryName);
                        if (contentType != null) {
                            // 同时限制图片数量、单张大小和累计大小，避免恶意或异常 ZIP 撑爆 JVM 堆。
                            if (images.size() >= properties.getMaxImageCount()
                                    || totalImageBytes >= properties.getMaxTotalImageBytes()) {
                                skippedImages++;
                                zip.closeEntry();
                                continue;
                            }
                            byte[] data;
                            try {
                                data = readEntry(zip, properties.getMaxImageBytes(), entryName);
                            } catch (EntrySizeLimitExceededException e) {
                                log.warn("跳过超限图片: entry={}, limit={}KB",
                                        entryName, properties.getMaxImageBytes() / 1024);
                                skippedImages++;
                                zip.closeEntry();
                                continue;
                            }
                            if (totalImageBytes + data.length > properties.getMaxTotalImageBytes()) {
                                log.warn("跳过图片，总图片大小将超过限制: entry={}, current={}KB, image={}KB, limit={}KB",
                                        entryName, totalImageBytes / 1024, data.length / 1024,
                                        properties.getMaxTotalImageBytes() / 1024);
                                skippedImages++;
                                zip.closeEntry();
                                continue;
                            }
                            // 保留规范化后的 ZIP 相对路径，之后用它与 Markdown 图片路径进行匹配。
                            images.put(entryName,
                                    new DocumentParseResult.ImageResource(entryName, data, contentType));
                            totalImageBytes += data.length;
                        }
                    }
                    zip.closeEntry();
                }
            }

            if (markdown == null) {
                throw new RuntimeException("MinerU 结果 ZIP 中未找到 full.md");
            }

            // content_list 异常采用降级策略：保留 Markdown 主产物，后续退化为纯文本结构分析。
            List<MineruContentElement> contentList = parseContentList(contentListJson);
            log.info("MinerU 产物提取完成: markdown={}字符, contentList={}项, 图片={}张({}KB), 跳过={}张",
                    markdown.length(), contentList.size(), images.size(),
                    totalImageBytes / 1024, skippedImages);

            return new DocumentParseResult(markdown, contentList, images);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("MinerU 结果 ZIP 处理失败", e);
        }
    }

    /**
     * 反序列化 content_list.json。缺失或格式异常时返回空列表——
     * 它只影响后续分块质量，不应导致整篇解析失败。
     */
    private List<MineruContentElement> parseContentList(String json) {
        if (json == null || json.isBlank()) {
            log.warn("MinerU 结果 ZIP 中未找到 content_list.json，后续分块将退化为纯 Markdown 解析");
            return List.of();
        }
        try {
            List<MineruContentElement> elements = objectMapper.readValue(
                    json, new TypeReference<List<MineruContentElement>>() {});
            return elements == null ? List.of() : elements;
        } catch (Exception e) {
            log.warn("content_list.json 解析失败，降级为空列表: {}", e.getMessage());
            return List.of();
        }
    }

    private byte[] readEntry(ZipInputStream zip, long maxBytes, String entryName) throws IOException {
        if (maxBytes <= 0) {
            throw new IllegalArgumentException("ZIP 条目大小限制必须大于 0");
        }
        // ZipEntry 声明的 size 可能缺失或不可信，因此以实际解压后的读取字节数执行限制。
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        long total = 0;
        int read;
        while ((read = zip.read(buffer)) != -1) {
            total += read;
            if (total > maxBytes) {
                throw new EntrySizeLimitExceededException(
                        "ZIP 条目超过大小限制: entry=" + entryName + ", limit=" + maxBytes);
            }
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }

    private static class EntrySizeLimitExceededException extends IOException {
        private EntrySizeLimitExceededException(String message) {
            super(message);
        }
    }

    /**
     * 规范化 ZIP 条目路径，并拦截 ZIP Slip（绝对路径、.. 穿越）。
     *
     * @return 规范化后的相对路径；非法条目返回 null
     */
    private String normalizeEntryName(String rawName) {
        if (rawName == null || rawName.isBlank()) {
            return null;
        }
        String name = rawName.replace('\\', '/');

        // 绝对路径与盘符
        if (name.startsWith("/") || name.matches("^[A-Za-z]:.*")) {
            return null;
        }

        List<String> segments = new ArrayList<>();
        for (String segment : name.split("/")) {
            if (segment.isEmpty() || ".".equals(segment)) {
                continue;
            }
            if ("..".equals(segment)) {
                return null; // 拒绝路径穿越，而非静默弹栈
            }
            segments.add(segment);
        }
        return segments.isEmpty() ? null : String.join("/", segments);
    }

    /**
     * 判断条目是否为指定文件名（顶层或任意子目录下）。
     */
    private boolean isEntry(String entryName, String fileName) {
        return entryName.equals(fileName) || entryName.endsWith("/" + fileName);
    }

    /**
     * 匹配 MinerU 的结构化内容文件。
     *
     * <p>不同版本的 MinerU 可能输出 content_list.json，也可能使用
     * {原文件名}_content_list.json；ZIP 中还可能带任务目录前缀。</p>
     */
    boolean isContentListEntry(String entryName) {
        if (entryName == null || entryName.isBlank()) {
            return false;
        }
        int slashIndex = entryName.lastIndexOf('/');
        String fileName = slashIndex >= 0
                ? entryName.substring(slashIndex + 1)
                : entryName;
        String lowerName = fileName.toLowerCase(Locale.ROOT);
        return lowerName.equals("content_list.json")
                || lowerName.endsWith("_content_list.json");
    }

    /**
     * 按扩展名判断图片类型，非图片返回 null。
     */
    private String imageContentType(String entryName) {
        int dotIdx = entryName.lastIndexOf('.');
        if (dotIdx < 0 || dotIdx == entryName.length() - 1) {
            return null;
        }
        String ext = entryName.substring(dotIdx + 1).toLowerCase(Locale.ROOT);
        return IMAGE_CONTENT_TYPES.get(ext);
    }

    private void requireSuccessStatus(int statusCode, String body, String operation) {
        if (statusCode < 200 || statusCode >= 300) {
            throw new RuntimeException("MinerU " + operation + "失败: HTTP "
                    + statusCode + ", body=" + body);
        }
    }

    /**
     * 将已有记录中的空格、中文等字符编码为合法的公网 URL。
     */
    private String normalizeFileUrl(String fileUrl) {
        if (fileUrl == null || fileUrl.isBlank()) {
            throw new IllegalArgumentException("待解析文件 URL 不能为空");
        }
        try {
            URI uri = new URI(fileUrl.replace(" ", "%20"));
            String scheme = uri.getScheme();
            if ((!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme))
                    || uri.getHost() == null) {
                throw new IllegalArgumentException("文件 URL 缺少 http:// 或 https://: " + fileUrl);
            }
            return uri.toASCIIString();
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("文件 URL 格式不正确: " + fileUrl, e);
        }
    }

    private void sleep(int seconds) {
        try {
            Thread.sleep(seconds * 1000L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("MinerU 轮询被中断", e);
        }
    }

    private String escapeJson(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
