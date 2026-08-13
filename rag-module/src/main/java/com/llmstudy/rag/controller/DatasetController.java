package com.llmstudy.rag.controller;

import com.llmstudy.rag.dto.ApiResult;
import com.llmstudy.rag.dto.DatasetGenerateRequest;
import com.llmstudy.rag.dto.DatasetGenerateResponse;
import com.llmstudy.rag.module.dataset.DatasetGenerationService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * RAGAS 评估数据集生成接口。
 *
 * <p>已加入 {@code AuthConfig.PUBLIC_PATHS}，无需登录。</p>
 */
@RestController
@RequestMapping("/dataset")
public class DatasetController {

    private final DatasetGenerationService datasetGenerationService;

    public DatasetController(DatasetGenerationService datasetGenerationService) {
        this.datasetGenerationService = datasetGenerationService;
    }

    /**
     * POST /dataset/generate
     *
     * <p>执行现有 RAG 检索链路并生成回答，返回原始问题、回答与最终 chunk 正文。
     * 检索不按用户权限过滤，使用全部已发布版本。</p>
     */
    @PostMapping("/generate")
    public ApiResult<DatasetGenerateResponse> generate(
            @RequestBody(required = false) DatasetGenerateRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("请求体不能为空");
        }
        if (request.query() == null || request.query().isBlank()) {
            throw new IllegalArgumentException("用户问题不能为空");
        }
        return ApiResult.ok(datasetGenerationService.generate(request.query()));
    }
}
