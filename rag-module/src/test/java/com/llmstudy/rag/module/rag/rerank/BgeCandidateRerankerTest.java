package com.llmstudy.rag.module.rag.rerank;

import com.llmstudy.rag.config.RerankerProperties;
import com.llmstudy.rag.module.knowledge.model.SegmentMetadataKeys;
import com.llmstudy.rag.module.rag.model.RetrievalCandidate;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.output.Response;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BgeCandidateRerankerTest {

    @Test
    void sortsCandidatesByBgeScoreAndKeepsRrfScore() {
        RerankerProperties properties = new RerankerProperties();
        properties.setEnabled(true);
        BgeScoringModel model = mock(BgeScoringModel.class);
        when(model.scoreAll(anyList(), eq("question")))
                .thenReturn(Response.from(List.of(0.1, 0.9)));
        RetrievalCandidate first = new RetrievalCandidate("a", "text-a", Map.of(), 1.0, null)
                .withRrfScore(0.2);
        RetrievalCandidate second = new RetrievalCandidate("b", "text-b", Map.of(), 1.0, null)
                .withRrfScore(0.1);

        RerankResult result = new BgeCandidateReranker(properties, model)
                .rerank("question", List.of(first, second));

        assertTrue(result.used());
        assertNull(result.reason());
        assertEquals(List.of("b", "a"), result.candidates().stream()
                .map(RetrievalCandidate::id).toList());
        assertEquals(0.9, result.candidates().getFirst().bgeScore());
        assertEquals(0.1, result.candidates().getFirst().rrfScore());
    }

    @Test
    void sendsHeaderPathWithChildText() {
        RerankerProperties properties = new RerankerProperties();
        properties.setEnabled(true);
        BgeScoringModel model = mock(BgeScoringModel.class);
        when(model.scoreAll(anyList(), eq("query")))
                .thenReturn(Response.from(List.of(0.5, 0.4)));
        List<RetrievalCandidate> input = List.of(
                new RetrievalCandidate("a", "child-a",
                        Map.of(SegmentMetadataKeys.HEADER_PATH, "实验 > 表 3"), 1.0, null),
                new RetrievalCandidate("b", "child-b", Map.of(), 1.0, null));

        new BgeCandidateReranker(properties, model).rerank("query", input);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<TextSegment>> captor = ArgumentCaptor.forClass(List.class);
        verify(model).scoreAll(captor.capture(), eq("query"));
        assertEquals("实验 > 表 3\nchild-a", captor.getValue().getFirst().text());
        assertEquals("child-b", captor.getValue().get(1).text());
    }

    @Test
    void inferenceFailureKeepsOriginalOrderWithReason() {
        RerankerProperties properties = new RerankerProperties();
        properties.setEnabled(true);
        BgeScoringModel model = mock(BgeScoringModel.class);
        when(model.scoreAll(anyList(), eq("question")))
                .thenThrow(new IllegalStateException("model unavailable"));
        List<RetrievalCandidate> input = List.of(candidate("a"), candidate("b"));

        RerankResult result = new BgeCandidateReranker(properties, model)
                .rerank("question", input);
        assertFalse(result.used());
        assertEquals("inference-error", result.reason());
        assertEquals(input, result.candidates());
        assertNull(result.candidates().getFirst().bgeScore());
    }

    @Test
    void invalidScoresKeepOriginalOrderWithReason() {
        RerankerProperties properties = new RerankerProperties();
        properties.setEnabled(true);
        BgeScoringModel model = mock(BgeScoringModel.class);
        when(model.scoreAll(anyList(), eq("question")))
                .thenReturn(Response.from(List.of(Double.NaN, 0.9)));
        List<RetrievalCandidate> input = List.of(candidate("a"), candidate("b"));

        RerankResult result = new BgeCandidateReranker(properties, model)
                .rerank("question", input);
        assertEquals("invalid-score", result.reason());
        assertEquals(input, result.candidates());
    }

    @Test
    void disabledReportsStableReason() {
        RerankerProperties properties = new RerankerProperties();
        properties.setEnabled(false);
        List<RetrievalCandidate> input = List.of(candidate("a"), candidate("b"));

        RerankResult result = new BgeCandidateReranker(properties, mock(BgeScoringModel.class))
                .rerank("question", input);
        assertEquals("disabled", result.reason());
        assertFalse(result.used());
    }

    private static RetrievalCandidate candidate(String id) {
        return new RetrievalCandidate(id, "text-" + id, Map.of(), 1.0, null);
    }
}
