package com.llmstudy.rag.module.rag.retrieval;

import com.llmstudy.rag.entity.KnowledgeSegment;
import com.llmstudy.rag.module.knowledge.model.SegmentMetadataKeys;
import com.llmstudy.rag.module.rag.model.RetrievalCandidate;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ParentChunkExpanderTest {

    @Test
    void expandsChildAndKeepsCandidateSourceMetadata() {
        ParentChunkResolver resolver = mock(ParentChunkResolver.class);
        KnowledgeSegment parent = new KnowledgeSegment();
        parent.setChunkId("p-1");
        parent.setText("full parent text");
        parent.setDocId("doc-from-parent");
        parent.setMetadata("""
                {"header_path":"方法 > 模型","page_start":3,"page_end":4}
                """);
        when(resolver.resolve(eq("p-1"), anyMap())).thenReturn(parent);

        RetrievalCandidate child = new RetrievalCandidate(
                "c-1", "child text",
                Map.of(SegmentMetadataKeys.PARENT_CHUNK_ID, "p-1",
                        SegmentMetadataKeys.DOC_ID, "doc-1",
                        SegmentMetadataKeys.VERSION_ID, "v-1",
                        SegmentMetadataKeys.SOURCE_URL, "https://md",
                        SegmentMetadataKeys.LANGUAGE, "EN",
                        SegmentMetadataKeys.PAGE_START, 3,
                        SegmentMetadataKeys.PAGE_END, 3),
                0.8, 0.9);

        List<RetrievalCandidate> expanded = new ParentChunkExpander(
                resolver, JsonMapper.builder().build())
                .expand(List.of(child));

        assertEquals(1, expanded.size());
        assertEquals("p-1", expanded.getFirst().id());
        assertEquals("full parent text", expanded.getFirst().text());
        assertEquals("doc-1", expanded.getFirst().metadata().get(SegmentMetadataKeys.DOC_ID));
        assertEquals("v-1", expanded.getFirst().metadata().get(SegmentMetadataKeys.VERSION_ID));
        assertEquals("https://md", expanded.getFirst().metadata().get(SegmentMetadataKeys.SOURCE_URL));
        assertEquals("EN", expanded.getFirst().metadata().get(SegmentMetadataKeys.LANGUAGE));
        assertEquals("方法 > 模型",
                expanded.getFirst().metadata().get(SegmentMetadataKeys.HEADER_PATH));
        assertEquals(3, expanded.getFirst().metadata().get(SegmentMetadataKeys.PAGE_START));
        assertEquals(4, expanded.getFirst().metadata().get(SegmentMetadataKeys.PAGE_END));
    }

    @Test
    void deduplicatesChildrenOfSameParent() {
        ParentChunkResolver resolver = mock(ParentChunkResolver.class);
        KnowledgeSegment parent = new KnowledgeSegment();
        parent.setChunkId("p-1");
        parent.setText("parent");
        parent.setMetadata("{}");
        when(resolver.resolve(eq("p-1"), anyMap())).thenReturn(parent);

        RetrievalCandidate first = new RetrievalCandidate(
                "c-1", "a", Map.of(SegmentMetadataKeys.PARENT_CHUNK_ID, "p-1"), 0.9, null);
        RetrievalCandidate second = new RetrievalCandidate(
                "c-2", "b", Map.of(SegmentMetadataKeys.PARENT_CHUNK_ID, "p-1"), 0.8, null);

        List<RetrievalCandidate> expanded = new ParentChunkExpander(
                resolver, JsonMapper.builder().build())
                .expand(List.of(first, second));

        assertEquals(1, expanded.size());
        assertEquals("p-1", expanded.getFirst().id());
    }

    @Test
    void keepsChildWhenParentMissing() {
        ParentChunkResolver resolver = mock(ParentChunkResolver.class);
        when(resolver.resolve(eq("missing"), anyMap())).thenReturn(null);
        RetrievalCandidate child = new RetrievalCandidate(
                "c-1", "child",
                Map.of(SegmentMetadataKeys.PARENT_CHUNK_ID, "missing"), 0.5, null);

        List<RetrievalCandidate> expanded = new ParentChunkExpander(
                resolver, JsonMapper.builder().build())
                .expand(List.of(child));

        assertEquals("c-1", expanded.getFirst().id());
        assertNull(new HashMap<>(expanded.getFirst().metadata())
                .get(SegmentMetadataKeys.HEADER_PATH));
    }
}
