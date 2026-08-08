package com.llmstudy.rag.auth.dto;

import com.llmstudy.rag.auth.entity.AuthUser;
import com.llmstudy.rag.auth.model.AuthenticatedUser;

/**
 * 返回给页面的当前用户资料，不包含密码哈希和数据库自增主键。
 */
public record CurrentUserResponse(String userId,
                                  String username,
                                  String displayName,
                                  String organizationId,
                                  String organizationName,
                                  String role) {

    /**
     * 从数据库用户实体构建响应，保留关联查询得到的组织名称。
     */
    public static CurrentUserResponse from(AuthUser user) {
        return new CurrentUserResponse(user.getUserId(), user.getUsername(),
                user.getDisplayName(), user.getOrganizationId(),
                user.getOrganizationName(), user.getRole());
    }

    /**
     * 从轻量身份快照构建响应；该快照不携带组织名称，因此名称返回空值。
     */
    public static CurrentUserResponse from(AuthenticatedUser user) {
        return new CurrentUserResponse(user.userId(), user.username(),
                user.displayName(), user.organizationId(), null, user.role().name());
    }
}
