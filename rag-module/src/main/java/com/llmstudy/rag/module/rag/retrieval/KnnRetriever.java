package com.llmstudy.rag.module.rag.retrieval;

import com.llmstudy.rag.module.knowledge.model.SegmentMetadataKeys;
import com.llmstudy.rag.module.rag.model.RetrievalCandidate;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.elasticsearch.ElasticsearchEmbeddingStore;
import dev.langchain4j.store.embedding.filter.MetadataFilterBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 向量近邻检索适配器。
 *
 * <p>只返回 child/standalone 命中；parent 展开统一延迟到融合重排之后。</p>
 */
@Component
public class KnnRetriever {

    private static final Logger log = LoggerFactory.getLogger(KnnRetriever.class);
    private static final int RESULTS_PER_CHANNEL = 5;

    private final OpenAiEmbeddingModel embeddingModel;
    private final ElasticsearchEmbeddingStore embeddingStore;

    public KnnRetriever(OpenAiEmbeddingModel embeddingModel,
                        ElasticsearchEmbeddingStore embeddingStore) {
        this.embeddingModel = embeddingModel;
        this.embeddingStore = embeddingStore;
    }

    /**
     * 对改写问题做向量近邻检索。
     *
     * <p>只返回 ES 中的 standalone/child 命中；parent 展开延迟到
     * {@link ParentChunkExpander}，以便 BM25/KNN 在融合前使用同一粒度 id。</p>
     */
    public List<RetrievalCandidate> retrieve(String rewrittenQuestion) {
        return search(rewrittenQuestion, null);
    }

    /** 仅在当前已发布版本集合内检索；空集合由上层短路，此处再防一层。 */
    public List<RetrievalCandidate> retrieve(String rewrittenQuestion,
                                             List<String> currentVersionIds) {
        if (currentVersionIds == null || currentVersionIds.isEmpty()) {
            return List.of();
        }
        return search(rewrittenQuestion, currentVersionIds);
    }

    private List<RetrievalCandidate> search(String rewrittenQuestion,
                                            List<String> currentVersionIds) {
        float[] vector = embeddingModel.embed(rewrittenQuestion);
        EmbeddingSearchRequest.EmbeddingSearchRequestBuilder request =
                EmbeddingSearchRequest.builder()
                        .queryEmbedding(Embedding.from(vector))
                        .maxResults(RESULTS_PER_CHANNEL)
                        .minScore(0.0);
        if (currentVersionIds != null) {
            request.filter(MetadataFilterBuilder
                    .metadataKey(SegmentMetadataKeys.VERSION_ID)
                    .isIn(currentVersionIds));
        }
        List<EmbeddingMatch<TextSegment>> matches =
                embeddingStore.search(request.build()).matches();
        List<RetrievalCandidate> candidates = new ArrayList<>();
        for (EmbeddingMatch<TextSegment> match : matches) {
            TextSegment segment = match.embedded();
            if (match.embeddingId() == null || match.embeddingId().isBlank()
                    || segment == null || segment.text() == null || segment.text().isBlank()) {
                log.warn("KNN 命中缺少 embeddingId 或文本，已跳过");
                continue;
            }
            Map<String, Object> metadata = new LinkedHashMap<>(segment.metadata().toMap());
            candidates.add(new RetrievalCandidate(
                    match.embeddingId(),
                    segment.text(),
                    metadata,
                    match.score() == null ? 0.0 : match.score(),
                    null));
        }
        return candidates;
    }
}
