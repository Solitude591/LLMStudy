package com.llmstudy.rag.module.chat.title;

import com.llmstudy.rag.config.TitleSummaryProperties;
import com.llmstudy.rag.module.chat.conversation.ConversationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/**
 * 会话标题异步生成服务。
 *
 * <p>复用 Spring AI 已配置的 ChatClient，通过按次覆盖 system 提示词和模型名，
 * 不与主对话的默认配置互相影响。会话创建时先保存基于首次问题生成的临时标题，
 * 本服务通过 Spring 管理的虚拟线程异步调用轻量模型并回写更精准的标题；
 * 生成失败仅记录日志，保留临时标题，不影响主对话流程。</p>
 */
@Service
public class TitleSummaryService {

    private static final Logger log = LoggerFactory.getLogger(TitleSummaryService.class);

    /** 标题生成提示词，要求模型只输出标题本身。 */
    private static final String SYSTEM_PROMPT_TEMPLATE =
            "你是一个会话标题生成助手。根据用户的第一句话，生成一个不超过 %d 个字的简短中文标题，"
                    + "概括对话主题。只输出标题本身，不要引号、不要解释、不要以标点符号结尾。";

    private final ChatClient chatClient;

    private final ConversationService conversationService;

    private final TitleSummaryProperties properties;

    public TitleSummaryService(ChatClient chatClient,
                               ConversationService conversationService,
                               TitleSummaryProperties properties) {
        this.chatClient = chatClient;
        this.conversationService = conversationService;
        this.properties = properties;
    }

    /**
     * 异步生成并更新会话标题；失败仅记录日志，保留临时标题。
     *
     * @param conversationId 会话 ID（调用方保证会话已落库）
     * @param firstQuery     用户的第一句话
     */
    @Async("titleSummaryExecutor")
    public void generateTitleAsync(String conversationId, String firstQuery) {
        try {
            // 标题生成与主对话使用独立异步任务，慢响应不会延长聊天接口耗时。
            String aiTitle = generateTitle(firstQuery);
            if (aiTitle == null || aiTitle.isBlank()) {
                log.warn("标题生成结果为空，保留临时标题: conversationId={}", conversationId);
                return;
            }

            // 模型返回有效标题后，通过事务 Service 更新会话记录。
            conversationService.updateConversationTitle(conversationId, aiTitle);
            log.debug("会话标题已更新: conversationId={}, title={}", conversationId, aiTitle);
        } catch (Exception e) {
            // 生成失败不影响主流程，数据库中仍保留创建会话时写入的临时标题。
            log.error("异步标题生成失败: conversationId={}", conversationId, e);
        }
    }

    /**
     * 调用轻量模型生成标题；配置了 rag.chat.title-summary.model 时按次覆盖主模型。
     */
    private String generateTitle(String firstQuery) {
        ChatClient.ChatClientRequestSpec prompt = chatClient.prompt()
                .system(SYSTEM_PROMPT_TEMPLATE.formatted(properties.getMaxTitleLength()))
                .user("用户的第一句话：" + firstQuery);
        if (properties.getModel() != null && !properties.getModel().isBlank()) {
            // Spring AI 2.x 使用 options() 覆盖模型，替代 1.x 的 model() 方法
            prompt = prompt.options(
                    OpenAiChatOptions.builder().model(properties.getModel()));
        }
        return normalize(prompt.call().content());
    }

    /**
     * 规范化模型输出：去首尾引号与空白、合并连续空白、按配置截断。
     */
    private String normalize(String generated) {
        if (generated == null) {
            return null;
        }
        String title = generated
                .replaceAll("^[\"'“”‘’\\s]+|[\"'“”‘’\\s]+$", "")
                .replaceAll("\\s+", " ")
                .trim();
        if (title.length() > properties.getMaxTitleLength()) {
            title = title.substring(0, properties.getMaxTitleLength());
        }
        return title;
    }
}
