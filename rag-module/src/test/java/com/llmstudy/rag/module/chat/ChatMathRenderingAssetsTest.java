package com.llmstudy.rag.module.chat;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatMathRenderingAssetsTest {

    @Test
    void mathJaxLoadsBeforeChatAndRenderingRunsAfterSanitizing() throws Exception {
        String html = resource("/static/chat.html");
        String javascript = resource("/static/js/chat.js");
        String mathJaxPath = "webjars/mathjax/3.2.2/es5/tex-chtml.js";

        assertTrue(html.indexOf(mathJaxPath) < html.indexOf("js/chat.js"));
        assertTrue(html.contains("inlineMath"));
        assertTrue(html.contains("displayMath"));
        assertNotNull(getClass().getResource(
                "/META-INF/resources/webjars/mathjax/3.2.2/es5/tex-chtml.js"));

        assertTrue(javascript.contains("protectMathExpressions(raw)"));
        assertTrue(javascript.contains("restoreMathExpressions(el, protectedMath.expressions)"));
        assertTrue(javascript.contains("window.MathJax.typesetPromise([el])"));
        assertTrue(javascript.indexOf("window.DOMPurify.sanitize(parsed")
                < javascript.indexOf("restoreMathExpressions(el, protectedMath.expressions)"));
        assertTrue(javascript.indexOf("restoreMathExpressions(el, protectedMath.expressions)")
                < javascript.indexOf("renderMath(el, protectedMath.expressions.length > 0)"));
    }

    /**
     * script 标签请求不携带 Authorization 头，公式脚本必须匿名可访问，
     * 否则 Sa-Token 拦截器会让 tex-chtml.js 返回 401，公式永远无法渲染。
     */
    @Test
    void mathJaxWebJarIsAnonymousAccessible() throws IOException {
        String authConfig = Files.readString(Path.of(
                "src/main/java/com/llmstudy/rag/auth/config/AuthConfig.java"), StandardCharsets.UTF_8);
        assertTrue(authConfig.contains("\"/webjars/**\""),
                "AuthConfig.PUBLIC_PATHS 必须放行 /webjars/**，否则 MathJax 脚本 401");
    }

    private static String resource(String path) throws IOException {
        try (InputStream input = ChatMathRenderingAssetsTest.class.getResourceAsStream(path)) {
            assertNotNull(input, "missing test resource: " + path);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
