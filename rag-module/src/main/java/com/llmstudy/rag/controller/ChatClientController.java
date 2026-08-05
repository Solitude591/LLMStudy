package com.llmstudy.rag.controller;

import com.llmstudy.rag.config.ChatProperties;
import com.llmstudy.rag.dto.ChatConversationResponse;
import com.llmstudy.rag.dto.ChatRequest;
import com.llmstudy.rag.dto.ChatResponse;
import com.llmstudy.rag.dto.ChatStreamResponse;
import com.llmstudy.rag.entity.ChatConversation;
import com.llmstudy.rag.module.chat.ChatOrchestrator;
import com.llmstudy.rag.module.chat.conversation.ConversationService;
import com.llmstudy.rag.module.chat.model.ChatCommand;
import com.llmstudy.rag.module.chat.model.ChatStreamEvent;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

/** HTTP adapter for chat; business routing lives in module.chat. */
@RestController
@RequestMapping("/chat/client")
public class ChatClientController {

    private final ChatOrchestrator orchestrator;
    private final ConversationService conversationService;
    private final ChatProperties properties;

    public ChatClientController(ChatOrchestrator orchestrator,
                                ConversationService conversationService,
                                ChatProperties properties) {
        this.orchestrator = orchestrator;
        this.conversationService = conversationService;
        this.properties = properties;
    }

    @PostMapping("/ask")
    public ChatResponse ask(@RequestBody ChatRequest request) {
        ChatOrchestrator.ChatAnswer answer = orchestrator.ask(command(request));
        return new ChatResponse(answer.conversationId(), answer.conversationTitle(),
                answer.userMessageId(), answer.assistantMessageId(), answer.content(),
                answer.tokenCount(), answer.modelName());
    }

    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ChatStreamResponse> stream(@RequestBody ChatRequest request) {
        return orchestrator.stream(command(request)).map(ChatClientController::toResponse);
    }

    @GetMapping("/conversations/{conversationId}")
    public ChatConversationResponse getConversation(@PathVariable String conversationId) {
        ChatConversation conversation = conversationService.getConversation(conversationId);
        if (conversation == null) {
            throw new IllegalArgumentException("会话不存在: " + conversationId);
        }
        return ChatConversationResponse.from(conversation);
    }

    private ChatCommand command(ChatRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("请求体不能为空");
        }
        String userId = request.userId() == null || request.userId().isBlank()
                ? properties.getDefaultUserId() : request.userId();
        return new ChatCommand(request.conversationId(), userId, request.query());
    }

    private static ChatStreamResponse toResponse(ChatStreamEvent event) {
        return new ChatStreamResponse(event.type().name(), event.conversationId(),
                event.conversationTitle(), event.userMessageId(),
                event.assistantMessageId(), event.content(),
                event.tokenCount(), event.modelName());
    }
}
