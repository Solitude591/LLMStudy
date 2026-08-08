package com.llmstudy.rag.auth.service;

import com.llmstudy.rag.auth.entity.AuthUser;
import com.llmstudy.rag.auth.mapper.AuthUserMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AuthRoleProviderTest {

    @Test
    void organizationAdminIncludesUserAndOrganizationAdminRoles() {
        AuthUserMapper mapper = mock(AuthUserMapper.class);
        when(mapper.findByUserId("admin")).thenReturn(user("ORG_ADMIN", "ENABLED"));

        assertEquals(List.of("USER", "ORG_ADMIN"),
                new AuthRoleProvider(mapper).getRoleList("admin", "login"));
    }

    @Test
    void disabledAccountHasNoRolesOrPermissions() {
        AuthUserMapper mapper = mock(AuthUserMapper.class);
        when(mapper.findByUserId("disabled")).thenReturn(user("SYS_ADMIN", "DISABLED"));
        AuthRoleProvider provider = new AuthRoleProvider(mapper);

        assertEquals(List.of(), provider.getRoleList("disabled", "login"));
        assertEquals(List.of(), provider.getPermissionList("disabled", "login"));
    }

    private static AuthUser user(String role, String status) {
        AuthUser user = new AuthUser();
        user.setRole(role);
        user.setStatus(status);
        return user;
    }
}
