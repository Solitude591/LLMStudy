package com.llmstudy.rag.auth.dto;

/**
 * 登录成功响应。
 *
 * @param token Sa-Token 生成的登录令牌
 */
public record LoginResponse(String token) {
}
