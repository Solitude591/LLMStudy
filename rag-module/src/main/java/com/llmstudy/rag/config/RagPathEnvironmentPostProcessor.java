package com.llmstudy.rag.config;

import com.llmstudy.rag.RagApplication;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.system.ApplicationHome;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.io.ResourceLoader;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Logback 在容器启动前读 {@code rag.llm-log.path}，所以这里用 EnvironmentPostProcessor
 * 把相对的 logs / models 路径接到 rag-module（或 JAR 所在目录），避免跟启动工作目录走。
 */
public class RagPathEnvironmentPostProcessor implements EnvironmentPostProcessor {

    static final String LLM_LOG_PATH = "rag.llm-log.path";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        Path root = moduleRoot();
        Map<String, Object> overrides = new LinkedHashMap<>();
        rebase(environment, overrides, LLM_LOG_PATH, root, "logs");
        rebase(environment, overrides, "rag.reranker.model-path", root, null);
        rebase(environment, overrides, "rag.reranker.tokenizer-path", root, null);
        if (!overrides.isEmpty()) {
            environment.getPropertySources().addFirst(new MapPropertySource("ragModulePaths", overrides));
        }
    }

    static Path moduleRoot() {
        Path current = new ApplicationHome(RagApplication.class).getDir().toPath().toAbsolutePath().normalize();
        for (int i = 0; i < 8 && current != null; i++) {
            if (isRagModule(current)) {
                return current;
            }
            current = current.getParent();
        }
        Path cwd = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        if (isRagModule(cwd)) {
            return cwd;
        }
        Path nested = cwd.resolve("rag-module");
        return isRagModule(nested) ? nested : cwd;
    }

    private static void rebase(
            ConfigurableEnvironment environment,
            Map<String, Object> overrides,
            String key,
            Path root,
            String blankDefault) {
        String value = environment.getProperty(key);
        if (value == null || value.isBlank()) {
            if (blankDefault != null) {
                overrides.put(key, root.resolve(blankDefault).toString());
            }
            return;
        }
        if (value.startsWith(ResourceLoader.CLASSPATH_URL_PREFIX) || value.startsWith("file:")) {
            return;
        }
        Path path = Path.of(value);
        if (path.isAbsolute()) {
            return;
        }
        overrides.put(key, root.resolve(path).normalize().toString());
    }

    private static boolean isRagModule(Path dir) {
        return dir != null
                && "rag-module".equals(fileName(dir))
                && Files.isRegularFile(dir.resolve("pom.xml"));
    }

    private static String fileName(Path path) {
        return path == null || path.getFileName() == null ? "" : path.getFileName().toString();
    }
}
