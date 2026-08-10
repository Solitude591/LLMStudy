package com.llmstudy.rag.module.knowledge.ingestion.chunk;

import com.llmstudy.rag.enums.ChunkType;
import com.llmstudy.rag.module.knowledge.model.KnowledgeChunk;
import com.llmstudy.rag.util.SnowflakeIdGenerator;

import java.util.ArrayList;
import java.util.List;

/**
 * 将一块连续正文输出为 standalone，或超限时的 parent + children。
 *
 * <p>{@link ChunkType} 只决定 {@code skipEmbedding}，不写入 MySQL metadata。</p>
 */
final class TextChunkEmitter {

    private final SnowflakeIdGenerator idGenerator;
    private final SemanticTextSplitter textSplitter;
    private final int chunkSize;

    TextChunkEmitter(SnowflakeIdGenerator idGenerator,
                     SemanticTextSplitter textSplitter,
                     int chunkSize) {
        this.idGenerator = idGenerator;
        this.textSplitter = textSplitter;
        this.chunkSize = chunkSize;
    }

    /**
     * 按字符上限切分正文。
     *
     * @param protectedRanges 与 {@code text} 同一 UTF-16 坐标系的受保护区间；
     *                        为空时走普通语义切分
     * @return 空输入返回空列表；超限时首条为 parent（skipEmbedding=true）
     */
    List<KnowledgeChunk> emitText(String text,
                                  String headerPath,
                                  Integer pageStart,
                                  Integer pageEnd,
                                  List<SemanticTextSplitter.IntRange> protectedRanges) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        // 未超限：直接 standalone，避免无意义的 parent/child 对。
        if (SemanticTextSplitter.codePointCount(text) <= chunkSize) {
            return List.of(standalone(text, headerPath, pageStart, pageEnd));
        }

        String parentChunkId = nextId();
        List<KnowledgeChunk> result = new ArrayList<>();
        // parent 保存完整原文供检索后回查；本身不做 embedding。
        result.add(new KnowledgeChunk(
                parentChunkId,
                text,
                ChunkMetadataFactory.create(null, headerPath, pageStart, pageEnd),
                ChunkType.PARENT.shouldSkipEmbedding()));

        List<String> children = protectedRanges == null || protectedRanges.isEmpty()
                ? textSplitter.split(text)
                : textSplitter.splitRespectingProtectedRanges(text, protectedRanges);
        for (String child : children) {
            if (child == null || child.isBlank()) {
                continue;
            }
            result.add(new KnowledgeChunk(
                    nextId(),
                    child,
                    ChunkMetadataFactory.create(parentChunkId, headerPath, pageStart, pageEnd),
                    ChunkType.CHILD.shouldSkipEmbedding()));
        }
        // 理论上非空超限正文至少产出一个 child；兜底保证版本仍可向量化。
        if (result.size() == 1) {
            result.add(new KnowledgeChunk(
                    nextId(),
                    text,
                    ChunkMetadataFactory.create(parentChunkId, headerPath, pageStart, pageEnd),
                    ChunkType.CHILD.shouldSkipEmbedding()));
        }
        return result;
    }

    /**
     * 生成可向量化的独立分片（图片、表格、短正文）。
     */
    KnowledgeChunk standalone(String text,
                              String headerPath,
                              Integer pageStart,
                              Integer pageEnd) {
        return new KnowledgeChunk(
                nextId(),
                text,
                ChunkMetadataFactory.create(null, headerPath, pageStart, pageEnd),
                ChunkType.STANDALONE.shouldSkipEmbedding());
    }

    private String nextId() {
        // chunk_id 列是 BIGINT；字符串传递避免前端 JS 精度丢失。
        return String.valueOf(idGenerator.nextId());
    }
}
