package com.llmstudy.rag.auth.entity;

import com.llmstudy.rag.auth.model.AuthenticatedUser;
import com.llmstudy.rag.auth.model.UserRole;
import com.llmstudy.rag.auth.model.UserStatus;

import java.time.LocalDateTime;

/**
 * 认证用户数据库实体，对应 {@code auth_user} 表。
 *
 * <p>{@code passwordHash} 只在登录校验时使用，不应出现在 Controller 响应或业务上下文中。</p>
 */
public class AuthUser {
    private Long id;
    private String userId;
    private String username;
    private String passwordHash;
    private String displayName;
    private String organizationId;
    private String organizationName;
    private String role;
    private String status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public String getPasswordHash() { return passwordHash; }
    public void setPasswordHash(String passwordHash) { this.passwordHash = passwordHash; }
    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }
    public String getOrganizationId() { return organizationId; }
    public void setOrganizationId(String organizationId) { this.organizationId = organizationId; }
    public String getOrganizationName() { return organizationName; }
    public void setOrganizationName(String organizationName) { this.organizationName = organizationName; }
    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }

    /** @return 将数据库角色字符串转换为强类型枚举 */
    public UserRole userRole() { return UserRole.valueOf(role); }

    /** @return 将数据库账号状态字符串转换为强类型枚举 */
    public UserStatus userStatus() { return UserStatus.valueOf(status); }

    /**
     * 生成不含密码的身份快照，供业务入口捕获并向后续流程传递。
     */
    public AuthenticatedUser toAuthenticatedUser() {
        return new AuthenticatedUser(userId, username, displayName, organizationId, userRole());
    }
}
