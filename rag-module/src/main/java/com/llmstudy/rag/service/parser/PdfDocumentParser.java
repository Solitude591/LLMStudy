package com.llmstudy.rag.service.parser;

import com.llmstudy.rag.client.MineruClient;
import com.llmstudy.rag.dto.DocumentParseResult;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * PDF 解析策略：使用 MinerU 生成 Markdown、content_list 和图片。
 */
@Component
public class PdfDocumentParser implements DocumentParserStrategy {

    private static final Set<String> SUPPORTED_TYPES = Set.of("pdf");

    private final MineruClient mineruClient;

    public PdfDocumentParser(MineruClient mineruClient) {
        this.mineruClient = mineruClient;
    }

    @Override
    public Set<String> supportedFileTypes() {
        return SUPPORTED_TYPES;
    }

    @Override
    public DocumentParseResult parse(DocumentParseContext context) {
        // MinerU 当前任务接口只接受公网 URL，因此传入上传后生成的 MinIO 地址；
        // PDF 策略不会在应用内部根据 docId 再下载一次原文件。
        return mineruClient.parse(context.document().getDocUrl());
    }
}
