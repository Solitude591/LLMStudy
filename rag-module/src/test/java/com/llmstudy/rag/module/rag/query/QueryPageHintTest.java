package com.llmstudy.rag.module.rag.query;

import com.llmstudy.rag.module.knowledge.model.SegmentMetadataKeys;
import com.llmstudy.rag.module.rag.model.RetrievalCandidate;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QueryPageHintTest {

    @Test
    void extractsChineseAndEnglishPageNumbers() {
        assertEquals(Set.of(2), QueryPageHint.pages("《U-Net》在第 2 页如何描述跳连？"));
        assertEquals(Set.of(4), QueryPageHint.pages("What does Table 1 on page 4 compare?"));
        assertTrue(QueryPageHint.pages("Compare U-Net and nnU-Net").isEmpty());
    }

    @Test
    void promotesOverlappingPagesWithoutReorderingTheRest() {
        RetrievalCandidate title = candidate("title", 1, 1);
        RetrievalCandidate table = candidate("table", 4, 4);
        RetrievalCandidate later = candidate("later", 9, 9);

        List<RetrievalCandidate> promoted = QueryPageHint.promote(
                List.of(title, table, later), Set.of(4));

        assertEquals(List.of("table", "title", "later"),
                promoted.stream().map(RetrievalCandidate::id).toList());
    }

    private static RetrievalCandidate candidate(String id, int start, int end) {
        return new RetrievalCandidate(id, "text-" + id, Map.of(
                SegmentMetadataKeys.PAGE_START, start,
                SegmentMetadataKeys.PAGE_END, end), 1.0, null);
    }
}
