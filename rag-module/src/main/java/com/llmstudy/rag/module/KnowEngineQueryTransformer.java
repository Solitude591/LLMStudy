package com.llmstudy.rag.module;

import com.llmstudy.rag.service.ChatService;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.invocation.InvocationContext;
import dev.langchain4j.invocation.InvocationParameters;
import dev.langchain4j.rag.query.Metadata;
import dev.langchain4j.rag.query.Query;
import dev.langchain4j.rag.query.transformer.QueryTransformer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.prompt.PromptTemplate;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 使用 Spring AI 改写用户问题，并通过 LangChain4j 的 QueryTransformer
 * 接口将改写后的问题交给后续 RAG 检索流程。
 *
 * <p>项目的模型调用统一使用 Spring AI，只保留 LangChain4j 的
 * Query 和 QueryTransformer 作为 RAG 流程接口，两者不会产生 Bean 冲突。</p>
 */
public class KnowEngineQueryTransformer implements QueryTransformer {

    private static final Logger log =
            LoggerFactory.getLogger(KnowEngineQueryTransformer.class);

    /**
     * 研究生论文知识库的问题改写模板。
     *
     * <p>模板要求模型只生成一条适合检索的语句，不直接回答问题，
     * 并通过 Few-shot 示例约束专业术语扩展、论文检索维度和输出格式。</p>
     */
    private static final String DEFAULT_PROMPT_TEMPLATE = """
            # 角色

            你是一名研究生论文知识库的检索问题改写助手。
            你的任务是将用户输入的问题改写为适合向量检索和关键词检索的查询语句，
            帮助 RAG 系统从学位论文、研究报告和学术文献中找到最相关的内容。
            你只负责改写检索问题，不负责回答问题。

            # 改写目标

            将口语化、简略、存在省略或检索意图不清晰的问题，改写为语义完整、
            专业术语明确、适合检索论文正文的查询语句。

            # 改写策略

            1. 准确识别用户希望检索的研究对象、核心主题和问题类型。
            2. 保留原问题中的专业术语、模型名称、算法名称、英文缩写、数据集名称、
               公式编号、章节编号和图表编号等关键信息。
            3. 根据问题意图，适当补充论文中常见的相关检索维度，例如：
               研究背景、理论基础、方法原理、模型结构、实验设计、数据集、评价指标、
               对比实验、消融实验、实验结果、创新点和局限性。
            4. 如果问题包含“它”“这个方法”“该模型”“上述实验”等指代，
               只有在当前问题已经提供足够信息时才能替换；无法确定时不得猜测。
            5. 不得添加原问题中没有依据的作者、论文名称、实验数据、研究结论或技术细节。
            6. 不得回答用户的问题，只输出改写后的检索语句。
            7. 输出一条完整、自然的中文检索语句，不要输出分析过程、解释、序号、
               标签、引号、Markdown 或 JSON。
            8. 避免机械堆砌关键词，改写结果应保持清晰、简洁，并与原问题意图一致。

            以上改写策略需逐一使用最终给出一个统一的改写结果。直接输出改写后的结果，不需要输出思考过程以及额外的多余内容。如果不需要改写，则直接输出原问题即可。

            # Few-shot 示例

            用户问题：
            这个模型有哪些创新？

            改写结果：
            该模型的主要创新点、核心方法改进、模型结构创新及其相对于已有方法的优势

            用户问题：
            为什么实验里要用消融？

            改写结果：
            论文设置消融实验的目的、消融实验设计以及各模型组件对实验结果的影响

            用户问题：
            这个数据集是怎么处理的？

            改写结果：
            该数据集在实验中的数据清洗、预处理、样本划分、数据增强及输入格式处理方法

            用户问题：
            Transformer 在这里起什么作用？

            改写结果：
            Transformer 模型在该研究方法中的功能、网络结构位置、特征建模方式及其对实验效果的作用

            用户问题：
            表3能说明什么？

            改写结果：
            论文表3中的实验设置、评价指标、对比结果及其反映的模型性能差异

            用户问题：
            这个方法比传统方法好在哪里？

            改写结果：
            该方法与传统方法在方法原理、模型性能、计算复杂度、适用场景和实验结果方面的对比优势

            # 待改写问题

            用户问题：
            {query}

            改写结果：
            """;

    /** 项目中已由 Spring Boot 自动配置的 Spring AI 聊天模型。 */
    private final ChatModel chatModel;

    /** 负责将改写结果回写到 chat_message.transform_content。 */
    private final ChatService chatService;

    /** 当前转换器使用的改写模板，调用方显式传 null 时不使用模板。 */
    private final PromptTemplate promptTemplate;

    /** 当前用户原始问题对应的消息 ID，用于异步回写改写结果。 */
    private final String sourceMessageId;

    /**
     * 使用类中的默认改写模板创建转换器。
     *
     * <p>默认模板已包含角色定义、论文场景改写策略、输出约束和
     * Few-shot 示例，调用时会自动将原始问题渲染到 query 变量。</p>
     *
     * @param chatModel       Spring AI 聊天模型
     * @param chatService     聊天消息服务，不需要回写时可传 null
     * @param sourceMessageId 原始用户消息 ID，不需要回写时可传 null
     */
    public KnowEngineQueryTransformer(ChatModel chatModel,
                                      ChatService chatService,
                                      String sourceMessageId) {
        this(chatModel,
                chatService,
                new PromptTemplate(DEFAULT_PROMPT_TEMPLATE),
                sourceMessageId);
    }

