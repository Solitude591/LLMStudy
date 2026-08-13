package com.llmstudy.rag.module.rag.query;

/**
 * 查询改写失败。
 *
 * <p>对外固定返回「模型内部错误」，不把供应商响应、JSON 原文或堆栈传给客户端。
 * 原始原因作为 cause 保留，只写服务端日志。</p>
 */
public class QueryRewriteException extends RuntimeException {

    public static final String SAFE_MESSAGE = "模型内部错误";

    public QueryRewriteException(Throwable cause) {
        super(SAFE_MESSAGE, cause);
    }

    public QueryRewriteException(String detail) {
        super(SAFE_MESSAGE, new IllegalStateException(detail));
    }
}
