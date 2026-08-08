package com.llmstudy.rag.controller;

import com.llmstudy.rag.auth.model.AccessContext;
import com.llmstudy.rag.auth.service.CurrentUserProvider;
import com.llmstudy.rag.auth.authorization.ResourceAccessDeniedException;
import com.llmstudy.rag.dto.ChatConversationResponse;
import com.llmstudy.rag.dto.ChatMessageResponse;
import com.llmstudy.rag.dto.ChatRequest;
import com.llmstudy.rag.dto.ChatResponse;
import com.llmstudy.rag.dto.ChatStreamResponse;
import com.llmstudy.rag.entity.ChatConversation;
import com.llmstudy.rag.module.chat.ChatOrchestrator;
import com.llmstudy.rag.module.chat.conversation.ConversationService;
import com.llmstudy.rag.module.chat.model.ChatCommand;
import com.llmstudy.rag.module.chat.model.ChatStreamEvent;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

import java.util.List;

/** HTTP adapter for chat; business routing lives in module.chat. */
@RestController
@RequestMapping("/chat/client")
public class ChatClientController {

    private final ChatOrchestrator orchestrator;
    private final ConversationService conversationService;
    private final CurrentUserProvider currentUserProvider;

    public ChatClientController(ChatOrchestrator orchestrator,
                                ConversationService conversationService,
                                CurrentUserProvider currentUserProvider) {
        this.orchestrator = orchestrator;
        this.conversationService = conversationService;
        this.currentUserProvider = currentUserProvider;
    }

    /** 同步聊天入口；命令构造阶段会捕获当前用户身份。 */
    @PostMapping("/ask")
    public ChatResponse ask(@RequestBody ChatRequest request) {
        ChatOrchestrator.ChatAnswer answer = orchestrator.ask(command(request));
        return new ChatResponse(answer.conversationId(), answer.conversationTitle(),
                answer.userMessageId(), answer.assistantMessageId(), answer.content(),
                answer.tokenCount(), answer.modelName());
    }

    /**
     * SSE 聊天入口。订阅和生成可能切换线程，因此必须先在调用 orchestrator 前捕获身份。
     */
    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ChatStreamResponse> stream(@RequestBody ChatRequest request) {
        return orchestrator.stream(command(request)).map(ChatClientController::toResponse);
    }

    @GetMapping("/conversations/{conversationId}")
    public ChatConversationResponse getConversation(@PathVariable String conversationId) {
        String userId = currentUserProvider.requireCurrentUser().userId();
        ChatConversation conversation = conversationService.getConversation(conversationId, userId);
        if (conversation == null) {
            throw new ResourceAccessDeniedException("会话不存在或无权访问");
        }
        return ChatConversationResponse.from(conversation);
    }

    /** 从 MySQL 返回指定用户的活跃会话，按最近更新时间倒序排列。 */
    @GetMapping("/conversations")
    public List<ChatConversationResponse> listConversations() {
        String userId = currentUserProvider.requireCurrentUser().userId();
        return conversationService.listConversations(userId).stream()
                .map(ChatConversationResponse::from)
                .toList();
    }

    /** 从 MySQL 读取会话的全部消息，用于前端切换会话时恢复历史。 */
    @GetMapping("/conversations/{conversationId}/messages")
    public List<ChatMessageResponse> listMessages(
            @PathVariable String conversationId) {
        String userId = currentUserProvider.requireCurrentUser().userId();
        return conversationService.listMessages(conversationId, userId).stream()
                .map(ChatMessageResponse::from)
                .toList();
    }

    /** 逻辑删除会话；历史消息保留，会话状态更新为 DELETED。 */
    @DeleteMapping("/conversations/{conversationId}")
    public ResponseEntity<Void> deleteConversation(
            @PathVariable String conversationId) {
        String userId = currentUserProvider.requireCurrentUser().userId();
        conversationService.deleteConversation(conversationId, userId);
        return ResponseEntity.noContent().build();
    }

    private ChatCommand command(ChatRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("请求体不能为空");
        }
        // 这是异步边界前唯一一次读取当前登录身份，之后只传递不可变 AccessContext。
        AccessContext accessContext = currentUserProvider.requireAccessContext();
        return new ChatCommand(request.conversationId(), accessContext, request.query());
    }

    private static ChatStreamResponse toResponse(ChatStreamEvent event) {
        return new ChatStreamResponse(event.type().name(), event.conversationId(),
                event.conversationTitle(), event.userMessageId(),
                event.assistantMessageId(), event.content(),
                event.tokenCount(), event.modelName());
    }
}
