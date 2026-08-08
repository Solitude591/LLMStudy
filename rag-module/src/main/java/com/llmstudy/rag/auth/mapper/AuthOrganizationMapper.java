package com.llmstudy.rag.auth.mapper;

import com.llmstudy.rag.auth.entity.AuthOrganization;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** 组织基础信息数据访问接口。 */
@Mapper
public interface AuthOrganizationMapper {

    /**
     * 根据稳定的组织业务 ID 查询组织。
     *
     * @param organizationId 组织业务 ID
     * @return 组织实体；不存在时返回 {@code null}
     */
    @Select("""
            SELECT id, organization_id, organization_name, created_at, updated_at
            FROM auth_organization
            WHERE organization_id = #{organizationId}
            """)
    AuthOrganization findByOrganizationId(@Param("organizationId") String organizationId);
}
