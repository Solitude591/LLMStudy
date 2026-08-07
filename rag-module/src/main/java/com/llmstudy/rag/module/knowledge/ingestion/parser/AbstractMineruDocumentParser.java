package com.llmstudy.rag.module.knowledge.ingestion.parser;

import com.llmstudy.rag.client.MineruClient;
import com.llmstudy.rag.dto.DocumentParseResult;

/**
 * 基于 MinerU 的文档解析策略公共实现。
 *
 * <p>PDF、Word 等由 MinerU 处理的格式只需要声明各自支持的扩展名，
 * 提交任务、轮询结果和下载解析产物的流程统一复用 {@link MineruClient}。</p>
 */
public abstract class AbstractMineruDocumentParser implements DocumentParserStrategy {

    private final MineruClient mineruClient;

    protected AbstractMineruDocumentParser(MineruClient mineruClient) {
        this.mineruClient = mineruClient;
    }

    /** {@inheritDoc} */
    @Override
    public final DocumentParseResult parse(DocumentParseContext context) {
        // MinerU 当前任务接口接收公网 URL，由服务端从 MinIO 拉取原始文件。
        return mineruClient.parse(context.version().getDocUrl());
    }
}
