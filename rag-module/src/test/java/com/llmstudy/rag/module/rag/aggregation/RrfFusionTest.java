package com.llmstudy.rag.module.rag.aggregation;

import com.llmstudy.rag.module.rag.model.RetrievalCandidate;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RrfFusionTest {

    @Test
    void mergesDuplicateCandidatesAndKeepsStableRanking() {
        RetrievalCandidate a = candidate("a");
        RetrievalCandidate b = candidate("b");
        RetrievalCandidate c = candidate("c");

        List<RetrievalCandidate> result = new RrfFusion().fuse(
                List.of(a, b), List.of(b, c), 10);

        assertEquals(List.of("b", "a", "c"),
                result.stream().map(RetrievalCandidate::id).toList());
        assertTrue(result.getFirst().score() > result.get(1).score());
    }

    private static RetrievalCandidate candidate(String id) {
        return new RetrievalCandidate(id, "text-" + id, Map.of(), 1.0, null);
    }
}
