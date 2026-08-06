package com.llmstudy.rag.config;

import com.llmstudy.rag.module.llm.LlmFileLoggingAdvisor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ChatClientConfig {

    @Bean
    public ChatClient chatClient(ChatModel chatModel,
                                 LlmFileLoggingAdvisor loggingAdvisor) {
        return ChatClient.builder(chatModel)
                .defaultAdvisors(loggingAdvisor)
                .build();
    }
}
