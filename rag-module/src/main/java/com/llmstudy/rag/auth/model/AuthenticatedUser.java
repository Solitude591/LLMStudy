package com.llmstudy.rag.auth.model;

/**
 * 已认证用户的只读身份快照。
 *
 * <p>该对象不包含密码等敏感字段，适合从 Controller 入口向业务层传递。</p>
 *
 * @param userId 用户业务 ID，也是 Sa-Token 的登录 ID
 * @param username 登录用户名
 * @param displayName 页面展示名称
 * @param organizationId 所属组织 ID；无组织用户为 {@code null}
 * @param role 当前用户角色
 */
public record AuthenticatedUser(String userId,
                                String username,
                                String displayName,
                                String organizationId,
                                UserRole role) {

    public AuthenticatedUser {
        // 用户 ID 和角色是所有权限判断的最小必要信息，构造时即保证完整。
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("userId 不能为空");
        }
        if (role == null) {
            throw new IllegalArgumentException("用户角色不能为空");
        }
    }

    /** @return 当前用户是否为系统管理员 */
    public boolean isSystemAdmin() {
        return role == UserRole.SYS_ADMIN;
    }

    /** @return 当前用户是否为组织管理员 */
    public boolean isOrganizationAdmin() {
        return role == UserRole.ORG_ADMIN;
    }
}
