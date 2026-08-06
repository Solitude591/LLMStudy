package com.llmstudy.rag.module.chat.flow;

import com.llmstudy.rag.module.rag.RagPipeline;
import com.llmstudy.rag.module.rag.model.RagResult;
import com.llmstudy.rag.module.rag.model.RagAnswerMode;
import com.llmstudy.rag.module.rag.model.RewrittenQuery;
import com.llmstudy.rag.module.chat.model.ChatIntent;
import com.llmstudy.rag.module.chat.model.IntentKeyInformation;
import com.llmstudy.rag.module.chat.model.IntentRecognitionResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ChatFlowTest {

    @Test
    void commonFlowKeepsOriginalQuery() {
        ChatFlow.FlowPreparation result = new CommonChatFlow()
                .prepare(" original ", List.of());
        assertNull(result.prompt().systemMessage());
        assertEquals(" original ", result.prompt().userMessage());
        assertNull(result.rewrittenQuery());
    }

    @Test
    void ragFlowReturnsControlledAnswerForEmptyRetrieval() {
        RagPipeline pipeline = mock(RagPipeline.class);
        when(pipeline.execute(any())).thenReturn(new RagResult(null,
                new RewrittenQuery("q", "rewritten"), List.of()));

        ChatFlow.FlowPreparation result = new RagChatFlow(pipeline)
                .prepare("q", List.of());

        assertEquals("rewritten", result.rewrittenQuery());
        assertEquals(RagChatFlow.NO_KNOWLEDGE_ANSWER, result.fixedAnswer());
    }

    @Test
    void mapsEveryKnowledgeIntentToItsRagAnswerMode() {
        Map<ChatIntent, RagAnswerMode> expected = Map.of(
                ChatIntent.PAPER_RETRIEVAL, RagAnswerMode.PAPER_RETRIEVAL,
                ChatIntent.PAPER_SUMMARY, RagAnswerMode.PAPER_SUMMARY,
                ChatIntent.PAPER_CONTENT_QA, RagAnswerMode.PAPER_CONTENT_QA,
                ChatIntent.METHOD_OR_CONCEPT, RagAnswerMode.METHOD_OR_CONCEPT,
                ChatIntent.EXPERIMENT_OR_RESULT, RagAnswerMode.EXPERIMENT_OR_RESULT,
                ChatIntent.COMPARISON_OR_CRITIQUE, RagAnswerMode.COMPARISON_OR_CRITIQUE,
                ChatIntent.ACADEMIC_PAPER_ASSISTANCE,
                RagAnswerMode.ACADEMIC_PAPER_ASSISTANCE);

        expected.forEach((intent, answerMode) -> {
            IntentRecognitionResult recognition = new IntentRecognitionResult(
                    true, intent, "reason",
                    new IntentKeyInformation(List.of("Paper"), List.of(), List.of(),
                            List.of("RAG"), List.of(), List.of(), List.of()), false);
            assertEquals(answerMode,
                    RagChatFlow.toRagIntent(recognition).answerMode());
            assertEquals(List.of("RAG"), RagChatFlow.toRagIntent(recognition)
                    .focusInformation().methodsOrModels());
        });
    }

    @Test
    void unknownIntentFallsBackToGenericMode() {
        assertEquals(RagAnswerMode.GENERIC,
                RagChatFlow.toRagIntent(IntentRecognitionResult.fallback("failed"))
                        .answerMode());
        assertEquals(RagAnswerMode.GENERIC,
                RagChatFlow.toRagIntent(null).answerMode());
    }
}
