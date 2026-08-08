package com.llmstudy.rag.auth.mapper;

import com.llmstudy.rag.auth.entity.AuthUser;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 认证用户数据访问接口。
 *
 * <p>用户查询统一关联组织表，确保登录响应和当前用户接口可以同时获得组织名称。</p>
 */
@Mapper
public interface AuthUserMapper {

    /** 用户及其组织资料的公共查询字段，避免两个身份查询产生字段差异。 */
    String USER_SELECT = """
            SELECT u.id, u.user_id, u.username, u.password_hash, u.display_name,
                   u.organization_id, o.organization_name, u.role, u.status,
                   u.created_at, u.updated_at
            FROM auth_user u
            LEFT JOIN auth_organization o ON o.organization_id = u.organization_id
            """;

    /**
     * 按登录名查找账号，用于密码校验。
     *
     * @param username 已去除首尾空白的登录名
     * @return 用户实体；不存在时返回 {@code null}
     */
    @Select(USER_SELECT + " WHERE u.username = #{username} LIMIT 1")
    AuthUser findByUsername(@Param("username") String username);

    /**
     * 按 Sa-Token 登录 ID 重新加载账号，用于每次请求校验账号是否仍然启用。
     */
    @Select(USER_SELECT + " WHERE u.user_id = #{userId} LIMIT 1")
    AuthUser findByUserId(@Param("userId") String userId);
}
