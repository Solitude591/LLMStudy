package com.llmstudy.rag.auth.dto;

/**
 * 修改文档可见范围的请求体。
 *
 * <p>请求中故意不接收 organizationId，组织归属由服务端根据文档所有者推导，
 * 防止客户端把文档挂到无权管理的组织。</p>
 */
public record DocumentVisibilityRequest(String visibility) {
}
