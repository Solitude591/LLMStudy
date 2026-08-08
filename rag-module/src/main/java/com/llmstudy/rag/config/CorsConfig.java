package com.llmstudy.rag.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web 跨域配置。
 *
 * <p>允许来源通过配置显式列举，页面使用 Authorization Bearer Token，
 * 因此不启用跨域 Cookie 凭证。</p>
 */
@Configuration
public class CorsConfig implements WebMvcConfigurer {

    private final String[] allowedOrigins;

    public CorsConfig(@Value("${app.cors.allowed-origins:http://localhost:8080,http://localhost:4173}")
                      String allowedOrigins) {
        // 配置使用逗号分隔，并容忍运维配置中的首尾空格。
        this.allowedOrigins = allowedOrigins.split("\\s*,\\s*");
    }

    /** 为 API 和静态页面注册统一跨域策略。 */
    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**")  // 匹配所有路径
                .allowedOrigins(allowedOrigins)
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS") // 允许的请求方法
                .allowedHeaders("*") // 包含页面发送的 Authorization 和 Content-Type
                .allowCredentials(false) // 使用 Authorization Bearer，不依赖 Cookie
                .maxAge(3600); // 预检请求的有效期（秒）
    }
}
