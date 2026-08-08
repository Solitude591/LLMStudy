package com.llmstudy.rag.auth.authorization;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AuthExceptionHandlerTest {

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new FailureController())
                .setControllerAdvice(new AuthExceptionHandler())
                .build();
    }

    @Test
    void authenticationFailureReturns401ApiResult() throws Exception {
        mvc.perform(get("/failure/unauthorized"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));
    }

    @Test
    void authorizationFailureReturns403ApiResult() throws Exception {
        mvc.perform(get("/failure/forbidden"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403));
    }

    @Test
    void redisFailureReturns503ApiResult() throws Exception {
        mvc.perform(get("/failure/redis"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value(503));
    }

    @RestController
    static class FailureController {
        @GetMapping("/failure/unauthorized")
        void unauthorized() {
            throw new AuthenticationException("未登录");
        }

        @GetMapping("/failure/forbidden")
        void forbidden() {
            throw new ResourceAccessDeniedException("无权访问");
        }

        @GetMapping("/failure/redis")
        void redis() {
            throw new RedisConnectionFailureException("redis down");
        }
    }
}
