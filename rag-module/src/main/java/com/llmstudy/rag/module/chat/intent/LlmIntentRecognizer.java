package com.llmstudy.rag.module.chat.intent;

import com.llmstudy.rag.config.IntentProperties;
import com.llmstudy.rag.module.chat.model.IntentRecognitionResult;
import com.llmstudy.rag.module.llm.LlmTraceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.util.StreamUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** 使用 LLM 结构化输出判断当前问题是否应进入论文 RAG。 */
@Service
public class LlmIntentRecognizer implements IntentRecognizer {

    private static final Logger log =
            LoggerFactory.getLogger(LlmIntentRecognizer.class);

    private final ChatClient chatClient;
    private final IntentProperties properties;
    private final String systemPrompt;

    public LlmIntentRecognizer(
            ChatClient chatClient,
            IntentProperties properties,
            @Value("classpath:prompts/chat/intent-recognition.st") Resource promptResource) {
        this.chatClient = chatClient;
        this.properties = properties;
        this.systemPrompt = readPrompt(promptResource);
    }

    /**
     * 结合近期会话识别意图。任何模型、网络或结构化解析异常都保守进入 RAG。
     */
    @Override
    public IntentRecognitionResult recognize(String query, List<Message> history) {
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("用户问题不能为空");
        }
        try {
            ChatClient.ChatClientRequestSpec prompt = chatClient.prompt()
                    .system(systemPrompt)
                    .user(buildUserPrompt(history, query.trim()))
                    .advisors(spec -> spec.params(
                            LlmTraceContext.params("intent-recognition")))
                    .options(OpenAiChatOptions.builder().temperature(0.0));
            if (properties.getModel() != null && !properties.getModel().isBlank()) {
                prompt = prompt.options(OpenAiChatOptions.builder()
                        .model(properties.getModel())
                        .temperature(0.0));
            }
            IntentRecognitionResult result = prompt.call().entity(
                    IntentRecognitionResult.class,
                    spec -> spec.validateSchema());
            if (result == null) {
                return IntentRecognitionResult.fallback("意图模型未返回结果");
            }
            IntentRecognitionResult normalized = result.normalized();
            log.info("意图识别完成: related={}, intent={}, fallback={}",
                    normalized.related(), normalized.intent(), normalized.fallback());
            return normalized;
        } catch (Exception e) {
            log.warn("意图识别失败，保守进入 RAG: {}", e.getMessage());
            return IntentRecognitionResult.fallback(
                    "意图识别失败，保守进入 RAG: " + e.getClass().getSimpleName());
        }
    }

    /** 将历史消息与当前问题组装成边界明确的意图识别输入。 */
    private static String buildUserPrompt(List<Message> history, String query) {
        StringBuilder text = new StringBuilder("<conversation_history>\n");
        if (history == null || history.isEmpty()) {
            text.append("无\n");
        } else {
            for (Message message : history) {
                if (message instanceof UserMessage userMessage) {
                    text.append("USER: ").append(userMessage.getText()).append('\n');
                } else if (message instanceof AssistantMessage assistantMessage) {
                    text.append("ASSISTANT: ").append(assistantMessage.getText()).append('\n');
                }
            }
        }
        // 用显式 XML 边界隔离历史与当前问题，降低模型混淆指令层级的概率。
        return text.append("</conversation_history>\n<current_query>\n")
                .append(query)
                .append("\n</current_query>")
                .toString();
    }

    /** 启动时以 UTF-8 加载意图识别系统提示词。 */
    private static String readPrompt(Resource resource) {
        try {
            return StreamUtils.copyToString(
                    resource.getInputStream(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("读取意图识别提示词失败", e);
        }
    }
}
