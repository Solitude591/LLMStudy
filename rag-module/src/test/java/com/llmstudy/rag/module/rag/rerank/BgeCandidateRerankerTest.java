package com.llmstudy.rag.module.rag.rerank;

import com.llmstudy.rag.config.RerankerProperties;
import com.llmstudy.rag.module.knowledge.model.SegmentMetadataKeys;
import com.llmstudy.rag.module.rag.model.RetrievalCandidate;
import com.llmstudy.rag.module.rag.model.RetrievalQueryPlan;
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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BgeCandidateRerankerTest {

    @Test
    void sortsCandidatesByBgeScoreAndKeepsRrfScore() {
        BgeScoringModel model = mock(BgeScoringModel.class);
        when(model.scorePairs(anyList(), anyList()))
                .thenReturn(Response.from(List.of(0.8, 0.9)));
        RetrievalCandidate first = new RetrievalCandidate("a", "text-a", Map.of(), 1.0, null)
                .withRrfScore(0.2);
        RetrievalCandidate second = new RetrievalCandidate("b", "text-b", Map.of(), 1.0, null)
                .withRrfScore(0.1);

        RerankResult result = new BgeCandidateReranker(enabled(), model)
                .rerank(plan("question"), List.of(first, second));

        assertTrue(result.used());
        assertNull(result.reason());
        assertEquals(List.of("b", "a"), result.candidates().stream()
                .map(RetrievalCandidate::id).toList());
        assertEquals(0.9, result.candidates().getFirst().bgeScore());
        assertEquals(0.1, result.candidates().getFirst().rrfScore());
    }

    @Test
    void filtersScoresBelowConfiguredMinimumAndKeepsBoundary() {
        BgeScoringModel model = mock(BgeScoringModel.class);
        when(model.scorePairs(anyList(), anyList()))
                .thenReturn(Response.from(List.of(0.59, 0.6, 0.91)));

        RerankResult result = new BgeCandidateReranker(enabled(), model)
                .rerank(plan("question"), List.of(
                        candidate("low"), candidate("boundary"), candidate("high")));

        assertTrue(result.used());
        assertEquals(List.of("high", "boundary"), result.candidates().stream()
                .map(RetrievalCandidate::id).toList());
    }

    @Test
    void allCandidatesBelowMinimumReturnsSuccessfulEmptyResult() {
        BgeScoringModel model = mock(BgeScoringModel.class);
        when(model.scorePairs(anyList(), anyList()))
                .thenReturn(Response.from(List.of(0.1)));

        RerankResult result = new BgeCandidateReranker(enabled(), model)
                .rerank(plan("question"), List.of(candidate("noise")));

        assertTrue(result.used());
        assertTrue(result.candidates().isEmpty());
        assertNull(result.reason());
    }

    @Test
    void usesLanguageSpecificQueriesAndFullHeaderPath() {
        BgeScoringModel model = mock(BgeScoringModel.class);
        when(model.scorePairs(anyList(), anyList()))
                .thenReturn(Response.from(List.of(0.9, 0.8, 0.7)));
        RetrievalQueryPlan plan = new RetrievalQueryPlan("q", "中文问题", "English question");
        List<RetrievalCandidate> input = List.of(
                candidate("zh", "ZH", "论文 > 3.3 Comparison", "38.96M 0.94M"),
                candidate("en", "EN", "Paper > Results", "params"),
                candidate("unk", "UNKNOWN", "A > B > C", "mixed"));

        new BgeCandidateReranker(enabled(), model).rerank(plan, input);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> queryCaptor = ArgumentCaptor.forClass(List.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<TextSegment>> docCaptor = ArgumentCaptor.forClass(List.class);
        verify(model).scorePairs(queryCaptor.capture(), docCaptor.capture());
        assertEquals("中文问题", queryCaptor.getValue().get(0));
        assertEquals("English question", queryCaptor.getValue().get(1));
        assertTrue(queryCaptor.getValue().get(2).contains("中文查询:"));
        assertTrue(queryCaptor.getValue().get(2).contains("English query:"));
        // 完整路径带上论文名和上级章节，跨论文题里两篇的「Results」才区分得开。
        assertEquals("论文 > 3.3 Comparison\n38.96M 0.94M", docCaptor.getValue().get(0).text());
        assertEquals("Paper > Results\nparams", docCaptor.getValue().get(1).text());
        assertEquals("A > B > C\nmixed", docCaptor.getValue().get(2).text());
    }

    @Test
    void focusedCandidateCanOverrideLanguageSpecificRerankQuery() {
        BgeScoringModel model = mock(BgeScoringModel.class);
        when(model.scorePairs(anyList(), anyList()))
                .thenReturn(Response.from(List.of(0.9)));
        RetrievalCandidate focused = new RetrievalCandidate(
                "focused", "memory evidence", Map.of(
                        SegmentMetadataKeys.LANGUAGE, "EN",
                        SegmentMetadataKeys.RERANK_QUERY_EN, "GPU memory limitation"),
                1.0, null);

        new BgeCandidateReranker(enabled(), model).rerank(
                new RetrievalQueryPlan("q", "完整中文问题", "full English question"),
                List.of(focused));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> queryCaptor = ArgumentCaptor.forClass(List.class);
        verify(model).scorePairs(queryCaptor.capture(), anyList());
        assertEquals(List.of("GPU memory limitation"), queryCaptor.getValue());
    }

    @Test
    void inferenceFailureKeepsOriginalOrderWithReason() {
        BgeScoringModel model = mock(BgeScoringModel.class);
        when(model.scorePairs(anyList(), anyList()))
                .thenThrow(new IllegalStateException("model unavailable"));
        List<RetrievalCandidate> input = List.of(candidate("a"), candidate("b"));

        RerankResult result = new BgeCandidateReranker(enabled(), model)
                .rerank(plan("question"), input);
        assertFalse(result.used());
        assertEquals("inference-error", result.reason());
        assertEquals(input, result.candidates());
        assertNull(result.candidates().getFirst().bgeScore());
    }

    @Test
    void invalidScoresKeepOriginalOrderWithReason() {
        BgeScoringModel model = mock(BgeScoringModel.class);
        when(model.scorePairs(anyList(), anyList()))
                .thenReturn(Response.from(List.of(Double.NaN, 0.9)));
        List<RetrievalCandidate> input = List.of(candidate("a"), candidate("b"));

        RerankResult result = new BgeCandidateReranker(enabled(), model)
                .rerank(plan("question"), input);
        assertEquals("invalid-score", result.reason());
        assertEquals(input, result.candidates());
    }

    @Test
    void disabledReportsStableReason() {
        RerankerProperties properties = new RerankerProperties();
        properties.setEnabled(false);
        List<RetrievalCandidate> input = List.of(candidate("a"), candidate("b"));

        RerankResult result = new BgeCandidateReranker(properties, mock(BgeScoringModel.class))
                .rerank(plan("question"), input);
        assertEquals("disabled", result.reason());
        assertFalse(result.used());
    }

    private static RerankerProperties enabled() {
        RerankerProperties properties = new RerankerProperties();
        properties.setEnabled(true);
        return properties;
    }

    private static RetrievalQueryPlan plan(String query) {
        return new RetrievalQueryPlan(query, query, query);
    }

    private static RetrievalCandidate candidate(String id) {
        return new RetrievalCandidate(id, "text-" + id, Map.of(), 1.0, null);
    }

    private static RetrievalCandidate candidate(String id, String language,
                                                String headerPath, String text) {
        return new RetrievalCandidate(id, text, Map.of(
                SegmentMetadataKeys.LANGUAGE, language,
                SegmentMetadataKeys.HEADER_PATH, headerPath), 1.0, null);
    }
}
