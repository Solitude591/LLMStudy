package com.llmstudy.rag.service.parser;

import com.llmstudy.rag.dto.DocumentParseResult;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Set;

/**
 * TXT 解析策略：直接读取上传接口透传的文件流并按 UTF-8 解码。
 */
@Component
public class TxtDocumentParser implements DocumentParserStrategy {

    private static final Set<String> SUPPORTED_TYPES = Set.of("txt");
    private static final int MAX_TEXT_BYTES = 50 * 1024 * 1024;

    @Override
    public Set<String> supportedFileTypes() {
        return SUPPORTED_TYPES;
    }

    @Override
    public DocumentParseResult parse(DocumentParseContext context) {
        // TXT 不需要第三方解析服务，直接消费上传接口透传的当前文件流。
        try (InputStream input = context.sourceFile().getInputStream()) {
            // 多读 1 个字节用于准确判断是否超限，同时避免无边界 readAllBytes。
            byte[] content = input.readNBytes(MAX_TEXT_BYTES + 1);
            if (content.length > MAX_TEXT_BYTES) {
                throw new IllegalArgumentException("TXT 文件超过最大解析限制 50MB");
            }

            // 当前基础策略统一使用 UTF-8，后续可独立扩展编码探测。
            String text = new String(content, StandardCharsets.UTF_8);

            // 去掉 UTF-8 BOM，避免转换后的 Markdown 首字符异常。
            if (!text.isEmpty() && text.charAt(0) == '\uFEFF') {
                text = text.substring(1);
            }
            // 统一封装为解析结果；TXT 没有结构化 content_list，也没有图片资源。
            return DocumentParseResult.text(text);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("TXT 文件读取失败: " + context.document().getDocId(), e);
        }
    }
}
