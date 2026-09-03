package com.llmstudy.rag.module.rag.retrieval;

import com.llmstudy.rag.config.RetrievalProperties;
import com.llmstudy.rag.entity.KnowledgeSegment;
import com.llmstudy.rag.mapper.KnowledgeSegmentMapper;
import com.llmstudy.rag.module.knowledge.model.SegmentMetadataKeys;
import com.llmstudy.rag.module.rag.model.RetrievalCandidate;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SectionChunkAssemblerTest {

    private static final String VERSION = "v-1";
    private static final String SECTION = "论文 > 1 Introduction";

    @Test
    void mergesNeighboursOfTheSameSectionAndUnionsPages() {
        KnowledgeSegmentMapper mapper = sectionOf(
                segment("a", "第一段", 1, 1),
                segment("b", "第二段", 2, 2),
                segment("c", "第三段", 2, 3));

        RetrievalCandidate merged = assembler(mapper, 10_000, 4)
                .assemble(candidate("b", "第二段", 2, 2), SectionChunkAssembler.newCache());

        // 输出 ID 收敛到窗口首片，同章节的不同命中因此会在 Top-N 去重时合并。
        assertEquals("a", merged.id());
        assertEquals("第一段\n\n第二段\n\n第三段", merged.text());
        assertEquals(1, merged.metadata().get(SegmentMetadataKeys.PAGE_START));
        assertEquals(3, merged.metadata().get(SegmentMetadataKeys.PAGE_END));
    }

    @Test
    void characterBudgetStopsTheWindowFromSwallowingWholeSection() {
        KnowledgeSegmentMapper mapper = sectionOf(
                segment("a", "x".repeat(50), 1, 1),
                segment("b", "y".repeat(50), 2, 2),
                segment("c", "z".repeat(50), 3, 3));

        // 50 + 2 + 50 = 102 放得下一片邻居，再加一片就是 154，超过预算。
        RetrievalCandidate merged = assembler(mapper, 120, 8)
                .assemble(candidate("b", "y".repeat(50), 2, 2), SectionChunkAssembler.newCache());

        assertEquals("y".repeat(50) + "\n\n" + "z".repeat(50), merged.text());
        assertEquals(2, merged.metadata().get(SegmentMetadataKeys.PAGE_START));
        assertEquals(3, merged.metadata().get(SegmentMetadataKeys.PAGE_END));
    }

    @Test
    void singlePartSectionIsReturnedUnchanged() {
        KnowledgeSegmentMapper mapper = sectionOf(segment("a", "只有一段", 1, 1));
        RetrievalCandidate anchor = candidate("a", "只有一段", 1, 1);

        assertSame(anchor, assembler(mapper, 10_000, 4)
                .assemble(anchor, SectionChunkAssembler.newCache()));
    }

    @Test
    void databaseFailureKeepsTheOriginalCandidate() {
        KnowledgeSegmentMapper mapper = mock(KnowledgeSegmentMapper.class);
        when(mapper.findSectionTopLevel(eq(VERSION), eq(SECTION)))
                .thenThrow(new IllegalStateException("db down"));
        RetrievalCandidate anchor = candidate("b", "第二段", 2, 2);

        assertSame(anchor, assembler(mapper, 10_000, 4)
                .assemble(anchor, SectionChunkAssembler.newCache()));
    }

    @Test
    void disabledMergeSkipsTheDatabaseEntirely() {
        KnowledgeSegmentMapper mapper = mock(KnowledgeSegmentMapper.class);
        RetrievalProperties properties = new RetrievalProperties();
        properties.setSectionMergeEnabled(false);
        RetrievalCandidate anchor = candidate("b", "第二段", 2, 2);

        assertSame(anchor, new SectionChunkAssembler(mapper, JsonMapper.builder().build(),
                properties).assemble(anchor, SectionChunkAssembler.newCache()));
        verify(mapper, times(0)).findSectionTopLevel(any(), any());
    }

    @Test
    void sectionIsFetchedOncePerRequest() {
        KnowledgeSegmentMapper mapper = sectionOf(
                segment("a", "第一段", 1, 1),
                segment("b", "第二段", 2, 2));
        SectionChunkAssembler assembler = assembler(mapper, 10_000, 4);
        Map<String, List<KnowledgeSegment>> cache = SectionChunkAssembler.newCache();

        assembler.assemble(candidate("a", "第一段", 1, 1), cache);
        assembler.assemble(candidate("b", "第二段", 2, 2), cache);

        verify(mapper, times(1)).findSectionTopLevel(VERSION, SECTION);
    }

    @Test
    void candidateOutsideTheSectionListIsLeftAlone() {
        KnowledgeSegmentMapper mapper = sectionOf(
                segment("a", "第一段", 1, 1),
                segment("b", "第二段", 2, 2));
        RetrievalCandidate anchor = candidate("child-of-a", "子片正文", 1, 1);

        RetrievalCandidate result = assembler(mapper, 10_000, 4)
                .assemble(anchor, SectionChunkAssembler.newCache());

        assertSame(anchor, result);
        assertTrue(result.text().contains("子片正文"));
    }

    private static SectionChunkAssembler assembler(KnowledgeSegmentMapper mapper,
                                                   int maxChars, int maxChunks) {
        RetrievalProperties properties = new RetrievalProperties();
        properties.setSectionMergeEnabled(true);
        properties.setSectionMergeMaxChars(maxChars);
        properties.setSectionMergeMaxChunks(maxChunks);
        return new SectionChunkAssembler(mapper, JsonMapper.builder().build(), properties);
    }

    private static KnowledgeSegmentMapper sectionOf(KnowledgeSegment... segments) {
        KnowledgeSegmentMapper mapper = mock(KnowledgeSegmentMapper.class);
        when(mapper.findSectionTopLevel(eq(VERSION), eq(SECTION))).thenReturn(List.of(segments));
        return mapper;
    }

    private static KnowledgeSegment segment(String chunkId, String text,
                                            int pageStart, int pageEnd) {
        KnowledgeSegment segment = new KnowledgeSegment();
        segment.setChunkId(chunkId);
        segment.setText(text);
        segment.setVersionId(VERSION);
        segment.setMetadata("{\"header_path\":\"" + SECTION + "\",\"page_start\":" + pageStart
                + ",\"page_end\":" + pageEnd + "}");
        return segment;
    }

    private static RetrievalCandidate candidate(String id, String text,
                                                int pageStart, int pageEnd) {
        return new RetrievalCandidate(id, text,
                Map.of(SegmentMetadataKeys.VERSION_ID, VERSION,
                        SegmentMetadataKeys.HEADER_PATH, SECTION,
                        SegmentMetadataKeys.PAGE_START, pageStart,
                        SegmentMetadataKeys.PAGE_END, pageEnd),
                0.8, 0.9);
    }
}
