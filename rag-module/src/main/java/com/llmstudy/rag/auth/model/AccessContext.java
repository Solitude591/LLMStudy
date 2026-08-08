package com.llmstudy.rag.auth.model;

/**
 * 一次业务请求使用的最小权限上下文。
 *
 * <p>它是可跨线程传递的普通不可变对象，不依赖 Sa-Token ThreadLocal，
 * 因此 SSE、Reactor 和后台任务必须在入口处捕获后显式传递本对象。</p>
 *
 * @param userId 当前用户 ID
 * @param organizationId 当前用户所属组织；无组织时为 {@code null}
 * @param role 当前用户角色
 */
public record AccessContext(String userId, String organizationId, UserRole role) {

    public AccessContext {
        // 权限上下文一旦进入异步链路就无法再从请求线程补全，因此提前拒绝不完整身份。
        if (userId == null || userId.isBlank() || role == null) {
            throw new IllegalArgumentException("访问上下文不完整");
        }
    }

    /**
     * 从完整认证用户中提取权限判断所需的最小字段。
     *
     * @param user 已完成账号状态校验的用户
     * @return 可安全跨线程传递的访问上下文
     */
    public static AccessContext from(AuthenticatedUser user) {
        return new AccessContext(user.userId(), user.organizationId(), user.role());
    }

    /** @return 当前身份是否具有系统管理员角色 */
    public boolean isSystemAdmin() {
        return role == UserRole.SYS_ADMIN;
    }
}
