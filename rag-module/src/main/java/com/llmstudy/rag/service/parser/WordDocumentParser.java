package com.llmstudy.rag.service.parser;

import com.llmstudy.rag.client.MineruClient;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * Word 解析策略：DOC 和 DOCX 均复用 MinerU 文档解析流程。
 */
@Component
public class WordDocumentParser extends AbstractMineruDocumentParser {

    private static final Set<String> SUPPORTED_TYPES = Set.of("doc", "docx");

    public WordDocumentParser(MineruClient mineruClient) {
        super(mineruClient);
    }

    @Override
    public Set<String> supportedFileTypes() {
        return SUPPORTED_TYPES;
    }
}
