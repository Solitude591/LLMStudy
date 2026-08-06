package com.llmstudy.rag.module.rag.prompt;

import com.llmstudy.rag.module.knowledge.model.SegmentMetadataKeys;
import com.llmstudy.rag.module.rag.model.RagAnswerMode;
import com.llmstudy.rag.module.rag.model.RagFocusInformation;
import com.llmstudy.rag.module.rag.model.RagIntentContext;
import com.llmstudy.rag.module.rag.model.RagRequest;
import com.llmstudy.rag.module.rag.model.RetrievalCandidate;
import com.llmstudy.rag.module.rag.model.RewrittenQuery;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verifyNoInteractions;

class RagPromptInjectorTest {

    @Test
    void selectsIntentTemplateAndInjectsFocusAndNumberedEvidence() {
        RagPromptInjector injector = new RagPromptInjector(
                new RagPromptTemplateRegistry(new DefaultResourceLoader()));
        RagRequest request = new RagRequest("表 3 说明了什么？", "无",
                new RagIntentContext(RagAnswerMode.EXPERIMENT_OR_RESULT,
                        new RagFocusInformation(List.of("RAG Paper"), List.of(),
                                List.of(), List.of("Hybrid RAG"),
                                List.of("HotpotQA"), List.of("F1"), List.of("表 3"))));
        RetrievalCandidate candidate = new RetrievalCandidate(
                "chunk-1", "F1 提升了 2.1 个百分点。",
                Map.of(SegmentMetadataKeys.DOC_ID, "doc-1",
                        SegmentMetadataKeys.HEADER_PATH, "实验 > 主结果"),
                0.8, 0.9);

        RagPromptInjector.Injection result = injector.inject(request,
                new RewrittenQuery(request.question(), "RAG 表 3 F1 结果"),
                List.of(candidate));

        assertTrue(result.prompt().systemMessage().contains("实验设置、数据集、指标"));
        assertTrue(result.prompt().userMessage().contains("主意图: EXPERIMENT_OR_RESULT"));
        assertTrue(result.prompt().userMessage().contains("方法或模型: Hybrid RAG"));
        assertTrue(result.prompt().userMessage().contains("数据集: HotpotQA"));
        assertTrue(result.prompt().userMessage().contains("评价指标: F1"));
        assertTrue(result.prompt().userMessage().contains("[1]"));
        assertTrue(result.prompt().userMessage().contains("F1 提升了 2.1 个百分点"));
        assertTrue(result.prompt().userMessage().contains("<reference_information>"));
        assertFalse(result.prompt().systemMessage().contains("F1 提升了 2.1 个百分点"));
        assertEquals("chunk-1", result.references().getFirst().chunkId());
    }

    @Test
    void emptyCandidatesKeepControlledNoKnowledgePathWithoutSelectingTemplate() {
        RagPromptTemplateRegistry registry = mock(RagPromptTemplateRegistry.class);
        RagPromptInjector.Injection result = new RagPromptInjector(registry).inject(
                new RagRequest("question", "无"),
                new RewrittenQuery("question", "rewritten"), List.of());

        assertNull(result.prompt());
        assertTrue(result.references().isEmpty());
        verifyNoInteractions(registry);
    }

    @Test
    void specializedTemplateRenderFailureFallsBackToGenericTemplate() {
        RagPromptTemplateRegistry registry = mock(RagPromptTemplateRegistry.class);
        when(registry.select(RagAnswerMode.PAPER_SUMMARY)).thenReturn(
                new RagPromptTemplateRegistry.TemplateSelection(
                        RagAnswerMode.PAPER_SUMMARY, "broken {missing}",
                        "user {intentContext}\n{information}\n{question}", false));
        when(registry.select(RagAnswerMode.GENERIC)).thenReturn(
                new RagPromptTemplateRegistry.TemplateSelection(
                        RagAnswerMode.GENERIC, "generic system",
                        "user {intentContext}\n{information}\n{question}", false));
        RagRequest request = new RagRequest("question", "无",
                new RagIntentContext(RagAnswerMode.PAPER_SUMMARY,
                        RagFocusInformation.empty()));

        RagPromptInjector.Injection result = new RagPromptInjector(registry).inject(
                request, new RewrittenQuery("question", "rewritten"),
                List.of(new RetrievalCandidate("chunk", "evidence", Map.of(), 1, null)));

        assertEquals("generic system", result.prompt().systemMessage());
        assertTrue(result.prompt().userMessage().contains("主意图: PAPER_SUMMARY"));
        assertTrue(result.prompt().userMessage().contains("evidence"));
    }
}
