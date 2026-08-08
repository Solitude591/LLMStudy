package com.llmstudy.rag.auth.service;

import cn.dev33.satoken.stp.StpUtil;
import com.llmstudy.rag.auth.authorization.AuthenticationException;
import com.llmstudy.rag.auth.entity.AuthUser;
import com.llmstudy.rag.auth.mapper.AuthUserMapper;
import com.llmstudy.rag.auth.model.AccessContext;
import com.llmstudy.rag.auth.model.AuthenticatedUser;
import com.llmstudy.rag.auth.model.UserStatus;
import org.springframework.stereotype.Component;

/**
 * 当前请求身份提供器。
 *
 * <p>只有该组件直接从 {@link StpUtil} 读取登录 ID。业务 Controller 应在请求入口
 * 调用本组件捕获身份，异步线程不得再次读取 Sa-Token ThreadLocal。</p>
 */
@Component
public class CurrentUserProvider {

    private final AuthUserMapper userMapper;

    public CurrentUserProvider(AuthUserMapper userMapper) {
        this.userMapper = userMapper;
    }

    /**
     * 获取并重新校验当前账号实体。
     *
     * <p>即使 Token 仍有效，也会检查数据库中的账号状态，以便停用账号及时失效。</p>
     */
    public AuthUser requireUserEntity() {
        // 先让 Sa-Token 校验 Token、最长有效期和闲置超时。
        StpUtil.checkLogin();
        String userId = StpUtil.getLoginIdAsString();
        AuthUser user = userMapper.findByUserId(userId);
        if (user == null || user.userStatus() != UserStatus.ENABLED) {
            // 删除已失效账号的 Redis 登录态，避免同一 Token 被反复尝试。
            StpUtil.logout();
            throw new AuthenticationException("当前账号不存在或已停用");
        }
        return user;
    }

    /** @return 不含密码等敏感信息的当前用户身份快照 */
    public AuthenticatedUser requireCurrentUser() {
        return requireUserEntity().toAuthenticatedUser();
    }

    /**
     * 获取适合跨线程传递的最小访问上下文。
     */
    public AccessContext requireAccessContext() {
        return AccessContext.from(requireCurrentUser());
    }
}
