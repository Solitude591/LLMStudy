package com.llmstudy.rag.auth.authorization;

/**
 * 已登录用户访问无权限资源时抛出的异常，统一转换为 HTTP 403。
 */
public class ResourceAccessDeniedException extends RuntimeException {
    public ResourceAccessDeniedException(String message) {
        super(message);
    }
}
