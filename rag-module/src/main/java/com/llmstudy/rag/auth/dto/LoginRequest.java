package com.llmstudy.rag.auth.dto;

/**
 * 登录接口请求体。
 *
 * @param username 用户名
 * @param password 明文密码；仅在本次请求内用于 BCrypt 校验，不会持久化
 */
public record LoginRequest(String username, String password) {
}
