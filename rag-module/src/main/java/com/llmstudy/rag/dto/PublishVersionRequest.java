package com.llmstudy.rag.dto;

/**
 * 发布或回滚文档版本时的乐观并发条件。
 *
 * @param expectedCurrentVersionId 客户端读取到的当前版本；首次发布传 null
 */
public record PublishVersionRequest(String expectedCurrentVersionId) {
}
