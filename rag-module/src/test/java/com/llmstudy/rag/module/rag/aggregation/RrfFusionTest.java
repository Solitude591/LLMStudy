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
                List.of(List.of(a, b), List.of(b, c)), 10, 60);

        assertEquals(List.of("b", "a", "c"),
                result.stream().map(RetrievalCandidate::id).toList());
        assertTrue(result.getFirst().rrfScore() > result.get(1).rrfScore());
        assertEquals(1.0, result.getFirst().rawScore());
    }

    @Test
    void fourWayHitsAccumulateAndTieBreaksByHitCountThenBestRankThenId() {
        // d 只在一路 rank1；e 两路都是 rank2。1/(60+1) < 2/(60+2)，e 应更高。
        List<RetrievalCandidate> result = new RrfFusion().fuse(List.of(
                List.of(candidate("d"), candidate("e")),
                List.of(candidate("f"), candidate("e")),
                List.of(),
                List.of()), 10, 60);

        assertEquals("e", result.getFirst().id());
    }

    private static RetrievalCandidate candidate(String id) {
        return new RetrievalCandidate(id, "text-" + id, Map.of(), 1.0, null);
    }
}
