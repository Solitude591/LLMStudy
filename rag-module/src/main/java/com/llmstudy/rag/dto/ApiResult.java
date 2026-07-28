package com.llmstudy.rag.dto;

/**
 * 统一 API 响应包装
 */
public class ApiResult<T> {

    private int code;
    private String message;
    private T data;

    private ApiResult(int code, String message, T data) {
        this.code = code;
        this.message = message;
        this.data = data;
    }

    public static <T> ApiResult<T> ok(T data) {
        return new ApiResult<>(0, "ok", data);
    }

    public static <T> ApiResult<T> ok(String message, T data) {
        return new ApiResult<>(0, message, data);
    }

    public static ApiResult<Void> fail(int code, String message) {
        return new ApiResult<>(code, message, null);
    }

    public static ApiResult<Void> fail(String message) {
        return new ApiResult<>(-1, message, null);
    }

    // ========== Getters & Setters ==========

    public int getCode() { return code; }
    public void setCode(int code) { this.code = code; }

    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }

    public T getData() { return data; }
    public void setData(T data) { this.data = data; }
}
