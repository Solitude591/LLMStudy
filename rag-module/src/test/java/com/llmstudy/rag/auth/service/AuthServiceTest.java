package com.llmstudy.rag.auth.service;

import cn.dev33.satoken.stp.StpUtil;
import com.llmstudy.rag.auth.authorization.AuthenticationException;
import com.llmstudy.rag.auth.dto.LoginResponse;
import com.llmstudy.rag.auth.entity.AuthUser;
import com.llmstudy.rag.auth.mapper.AuthUserMapper;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

class AuthServiceTest {

    @Test
    void demoPasswordHashMatchesDocumentedInitialPassword() {
        assertTrue(new BCryptPasswordEncoder().matches("ChangeMe123!",
                "$2y$10$QOwF4gQSwPyY4Hbxf3Otcuezq7.ufOu6fj3l3YQ1JfVHeFk2xFyiq"));
    }

    @Test
    void invalidPasswordUsesGenericAuthenticationFailure() {
        AuthUserMapper mapper = mock(AuthUserMapper.class);
        AuthUser user = new AuthUser();
        user.setUserId("user-alice");
        user.setUsername("alice");
        user.setPasswordHash(new BCryptPasswordEncoder().encode("correct"));
        user.setRole("USER");
        user.setStatus("ENABLED");
        when(mapper.findByUsername("alice")).thenReturn(user);
        AuthService service = new AuthService(mapper, new BCryptPasswordEncoder(),
                mock(CurrentUserProvider.class));

        assertThrows(AuthenticationException.class,
                () -> service.login("alice", "wrong"));
    }

    @Test
    void disabledAccountCannotLogin() {
        AuthUserMapper mapper = mock(AuthUserMapper.class);
        AuthUser user = new AuthUser();
        user.setStatus("DISABLED");
        when(mapper.findByUsername("alice")).thenReturn(user);
        AuthService service = new AuthService(mapper, new BCryptPasswordEncoder(),
                mock(CurrentUserProvider.class));

        assertThrows(AuthenticationException.class,
                () -> service.login("alice", "ChangeMe123!"));
    }

    @Test
    void successfulLoginCreatesSaTokenSessionAndReturnsBearerToken() {
        AuthUserMapper mapper = mock(AuthUserMapper.class);
        AuthUser user = enabledUser();
        when(mapper.findByUsername("alice")).thenReturn(user);
        AuthService service = new AuthService(mapper, new BCryptPasswordEncoder(),
                mock(CurrentUserProvider.class));

        try (MockedStatic<StpUtil> saToken = mockStatic(StpUtil.class)) {
            saToken.when(StpUtil::getTokenValue).thenReturn("token-123");

            LoginResponse response = service.login(" alice ", "ChangeMe123!");

            saToken.verify(() -> StpUtil.login("user-alice"));
            assertEquals("token-123", response.token());
        }
    }

    @Test
    void logoutRemovesSaTokenSession() {
        AuthService service = new AuthService(mock(AuthUserMapper.class),
                new BCryptPasswordEncoder(), mock(CurrentUserProvider.class));

        try (MockedStatic<StpUtil> saToken = mockStatic(StpUtil.class)) {
            service.logout();
            saToken.verify(StpUtil::logout);
        }
    }

    @Test
    void redisFailureDuringLoginIsNotBypassed() {
        AuthUserMapper mapper = mock(AuthUserMapper.class);
        when(mapper.findByUsername("alice")).thenReturn(enabledUser());
        AuthService service = new AuthService(mapper, new BCryptPasswordEncoder(),
                mock(CurrentUserProvider.class));

        try (MockedStatic<StpUtil> saToken = mockStatic(StpUtil.class)) {
            saToken.when(() -> StpUtil.login("user-alice"))
                    .thenThrow(new RedisConnectionFailureException("redis down"));

            assertThrows(RedisConnectionFailureException.class,
                    () -> service.login("alice", "ChangeMe123!"));
        }
    }

    private static AuthUser enabledUser() {
        AuthUser user = new AuthUser();
        user.setUserId("user-alice");
        user.setUsername("alice");
        user.setDisplayName("Alice");
        user.setOrganizationId("org-a");
        user.setOrganizationName("组织 A");
        user.setPasswordHash(new BCryptPasswordEncoder().encode("ChangeMe123!"));
        user.setRole("USER");
        user.setStatus("ENABLED");
        return user;
    }
}
