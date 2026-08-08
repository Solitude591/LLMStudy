package com.llmstudy.rag.auth.controller;

import com.llmstudy.rag.auth.dto.CurrentUserResponse;
import com.llmstudy.rag.auth.dto.LoginRequest;
import com.llmstudy.rag.auth.dto.LoginResponse;
import com.llmstudy.rag.auth.service.AuthService;
import com.llmstudy.rag.dto.ApiResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 登录态 HTTP 接口。
 *
 * <p>仅负责协议参数和统一响应包装，密码验证及 Sa-Token 操作由 {@link AuthService} 完成。</p>
 */
@RestController
@RequestMapping("/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    /**
     * 使用用户名和密码登录，成功后返回前端需要保存的 Bearer Token。
     */
    @PostMapping("/login")
    public ApiResult<LoginResponse> login(@RequestBody LoginRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("请求体不能为空");
        }
        return ApiResult.ok("登录成功", authService.login(request.username(), request.password()));
    }

    /** 注销当前 Token；其他设备或其他 Token 的登录态不受影响。 */
    @PostMapping("/logout")
    public ApiResult<Void> logout() {
        authService.logout();
        return ApiResult.ok(null);
    }

    /** 返回当前登录用户资料，同时校验账号是否仍处于启用状态。 */
    @GetMapping("/me")
    public ApiResult<CurrentUserResponse> me() {
        return ApiResult.ok(CurrentUserResponse.from(authService.currentUser()));
    }
}
