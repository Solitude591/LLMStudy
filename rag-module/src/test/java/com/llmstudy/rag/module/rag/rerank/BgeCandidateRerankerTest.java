package com.llmstudy.rag.module.rag.rerank;

import com.llmstudy.rag.config.RerankerProperties;
import com.llmstudy.rag.module.rag.model.RetrievalCandidate;
import dev.langchain4j.model.output.Response;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BgeCandidateRerankerTest {

    @Test
    void sortsCandidatesByBgeScore() {
        RerankerProperties properties = new RerankerProperties();
        properties.setEnabled(true);
        BgeScoringModel model = mock(BgeScoringModel.class);
        when(model.scoreAll(anyList(), eq("question")))
                .thenReturn(Response.from(List.of(0.1, 0.9)));
        List<RetrievalCandidate> input = List.of(candidate("a"), candidate("b"));

        List<RetrievalCandidate> result = new BgeCandidateReranker(properties, model)
                .rerank("question", input);

        assertEquals(List.of("b", "a"), result.stream()
                .map(RetrievalCandidate::id).toList());
        assertEquals(0.9, result.getFirst().rerankedScore());
    }

    @Test
    void inferenceFailureKeepsOriginalOrder() {
        RerankerProperties properties = new RerankerProperties();
        properties.setEnabled(true);
        BgeScoringModel model = mock(BgeScoringModel.class);
        when(model.scoreAll(anyList(), eq("question")))
                .thenThrow(new IllegalStateException("model unavailable"));
        List<RetrievalCandidate> input = List.of(candidate("a"), candidate("b"));

        assertEquals(input, new BgeCandidateReranker(properties, model)
                .rerank("question", input));
    }

    @Test
    void invalidScoresKeepOriginalOrder() {
        RerankerProperties properties = new RerankerProperties();
        properties.setEnabled(true);
        BgeScoringModel model = mock(BgeScoringModel.class);
        when(model.scoreAll(anyList(), eq("question")))
                .thenReturn(Response.from(List.of(Double.NaN, 0.9)));
        List<RetrievalCandidate> input = List.of(candidate("a"), candidate("b"));

        assertEquals(input, new BgeCandidateReranker(properties, model)
                .rerank("question", input));
    }

    private static RetrievalCandidate candidate(String id) {
        return new RetrievalCandidate(id, "text-" + id, Map.of(), 1.0, null);
    }
}
