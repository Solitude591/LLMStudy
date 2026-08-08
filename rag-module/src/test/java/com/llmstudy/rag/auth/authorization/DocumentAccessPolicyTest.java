package com.llmstudy.rag.auth.authorization;

import com.llmstudy.rag.auth.mapper.AuthUserMapper;
import com.llmstudy.rag.auth.model.AccessContext;
import com.llmstudy.rag.auth.model.DocumentVisibility;
import com.llmstudy.rag.auth.model.UserRole;
import com.llmstudy.rag.entity.KnowledgeDocument;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class DocumentAccessPolicyTest {

    private final DocumentAccessPolicy policy =
            new DocumentAccessPolicy(mock(AuthUserMapper.class));

    @Test
    void privateDocumentOnlyAllowsOwnerAndSystemAdmin() {
        KnowledgeDocument document = document(DocumentVisibility.PRIVATE, "owner", null);

        assertTrue(policy.canRead(document, actor("owner", "org-a", UserRole.USER)));
        assertFalse(policy.canRead(document, actor("other", "org-a", UserRole.ORG_ADMIN)));
        assertTrue(policy.canWrite(document, actor("sys", null, UserRole.SYS_ADMIN)));
    }

    @Test
    void organizationDocumentAllowsSameOrganizationReadAndAdminWrite() {
        KnowledgeDocument document = document(
                DocumentVisibility.ORGANIZATION, "owner", "org-a");

        assertTrue(policy.canRead(document, actor("member", "org-a", UserRole.USER)));
        assertFalse(policy.canRead(document, actor("other", "org-b", UserRole.USER)));
        assertTrue(policy.canWrite(document,
                actor("admin", "org-a", UserRole.ORG_ADMIN)));
        assertFalse(policy.canWrite(document,
                actor("admin-b", "org-b", UserRole.ORG_ADMIN)));
    }

    @Test
    void publicDocumentAllowsAllAuthenticatedReadersButNotForeignWriters() {
        KnowledgeDocument document = document(DocumentVisibility.PUBLIC, "owner", null);

        assertTrue(policy.canRead(document, actor("other", "org-b", UserRole.USER)));
        assertFalse(policy.canWrite(document, actor("other", "org-b", UserRole.USER)));
    }

    private static KnowledgeDocument document(DocumentVisibility visibility,
                                               String owner, String organizationId) {
        KnowledgeDocument document = new KnowledgeDocument();
        document.setOwnerUserId(owner);
        document.setDocumentVisibility(visibility);
        document.setOrganizationId(organizationId);
        return document;
    }

    private static AccessContext actor(String userId, String organizationId, UserRole role) {
        return new AccessContext(userId, organizationId, role);
    }
}
