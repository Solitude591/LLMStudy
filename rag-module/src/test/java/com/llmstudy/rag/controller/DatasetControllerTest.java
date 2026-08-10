package com.llmstudy.rag.controller;

import com.llmstudy.rag.auth.model.AccessContext;
import com.llmstudy.rag.auth.model.UserRole;
import com.llmstudy.rag.auth.service.CurrentUserProvider;
import com.llmstudy.rag.config.GlobalExceptionHandler;
import com.llmstudy.rag.dto.ApiResult;
import com.llmstudy.rag.dto.DatasetGenerateRequest;
import com.llmstudy.rag.dto.DatasetGenerateResponse;
import com.llmstudy.rag.module.dataset.DatasetGenerationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class DatasetControllerTest {

    private static final AccessContext ACCESS =
            new AccessContext("user-1", "org-a", UserRole.USER);

    private DatasetGenerationService service;
    private CurrentUserProvider currentUserProvider;
    private DatasetController controller;
    private MockMvc mvc;
    private JsonMapper jsonMapper;

    @BeforeEach
    void setUp() {
        service = mock(DatasetGenerationService.class);
        currentUserProvider = mock(CurrentUserProvider.class);
        when(currentUserProvider.requireAccessContext()).thenReturn(ACCESS);
        controller = new DatasetController(service, currentUserProvider);
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
        jsonMapper = JsonMapper.builder().build();
    }

    @Test
    void generateCapturesAccessContextAndDelegatesToService() {
        DatasetGenerateResponse payload = new DatasetGenerateResponse(
                "问题", "回答", List.of("chunk-a"));
        when(service.generate(eq("问题"), eq(ACCESS))).thenReturn(payload);

        ApiResult<DatasetGenerateResponse> result =
                controller.generate(new DatasetGenerateRequest("问题"));

        assertEquals(0, result.getCode());
        assertEquals("ok", result.getMessage());
        assertEquals(payload, result.getData());
        ArgumentCaptor<AccessContext> captor = ArgumentCaptor.forClass(AccessContext.class);
        verify(currentUserProvider).requireAccessContext();
        verify(service).generate(eq("问题"), captor.capture());
        assertEquals(ACCESS, captor.getValue());
    }

    @Test
    void nullRequestBodyReturns400() {
        assertThrows(IllegalArgumentException.class, () -> controller.generate(null));
    }

    @Test
    void blankQueryReturns400ApiResult() throws Exception {
        mvc.perform(post("/dataset/generate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"   \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    void successJsonContainsOnlyQueryResponseAndChunks() throws Exception {
        when(service.generate(eq("表 3 中哪个模型的 F1 最高？"), any()))
                .thenReturn(new DatasetGenerateResponse(
                        "表 3 中哪个模型的 F1 最高？",
                        "Hybrid RAG 的 F1 最高[1]。",
                        List.of("证据正文 A", "证据正文 B")));

        String body = mvc.perform(post("/dataset/generate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"表 3 中哪个模型的 F1 最高？\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.query").value("表 3 中哪个模型的 F1 最高？"))
                .andExpect(jsonPath("$.data.response").value("Hybrid RAG 的 F1 最高[1]。"))
                .andExpect(jsonPath("$.data.chunks[0]").value("证据正文 A"))
                .andExpect(jsonPath("$.data.chunks[1]").value("证据正文 B"))
                .andReturn()
                .getResponse()
                .getContentAsString();

        @SuppressWarnings("unchecked")
        Map<String, Object> root = jsonMapper.readValue(body, Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) root.get("data");
        assertEquals(3, data.size());
        assertFalse(body.contains("citation"));
        assertFalse(body.contains("sourceUrl"));
        assertFalse(body.contains("docId"));
        assertFalse(body.contains("chunkId"));
        assertFalse(body.contains("\"score\""));
        assertFalse(body.contains("rerankedScore"));
    }
}
