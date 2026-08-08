package com.llmstudy.rag.module.rag.retrieval;

import com.llmstudy.rag.auth.model.AccessContext;
import com.llmstudy.rag.auth.model.UserRole;
import com.llmstudy.rag.mapper.KnowledgeDocumentMapper;
import com.llmstudy.rag.module.rag.model.RetrievalCandidate;
import com.llmstudy.rag.module.rag.model.RewrittenQuery;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;

class HybridRetrieverTest {

    @Test
    void singleChannelFailureUsesTheOtherChannel() throws Exception {
        Bm25Retriever bm25 = mock(Bm25Retriever.class);
        KnnRetriever knn = mock(KnnRetriever.class);
        when(bm25.retrieve("original")).thenThrow(new IOException("down"));
        when(knn.retrieve("rewritten")).thenReturn(List.of(candidate("knn")));

        HybridRetriever.RetrievalResult result = new HybridRetriever(bm25, knn)
                .retrieve(new RewrittenQuery("original", "rewritten"));

        assertTrue(result.degraded());
        assertEquals(List.of("knn"), result.knn().stream()
                .map(RetrievalCandidate::id).toList());
    }

    @Test
    void bothChannelFailuresRaiseRetrievalFailure() throws Exception {
        Bm25Retriever bm25 = mock(Bm25Retriever.class);
        KnnRetriever knn = mock(KnnRetriever.class);
        when(bm25.retrieve("original")).thenThrow(new IOException("bm25"));
        when(knn.retrieve("rewritten")).thenThrow(new IllegalStateException("knn"));

        assertThrows(IllegalStateException.class, () -> new HybridRetriever(bm25, knn)
                .retrieve(new RewrittenQuery("original", "rewritten")));
    }

    @Test
    void authenticatedAccessContextFiltersBothChannelsWithSameVersionSnapshot()
            throws Exception {
        Bm25Retriever bm25 = mock(Bm25Retriever.class);
        KnnRetriever knn = mock(KnnRetriever.class);
        KnowledgeDocumentMapper documents = mock(KnowledgeDocumentMapper.class);
        AccessContext actor = new AccessContext("alice", "org-a", UserRole.USER);
        List<String> versions = List.of("v-private", "v-org", "v-public");
        when(documents.findAccessibleCurrentVersionIds("alice", "org-a", false))
                .thenReturn(versions);
        when(bm25.retrieve("original", versions)).thenReturn(List.of());
        when(knn.retrieve("rewritten", versions)).thenReturn(List.of());

        new HybridRetriever(bm25, knn, documents)
                .retrieve(new RewrittenQuery("original", "rewritten"), actor);

        verify(bm25).retrieve("original", versions);
        verify(knn).retrieve("rewritten", versions);
    }

    private static RetrievalCandidate candidate(String id) {
        return new RetrievalCandidate(id, "text", Map.of(), 1.0, null);
    }
}
