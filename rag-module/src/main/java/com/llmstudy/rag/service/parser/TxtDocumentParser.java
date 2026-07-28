package com.llmstudy.rag.service.parser;

import com.llmstudy.rag.config.MinioProperties;
import com.llmstudy.rag.dto.DocumentParseResult;
import io.minio.GetObjectArgs;
import io.minio.GetObjectResponse;
import io.minio.MinioClient;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Set;

/**
 * TXT 解析策略：直接从 MinIO 读取原文件并按 UTF-8 解码。
 */
@Component
public class TxtDocumentParser implements DocumentParserStrategy {

    private static final Set<String> SUPPORTED_TYPES = Set.of("txt");
    private static final int MAX_TEXT_BYTES = 50 * 1024 * 1024;

    private final MinioClient minioClient;
    private final MinioProperties minioProperties;

    public TxtDocumentParser(MinioClient minioClient, MinioProperties minioProperties) {
        this.minioClient = minioClient;
        this.minioProperties = minioProperties;
    }

    @Override
    public Set<String> supportedFileTypes() {
        return SUPPORTED_TYPES;
    }

    @Override
    public DocumentParseResult parse(DocumentParseContext context) {
        try (GetObjectResponse input = minioClient.getObject(GetObjectArgs.builder()
                .bucket(minioProperties.getBucketName())
                .object(context.sourceObjectKey())
                .build())) {
            byte[] content = input.readNBytes(MAX_TEXT_BYTES + 1);
            if (content.length > MAX_TEXT_BYTES) {
                throw new IllegalArgumentException("TXT 文件超过最大解析限制 50MB");
            }
            String text = new String(content, StandardCharsets.UTF_8);
            // 去掉 UTF-8 BOM，避免转换后的 Markdown 首字符异常。
            if (!text.isEmpty() && text.charAt(0) == '\uFEFF') {
                text = text.substring(1);
            }
            return DocumentParseResult.text(text);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("TXT 文件读取失败: " + context.document().getDocId(), e);
        }
    }
}
