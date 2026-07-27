package com.llmstudy.rag.controller;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

@RestController
@RequestMapping("/chat/deepseek")
public class DeepseekController {

    private final ChatModel chatModel;

    public DeepseekController(@Qualifier("openAiChatModel") ChatModel chatModel) {
        this.chatModel = chatModel;
    }

    @GetMapping("/ask")
    public String ask(@RequestParam String query) {
        return chatModel.call(query);
    }

    @GetMapping(value = "/stream", produces = "text/event-stream;charset=UTF-8")
    public Flux<String> askStream(
            @RequestParam String query,
            HttpServletResponse response) {
        response.setCharacterEncoding("UTF-8");
        Prompt prompt = new Prompt(new UserMessage(query));
        return chatModel.stream(prompt)
                .mapNotNull(chatResponse ->
                        chatResponse.getResult().getOutput().getText());
    }
}
