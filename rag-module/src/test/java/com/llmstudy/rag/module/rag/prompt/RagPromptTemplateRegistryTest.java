package com.llmstudy.rag.module.rag.prompt;

import com.llmstudy.rag.module.rag.model.RagAnswerMode;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.ResourceLoader;

import java.nio.charset.StandardCharsets;
import java.util.EnumSet;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RagPromptTemplateRegistryTest {

    private static final String GENERIC_PATH = "classpath:prompts/rag/answer.st";

    @Test
    void loadsDistinctTemplateForEverySpecializedMode() {
        RagPromptTemplateRegistry registry =
                new RagPromptTemplateRegistry(new DefaultResourceLoader());
        String generic = registry.select(RagAnswerMode.GENERIC).template();

        for (RagAnswerMode mode : EnumSet.complementOf(
                EnumSet.of(RagAnswerMode.GENERIC))) {
            RagPromptTemplateRegistry.TemplateSelection selection = registry.select(mode);
            assertEquals(mode, selection.effectiveMode());
            assertFalse(selection.fallback());
            assertNotEquals(generic, selection.template());
            assertTrue(selection.template().contains("{intentContext}"));
            String rendered = new PromptTemplate(selection.template()).create(Map.of(
                    "intentContext", "intent",
                    "information", "evidence",
                    "question", "question")).getContents();
            assertTrue(rendered.contains("intent"));
            assertTrue(rendered.contains("evidence"));
            assertTrue(rendered.contains("question"));
        }
    }

    @Test
    void missingSpecializedTemplateFallsBackToGeneric() {
        ResourceLoader loader = mock(ResourceLoader.class);
        when(loader.getResource(anyString()))
                .thenReturn(new ClassPathResource("missing-template.st"));
        when(loader.getResource(GENERIC_PATH)).thenReturn(text("generic {question}"));

        RagPromptTemplateRegistry.TemplateSelection selection =
                new RagPromptTemplateRegistry(loader).select(RagAnswerMode.PAPER_SUMMARY);

        assertEquals(RagAnswerMode.GENERIC, selection.effectiveMode());
        assertTrue(selection.fallback());
        assertEquals("generic {question}", selection.template());
    }

    @Test
    void missingGenericTemplateFailsFast() {
        ResourceLoader loader = mock(ResourceLoader.class);
        when(loader.getResource(anyString()))
                .thenReturn(new ClassPathResource("missing-template.st"));

        assertThrows(IllegalStateException.class,
                () -> new RagPromptTemplateRegistry(loader));
    }

    private static ByteArrayResource text(String value) {
        return new ByteArrayResource(value.getBytes(StandardCharsets.UTF_8));
    }
}
