package com.llmstudy.rag.dto;

import java.time.LocalDateTime;

/** 文档物理版本详情，用于版本列表、状态轮询、发布和回滚选择。 */
public record DocumentVersionVO(
        String docId,
        String versionId,
        Integer versionNo,
        String contentHash,
        String fileType,
        String uploadedBy,
        String docUrl,
        String convertedDocUrl,
        String processingStatus,
        String releaseStatus,
        String errorMessage,
        Integer retryCount,
        String changeSummary,
        LocalDateTime readyAt,
        LocalDateTime publishedAt,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        boolean current) {
}
