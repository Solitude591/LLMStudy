package com.llmstudy.rag.module.knowledge.ingestion.parser;

import com.llmstudy.rag.client.MineruClient;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * PDF 解析策略：使用 MinerU 生成 Markdown、content_list 和图片。
 */
@Component
public class PdfDocumentParser extends AbstractMineruDocumentParser {

    private static final Set<String> SUPPORTED_TYPES = Set.of("pdf");

    public PdfDocumentParser(MineruClient mineruClient) {
        super(mineruClient);
    }

    /** {@inheritDoc} */
    @Override
    public Set<String> supportedFileTypes() {
        return SUPPORTED_TYPES;
    }

}
