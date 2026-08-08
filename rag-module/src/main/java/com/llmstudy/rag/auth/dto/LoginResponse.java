package com.llmstudy.rag.auth.dto;

/**
 * 登录成功响应。
 *
 * @param tokenName HTTP Token 名称，当前固定为 Authorization
 * @param tokenType Token 前缀，当前固定为 Bearer
 * @param tokenValue Sa-Token 生成的登录令牌
 * @param expiresInSeconds Token 最长有效秒数
 * @param user 当前登录用户资料
 */
public record LoginResponse(String tokenName,
                            String tokenType,
                            String tokenValue,
                            long expiresInSeconds,
                            CurrentUserResponse user) {
}
