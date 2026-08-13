package com.llmstudy.rag.controller.dev;

import com.llmstudy.rag.dto.RetrievalDiagnoseRequest;
import com.llmstudy.rag.module.rag.RagPipeline;
import com.llmstudy.rag.module.rag.model.RagRequest;
import com.llmstudy.rag.module.rag.model.RetrievalDiagnoseResponse;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 检索诊断入口。
 *
 * <p>无需登录，Controller 只依赖 {@link RagPipeline}，
 * 不碰 retrieval/aggregation/rerank 内部类。</p>
 */
@RestController
@RequestMapping("/dev/rag/retrieval")
public class RetrievalDiagnoseController {

    private final RagPipeline ragPipeline;

    public RetrievalDiagnoseController(RagPipeline ragPipeline) {
        this.ragPipeline = ragPipeline;
    }

    /**
     * 执行改写、四路召回和排序，不调用回答模型。
     *
     * <p>{@code accessContext} 为空，与 {@code /dataset/generate} 一样检索全部已发布版本。</p>
     */
    @PostMapping("/diagnose")
    public RetrievalDiagnoseResponse diagnose(@RequestBody RetrievalDiagnoseRequest request) {
        if (request == null || request.query() == null || request.query().isBlank()) {
            throw new IllegalArgumentException("用户问题不能为空");
        }
        return ragPipeline.diagnose(
                new RagRequest(request.query(), request.conversationContext()),
                Boolean.TRUE.equals(request.includeText()));
    }
}
