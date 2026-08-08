package com.llmstudy.rag.auth.authorization;

/**
 * 账号认证失败异常，由统一异常处理器转换为 HTTP 401。
 *
 * <p>登录失败对外使用统一提示，避免根据错误信息枚举有效用户名。</p>
 */
public class AuthenticationException extends RuntimeException {
    public AuthenticationException(String message) {
        super(message);
    }
}
