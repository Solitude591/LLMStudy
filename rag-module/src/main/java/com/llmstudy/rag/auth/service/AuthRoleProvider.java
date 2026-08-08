package com.llmstudy.rag.auth.service;

import cn.dev33.satoken.stp.StpInterface;
import com.llmstudy.rag.auth.entity.AuthUser;
import com.llmstudy.rag.auth.mapper.AuthUserMapper;
import com.llmstudy.rag.auth.model.UserRole;
import com.llmstudy.rag.auth.model.UserStatus;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Sa-Token 角色与权限数据提供器。
 *
 * <p>每次注解鉴权都从数据库加载账号，确保角色变更或账号停用即时生效。</p>
 */
@Component
public class AuthRoleProvider implements StpInterface {

    private final AuthUserMapper userMapper;

    public AuthRoleProvider(AuthUserMapper userMapper) {
        this.userMapper = userMapper;
    }

    /**
     * 返回用户具备的细粒度权限。
     *
     * <p>当前仅系统管理员拥有手动文档处理权限，后续权限可继续在此集中扩展。</p>
     */
    @Override
    public List<String> getPermissionList(Object loginId, String loginType) {
        AuthUser user = enabled(loginId);
        return user != null && user.userRole() == UserRole.SYS_ADMIN
                ? List.of("document:process") : List.of();
    }

    /**
     * 返回带继承关系的角色列表，使高等级角色同时具备基础角色能力。
     */
    @Override
    public List<String> getRoleList(Object loginId, String loginType) {
        AuthUser user = enabled(loginId);
        if (user == null) {
            return List.of();
        }
        return switch (user.userRole()) {
            case USER -> List.of(UserRole.USER.name());
            case ORG_ADMIN -> List.of(UserRole.USER.name(), UserRole.ORG_ADMIN.name());
            case SYS_ADMIN -> List.of(UserRole.USER.name(), UserRole.ORG_ADMIN.name(),
                    UserRole.SYS_ADMIN.name());
        };
    }

    /** 加载账号并过滤不存在或已停用的用户。 */
    private AuthUser enabled(Object loginId) {
        if (loginId == null) {
            return null;
        }
        AuthUser user = userMapper.findByUserId(loginId.toString());
        return user != null && user.userStatus() == UserStatus.ENABLED ? user : null;
    }
}
