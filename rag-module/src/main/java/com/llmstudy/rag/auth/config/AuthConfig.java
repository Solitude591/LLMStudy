package com.llmstudy.rag.auth.config;

import cn.dev33.satoken.interceptor.SaInterceptor;
import cn.dev33.satoken.router.SaRouter;
import cn.dev33.satoken.stp.StpUtil;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Sa-Token Web 鉴权与密码编码配置。
 */
@Configuration
public class AuthConfig implements WebMvcConfigurer {

    /**
     * 无需登录即可访问的路径。
     *
     * <p>除登录、静态页、RAGAS 数据集生成和 dev 检索诊断外，其余业务 API 均要求有效登录态。</p>
     */
    private static final String[] PUBLIC_PATHS = {
            "/auth/login", "/dataset/generate", "/dev/rag/retrieval/diagnose",
            "/", "/index.html", "/login.html", "/chat.html",
            "/upload.html", "/css/**", "/js/**", "/vendor/**", "/webjars/**", "/favicon.ico", "/error"
    };

    /**
     * 注册全局 Sa-Token 拦截器，并启用控制器上的鉴权注解处理。
     *
     * <p>SSE / {@code Flux} 会在完成时触发 {@link DispatcherType#ASYNC} 二次分发。
     * Sa-Token 默认 Filter 不会给异步线程注入上下文，若拦截器在 ASYNC 阶段再次调用
     * {@link StpUtil#checkLogin()}，就会抛出「SaTokenContext 上下文尚未初始化」。
     * 登录校验已在 {@link DispatcherType#REQUEST} 阶段完成，ASYNC 直接放行即可。</p>
     */
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        SaInterceptor saInterceptor = new SaInterceptor(handler -> SaRouter.match("/**")
                .notMatch(PUBLIC_PATHS)
                .check(route -> StpUtil.checkLogin()));

        registry.addInterceptor(new HandlerInterceptor() {
            @Override
            public boolean preHandle(HttpServletRequest request, HttpServletResponse response,
                                     Object handler) throws Exception {
                if (request.getDispatcherType() == DispatcherType.ASYNC) {
                    return true;
                }
                return saInterceptor.preHandle(request, response, handler);
            }
        }).addPathPatterns("/**");
    }

    /**
     * 提供 BCrypt 密码编码器；强度 10 与演示账号哈希保持一致。
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(10);
    }
}