    /**
     * 使用调用方传入的 Spring AI PromptTemplate 创建转换器。
     *
     * @param chatModel       Spring AI 聊天模型
     * @param chatService     聊天消息服务，不需要回写时可传 null
     * @param promptTemplate  问题改写模板，传 null 时直接使用原始问题
     * @param sourceMessageId 原始用户消息 ID，不需要回写时可传 null
     */
    public KnowEngineQueryTransformer(ChatModel chatModel,
                                      ChatService chatService,
                                      PromptTemplate promptTemplate,
                                      String sourceMessageId) {
        if (chatModel == null) {
            throw new IllegalArgumentException("chatModel 不能为空");
        }
        this.chatModel = chatModel;
        this.chatService = chatService;
        this.promptTemplate = promptTemplate;
        this.sourceMessageId = sourceMessageId == null || sourceMessageId.isBlank()
                ? null
                : sourceMessageId.trim();
    }

    /**
     * 调用 Spring AI 完成问题改写，生成新的检索 Query，并异步保存改写结果。
     *
     * <p>只返回改写后的 Query，原问题通过 Query metadata 的 chatMessage 保留，
     * 供混合检索器读取原问题执行 BM25；这样同一个 Retriever 只会被调用一次，
     * 避免改写问题和原问题分别触发一次完整检索。</p>
     *
     * @param query LangChain4j RAG 流程传入的原始 Query
     * @return 只包含改写后 Query 的列表，原问题位于返回 Query 的 metadata 中
     */
    @Override
    public Collection<Query> transform(Query query) {
        // 进入模型调用前先拒绝空问题，避免产生无意义的模型请求。
        if (query == null || query.text() == null || query.text().isBlank()) {
            throw new IllegalArgumentException("query 不能为空");
        }

        log.info("开始问题改写, 原始问题: {}", query.text());

        // 默认使用论文知识库模板；显式传入 null 时则直接将原问题发送给模型。
        Prompt prompt = promptTemplate == null
                ? new Prompt(query.text())
                : promptTemplate.create(Map.of("query", query.text()));
        ChatResponse chatResponse = chatModel.call(prompt);

        // Spring AI 的模型响应存在多层对象，逐层校验可以返回更清晰的业务异常。
        if (chatResponse == null
                || chatResponse.getResult() == null
                || chatResponse.getResult().getOutput() == null
                || chatResponse.getResult().getOutput().getText() == null
                || chatResponse.getResult().getOutput().getText().isBlank()) {
            throw new IllegalStateException("模型未返回有效的问题改写结果");
        }
        String rewrittenQuery = chatResponse.getResult().getOutput().getText().trim();
        log.info("问题改写完成, 改写后问题: {}", rewrittenQuery);

        // 模板已要求输出可直接检索的完整语句，因此不再追加用户 ID、当前时间等无关语义。
        String retrievalQuery = rewrittenQuery;

        // 改写后 Query 携带原问题 metadata：改写问题作为 Query.text() 供 KNN 使用，
        // 原问题写入 metadata.chatMessage 供 BM25 使用，保证 Retriever 只被调用一次。
        Metadata metadata = query.metadata();
        if (metadata == null) {
            metadata = buildMetadata(query.text());
        }
        Query transformedQuery = Query.from(retrievalQuery, metadata);

        // 回写是检索之外的附加操作，使用虚拟线程避免增加主检索流程的耗时。
        if (sourceMessageId != null && chatService != null) {
            Thread.ofVirtual()
                    .name("query-transform-" + sourceMessageId)
                    .start(() -> {
                        try {
                            chatService.updateMessageTransformContent(
                                    sourceMessageId, rewrittenQuery);
                        } catch (Exception e) {
                            // 数据库回写失败不应影响已经生成的检索 Query。
                            log.error("异步回写问题改写结果失败: messageId={}",
                                    sourceMessageId, e);
                        }
                    });
        }

        // 只返回改写后 Query，避免同一个 Retriever 被调用两次。
        return List.of(transformedQuery);
    }

    /**
     * 构建携带原始问题的 Query metadata。
     *
     * <p>Query.Metadata 要求非空的 InvocationContext，但本项目的 RAG 流程并不依赖
     * LangChain4j AI Service 调用链，因此使用最小占位上下文，仅保证 metadata
     * 可合法构造；检索器只读取 chatMessage 中的原问题。</p>
     *
     * @param originalQuery 用户原始问题
     * @return 以原问题为 chatMessage 的 Query metadata
     */
    private Metadata buildMetadata(String originalQuery) {
        InvocationContext invocationContext = InvocationContext.builder()
                .invocationId(UUID.randomUUID())
                .interfaceName(KnowEngineQueryTransformer.class.getName())
                .methodName("transform")
                .methodArguments(List.of(originalQuery))
                .invocationParameters(new InvocationParameters())
                .timestampNow()
                .build();
        return Metadata.builder()
                .chatMessage(UserMessage.from(originalQuery))
                .invocationContext(invocationContext)
                .build();
    }
}
