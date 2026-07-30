package com.llmstudy.rag.service.parser;

import com.llmstudy.rag.dto.DocumentParseResult;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Set;

/**
 * TXT 解析策略：优先消费上传请求中的文件流，无流时从 MinIO（docUrl）下载。
 */
@Component
public class TxtDocumentParser implements DocumentParserStrategy {

    private static final Set<String> SUPPORTED_TYPES = Set.of("txt");
    private static final int MAX_TEXT_BYTES = 50 * 1024 * 1024;
    private static final HttpClient httpClient = HttpClient.newHttpClient();

    @Override
    public Set<String> supportedFileTypes() {
        return SUPPORTED_TYPES;
    }

    @Override
    public DocumentParseResult parse(DocumentParseContext context) {
        byte[] content;
        if (context.sourceFile() != null) {
            // 同步路径：使用上传请求直接透传的临时文件输入流，无需额外网络请求。
            content = readFromStream(context);
        } else {
            // 异步路径：上传请求已结束，通过 MinIO 公网 URL 下载原始文件内容。
            content = readFromUrl(context);
        }

        String text = new String(content, StandardCharsets.UTF_8);
        // 去掉 UTF-8 BOM，避免转换后的 Markdown 首字符异常。
        if (!text.isEmpty() && text.charAt(0) == '﻿') {
            text = text.substring(1);
        }
        return DocumentParseResult.text(text);
    }

    private byte[] readFromStream(DocumentParseContext context) {
        try (InputStream input = context.sourceFile().getInputStream()) {
            byte[] content = input.readNBytes(MAX_TEXT_BYTES + 1);
            if (content.length > MAX_TEXT_BYTES) {
                throw new IllegalArgumentException("TXT 文件超过最大解析限制 50MB");
            }
            return content;
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("TXT 文件读取失败: " + context.document().getDocId(), e);
        }
    }

    private byte[] readFromUrl(DocumentParseContext context) {
        String docUrl = context.document().getDocUrl();
        if (docUrl == null || docUrl.isBlank()) {
            throw new IllegalArgumentException("异步解析 TXT 时 docUrl 不能为空");
        }
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(docUrl))
                .GET()
                .build();
        try {
            HttpResponse<byte[]> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() != 200) {
                throw new RuntimeException("从 MinIO 下载原始文件失败: HTTP " + response.statusCode());
            }
            byte[] content = response.body();
            if (content.length > MAX_TEXT_BYTES) {
                throw new IllegalArgumentException("TXT 文件超过最大解析限制 50MB");
            }
            return content;
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("从 MinIO 下载原始文件失败: " + docUrl, e);
        }
    }
}
