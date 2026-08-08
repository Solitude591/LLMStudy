package com.llmstudy.rag.auth.service;

import cn.dev33.satoken.stp.StpUtil;
import com.llmstudy.rag.auth.authorization.AuthenticationException;
import com.llmstudy.rag.auth.dto.CurrentUserResponse;
import com.llmstudy.rag.auth.dto.LoginResponse;
import com.llmstudy.rag.auth.entity.AuthUser;
import com.llmstudy.rag.auth.mapper.AuthUserMapper;
import com.llmstudy.rag.auth.model.UserStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * 登录、登出和当前账号查询服务。
 *
 * <p>密码只使用 BCrypt 验证；登录成功后由 Sa-Token 创建并写入 Redis 登录态。</p>
 */
@Service
public class AuthService {

    /** 与 application.yml 中 Sa-Token timeout 保持一致，用于返回页面展示。 */
    private static final long TOKEN_TIMEOUT_SECONDS = 24 * 60 * 60;

    private final AuthUserMapper userMapper;
    private final PasswordEncoder passwordEncoder;
    private final CurrentUserProvider currentUserProvider;

    public AuthService(AuthUserMapper userMapper,
                       PasswordEncoder passwordEncoder,
                       CurrentUserProvider currentUserProvider) {
        this.userMapper = userMapper;
        this.passwordEncoder = passwordEncoder;
        this.currentUserProvider = currentUserProvider;
    }

    /**
     * 校验用户名和密码并创建登录态。
     *
     * @return Bearer Token 与当前用户资料
     * @throws AuthenticationException 用户不存在、密码错误或账号被停用时抛出
     */
    public LoginResponse login(String username, String password) {
        if (username == null || username.isBlank() || password == null || password.isBlank()) {
            throw new AuthenticationException("用户名或密码错误");
        }
        AuthUser user = userMapper.findByUsername(username.trim());
        if (user == null || user.userStatus() != UserStatus.ENABLED
                || !passwordEncoder.matches(password, user.getPasswordHash())) {
            // 不区分用户名、密码和状态错误，避免泄露账号是否存在。
            throw new AuthenticationException("用户名或密码错误");
        }
        // Sa-Token DAO 使用 RedisTemplate；写入失败会向上抛出并由异常处理器返回 503。
        StpUtil.login(user.getUserId());
        return new LoginResponse("Authorization", "Bearer", StpUtil.getTokenValue(),
                TOKEN_TIMEOUT_SECONDS, CurrentUserResponse.from(user));
    }

    /** 删除当前 Token 对应的 Redis 登录态。 */
    public void logout() {
        StpUtil.logout();
    }

    /** 获取经过登录状态和账号启用状态双重校验的当前用户。 */
    public AuthUser currentUser() {
        return currentUserProvider.requireUserEntity();
    }
}
