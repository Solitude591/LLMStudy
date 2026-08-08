package com.llmstudy.rag.auth.config;

import cn.dev33.satoken.interceptor.SaInterceptor;
import cn.dev33.satoken.router.SaRouter;
import cn.dev33.satoken.stp.StpUtil;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Sa-Token Web 鉴权与密码编码配置。
 */
@Configuration
public class AuthConfig implements WebMvcConfigurer {

    /**
     * 无需登录即可访问的页面和静态资源。
     *
     * <p>业务 API 不应加入该列表；除登录接口外，所有接口均要求有效登录态。</p>
     */
    private static final String[] PUBLIC_PATHS = {
            "/auth/login", "/", "/index.html", "/login.html", "/chat.html",
            "/upload.html", "/css/**", "/js/**", "/favicon.ico", "/error"
    };

    /**
     * 注册全局 Sa-Token 拦截器，并启用控制器上的鉴权注解处理。
     */
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // 先排除登录页和静态资源，再对剩余路径统一执行登录检查。
        registry.addInterceptor(new SaInterceptor(handler -> SaRouter.match("/**")
                        .notMatch(PUBLIC_PATHS)
                        .check(route -> StpUtil.checkLogin())))
                .addPathPatterns("/**");
    }

    /**
     * 提供 BCrypt 密码编码器；强度 10 与演示账号哈希保持一致。
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(10);
    }
}
