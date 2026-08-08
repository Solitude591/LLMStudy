package com.llmstudy.rag.auth.authorization;

import com.llmstudy.rag.auth.entity.AuthUser;
import com.llmstudy.rag.auth.mapper.AuthUserMapper;
import com.llmstudy.rag.auth.model.AccessContext;
import com.llmstudy.rag.auth.model.DocumentVisibility;
import com.llmstudy.rag.auth.model.UserRole;
import com.llmstudy.rag.entity.KnowledgeDocument;
import org.springframework.stereotype.Component;

import java.util.Objects;

/**
 * 文档读写权限的唯一判定入口。
 *
 * <p>Controller 和文档服务不自行拼装角色条件，统一通过本策略组合文档所有者、
 * 文档可见范围、用户组织和角色，避免不同接口出现不一致的授权规则。</p>
 */
@Component
public class DocumentAccessPolicy {

    private final AuthUserMapper userMapper;

    public DocumentAccessPolicy(AuthUserMapper userMapper) {
        this.userMapper = userMapper;
    }

    /**
     * 判断用户是否可以读取文档。
     *
     * <p>拥有写权限必然拥有读权限；其余用户再按照公开或同组织规则判断。</p>
     */
    public boolean canRead(KnowledgeDocument document, AccessContext actor) {
        if (document == null || actor == null) {
            return false;
        }
        if (canWrite(document, actor)) {
            // 所有者、允许管理该文档的组织管理员和系统管理员无需重复判断可见范围。
            return true;
        }
        return switch (document.getDocumentVisibility()) {
            case PRIVATE -> false;
            case PUBLIC -> true;
            case ORGANIZATION -> actor.organizationId() != null
                    && Objects.equals(actor.organizationId(), document.getOrganizationId());
        };
    }

    /**
     * 判断用户是否可以修改文档元数据或版本。
     */
    public boolean canWrite(KnowledgeDocument document, AccessContext actor) {
        if (document == null || actor == null) {
            return false;
        }
        if (actor.role() == UserRole.SYS_ADMIN
                || Objects.equals(actor.userId(), document.getOwnerUserId())) {
            // 系统管理员和文档所有者始终可以管理文档。
            return true;
        }
        // 组织管理员的管理权只覆盖本组织的 ORGANIZATION 文档，不覆盖私有或公开文档。
        return document.getDocumentVisibility() == DocumentVisibility.ORGANIZATION
                && actor.role() == UserRole.ORG_ADMIN
                && actor.organizationId() != null
                && Objects.equals(actor.organizationId(), document.getOrganizationId());
    }

    /**
     * 强制执行读权限检查。
     *
     * @throws ResourceAccessDeniedException 无读取权限时抛出
     */
    public void requireRead(KnowledgeDocument document, AccessContext actor) {
        if (!canRead(document, actor)) {
            throw new ResourceAccessDeniedException("文档不存在或无权访问");
        }
    }

    /**
     * 强制执行写权限检查。
     *
     * @throws ResourceAccessDeniedException 无修改权限时抛出
     */
    public void requireWrite(KnowledgeDocument document, AccessContext actor) {
        if (!canWrite(document, actor)) {
            throw new ResourceAccessDeniedException("无权修改该文档");
        }
    }

    /**
     * 根据文档所有者推导组织可见文档的组织 ID。
     *
     * <p>不信任客户端传入的组织 ID，避免用户把文档发布到其他组织。</p>
     */
    public String resolveOwnerOrganization(KnowledgeDocument document) {
        AuthUser owner = userMapper.findByUserId(document.getOwnerUserId());
        if (owner == null || owner.getOrganizationId() == null
                || owner.getOrganizationId().isBlank()) {
            throw new IllegalArgumentException("文档所有者未加入组织");
        }
        return owner.getOrganizationId();
    }
}
