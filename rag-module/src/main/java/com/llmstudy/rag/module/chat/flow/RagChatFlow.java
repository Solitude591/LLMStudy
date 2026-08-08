package com.llmstudy.rag.module.chat.flow;

import com.llmstudy.rag.module.rag.RagPipeline;
import com.llmstudy.rag.module.rag.model.RagRequest;
import com.llmstudy.rag.module.rag.model.RagResult;
import com.llmstudy.rag.module.rag.model.RagAnswerMode;
import com.llmstudy.rag.module.rag.model.RagFocusInformation;
import com.llmstudy.rag.module.rag.model.RagIntentContext;
import com.llmstudy.rag.module.chat.model.ChatIntent;
import com.llmstudy.rag.module.chat.model.IntentKeyInformation;
import com.llmstudy.rag.module.chat.model.IntentRecognitionResult;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Component;

import java.util.List;

/** RAG 聊天 Flow：负责转换聊天上下文，检索编排统一委托给 {@link RagPipeline}。 */
@Component
public class RagChatFlow implements ChatFlow {

    public static final String NO_KNOWLEDGE_ANSWER =
            "根据当前论文知识库资料，我暂时无法回答这个问题。";
    private final RagPipeline pipeline;

    public RagChatFlow(RagPipeline pipeline) {
        this.pipeline = pipeline;
    }

    /** 执行 RAG Pipeline，并把改写问题和引用交回聊天编排层持久化。 */
    @Override
    public FlowPreparation prepare(ChatFlowContext context) {
        // 将请求入口捕获的身份继续传给 RAG Pipeline；此处可能已经不在原 HTTP 线程。
        RagResult result = pipeline.execute(new RagRequest(
                context.query(), formatHistory(context.history()), toRagIntent(context.intent()),
                context.accessContext()));
        // 空检索结果不再请求 LLM，避免模型在无证据时自由发挥。
        return new FlowPreparation(result.prompt(),
                result.rewrittenQuery().rewrittenQuestion(), result.references(),
                result.empty() ? NO_KNOWLEDGE_ANSWER : null);
    }

    /** 将 Chat 意图转换为 RAG 自有策略，避免 RAG 模块反向依赖 Chat。 */
    static RagIntentContext toRagIntent(IntentRecognitionResult result) {
        if (result == null) {
            return RagIntentContext.generic();
        }
        ChatIntent intent = result.intent();
        RagAnswerMode mode = intent == null ? RagAnswerMode.GENERIC : switch (intent) {
            case PAPER_RETRIEVAL -> RagAnswerMode.PAPER_RETRIEVAL;
            case PAPER_SUMMARY -> RagAnswerMode.PAPER_SUMMARY;
            case PAPER_CONTENT_QA -> RagAnswerMode.PAPER_CONTENT_QA;
            case METHOD_OR_CONCEPT -> RagAnswerMode.METHOD_OR_CONCEPT;
            case EXPERIMENT_OR_RESULT -> RagAnswerMode.EXPERIMENT_OR_RESULT;
            case COMPARISON_OR_CRITIQUE -> RagAnswerMode.COMPARISON_OR_CRITIQUE;
            case ACADEMIC_PAPER_ASSISTANCE -> RagAnswerMode.ACADEMIC_PAPER_ASSISTANCE;
            case GENERAL_CHAT, UNKNOWN -> RagAnswerMode.GENERIC;
        };
        // 只传递主意图与结构化检索焦点，分类理由仅作审计 metadata。
        IntentKeyInformation keyInformation = result.keyInformation() == null
                ? IntentKeyInformation.empty() : result.keyInformation().normalized();
        return new RagIntentContext(mode, new RagFocusInformation(
                keyInformation.paperTitles(), keyInformation.authors(),
                keyInformation.researchTopics(), keyInformation.methodsOrModels(),
                keyInformation.datasets(), keyInformation.metrics(),
                keyInformation.otherConstraints()));
    }

    /** 将 Spring AI 消息历史序列化为查询改写模板可读的文本。 */
    static String formatHistory(List<Message> history) {
        if (history == null || history.isEmpty()) {
            return "无";
        }
        StringBuilder context = new StringBuilder();
        for (Message message : history) {
            if (message instanceof UserMessage user) {
                context.append("USER: ").append(user.getText()).append('\n');
            } else if (message instanceof AssistantMessage assistant) {
                context.append("ASSISTANT: ").append(assistant.getText()).append('\n');
            }
        }
        return context.isEmpty() ? "无" : context.toString().trim();
    }
}
