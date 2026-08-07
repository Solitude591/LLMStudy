package com.llmstudy.rag.dto;

/** 发布或回滚后的当前版本指针。 */
public record VersionPublishResult(
        String docId,
        String previousVersionId,
        String currentVersionId,
        String releaseStatus,
        boolean switched) {
}
