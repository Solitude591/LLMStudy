# RAGAS 数据生成接口实施计划

## 1. 目标

在controller/dev下新增 `POST /dataset/generate` 接口，输入用户问题后完整执行现有 RAG 检索链路：

1. 查询改写
2. BM25/KNN 混合检索
3. RRF 融合与可选重排
4. Prompt 注入
5. LLM 生成回答

返回数据仅包含：

- 用户提交的原始 `query`
- AI 生成的 `response`
- 最终参与回答生成的 chunk 正文列表 `chunks`

`chunks` 中不返回引用编号、文档 ID、chunk ID、标题路径、来源 URL、检索分数或重排分数。

## 2. 接口契约

### 2.1 请求

```http
POST /dataset/generate
Authorization: Bearer <token>
Content-Type: application/json
```

```json
{
  "query": "表 3 中哪个模型的 F1 最高？"
}
```

请求字段：

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `query` | `String` | 是 | 用户原始问题，不允许为 `null`、空字符串或全空白字符 |

### 2.2 成功响应

```json
{
  "code": 0,
  "message": "ok",
  "data": {
    "query": "表 3 中哪个模型的 F1 最高？",
    "response": "Hybrid RAG 的 F1 最高[1]。",
    "chunks": [
      "表 3 展示了各模型的实验结果……",
      "Hybrid RAG 在该数据集上取得了最高 F1……"
    ]
  }
}
```

建议响应 DTO：

```java
public record DatasetGenerateResponse(
        String query,
        String response,
        List<String> chunks) {
}
```

接口使用项目现有 `ApiResult.ok(data)` 包装返回值。

### 2.3 空检索响应

未检索到候选 chunk 时，不调用最终回答 LLM，返回可控的固定回答：

```json
{
  "code": 0,
  "message": "ok",
  "data": {
    "query": "用户原始问题",
    "response": "根据当前论文知识库资料，我暂时无法回答这个问题。",
    "chunks": []
  }
}
```

### 2.4 参数错误

`query` 无效时抛出 `IllegalArgumentException`，由现有 `GlobalExceptionHandler` 转换为 HTTP 400 和统一 `ApiResult` 结构。

## 3. 设计约束

### 3.1 必须复用在线 RAG Pipeline

新接口应调用 `RagPipeline.execute(...)`，不在 Controller 或 Dataset Service 中重复实现检索、融合、重排逻辑。

接口没有会话语义，建议使用：

- `question`: 请求中的 `query`
- `conversationContext`: `"无"`
- `intentContext`: `RagIntentContext.generic()`
- `accessContext`: 在 Controller 请求线程中通过 `CurrentUserProvider.requireAccessContext()` 捕获

该设计会强制走 RAG 检索，不经过 Common Chat/RAG 意图分流。

### 3.2 不使用会话编排器

Dataset 生成不需要：

- 创建或读取 `ChatConversation`
- 保存用户消息和助手消息
- 生成会话标题
- 返回消息 ID、Token 数量或模型名称

会话的title固定以 `dataset-creator + userId + localdateTime.now()`保存，不需要调用titleservice生成title。

因此不应直接复用 `ChatOrchestrator.ask()` 或 `ChatOrchestrator.stream()`，而应新增独立的 Dataset 生成服务。

### 3.3 权限边界保持不变

`/dataset/generate` 不加入 `AuthConfig.PUBLIC_PATHS`。接口必须需要登录，并将当前用户的 `AccessContext` 显式传入 RAG Pipeline，保证 BM25/KNN 仅检索当前用户可读的已发布文档版本。

### 3.4 不修改 `RagReference` 的对外含义

不建议将 chunk 正文加入 `RagReference`，因为该对象会被序列化到现有聊天消息的 `ragReferences` 字段，修改它会同时改变聊天接口和数据库中的引用结构。

建议在 `RagResult` 中新增独立的 `List<String> chunks` 字段，由 `RagPipeline` 从最终聚合候选中提取 `RetrievalCandidate.text()`。

## 4. 建议改动

### 4.1 DTO

新增：

- `dto/DatasetGenerateRequest.java`
- `dto/DatasetGenerateResponse.java`

`DatasetGenerateRequest` 可以在 compact constructor 中完成非空校验。响应中的 `chunks` 应防御性复制，且不返回 `null`。

### 4.2 RAG Pipeline 输出

修改 `module/rag/model/RagResult.java`：

```java
public record RagResult(
        LlmPrompt prompt,
        RewrittenQuery rewrittenQuery,
        List<RagReference> references,
        List<String> chunks) {
}
```

构造时对 `references` 和 `chunks` 做非 `null` 处理和防御性复制。

修改 `module/rag/RagPipeline.java`：

```java
List<String> chunks = candidates.stream()
        .map(RetrievalCandidate::text)
        .toList();
```

`chunks` 必须来自 `RetrievalAggregator.aggregate(...)` 返回的最终候选，以保证：

- 已经完成 RRF 融合
- 已经完成可选重排
- 已经按配置截断 Top N
- 顺序与注入 Prompt 的证据顺序一致

### 4.3 Dataset 生成服务

建议新增：

- `module/dataset/DatasetGenerationService.java`

核心流程：

1. 校验并保留用户原始 `query`。
2. 组装带 `AccessContext` 的 `RagRequest`。
3. 调用 `RagPipeline.execute(...)`。
4. 若 `RagResult.empty()`，返回固定回答和空 `chunks`。
5. 否则使用 `RagResult.prompt()` 调用现有 `ChatClient`。
6. 检查模型响应不为 `null` 且不是空白文本。
7. 返回 `DatasetGenerateResponse(query, response, chunks)`。

LLM 调用建议设置独立日志阶段，例如 `dataset-generate`，便于与 `query-rewrite`、`final-answer` 等现有日志区分。

### 4.4 Controller

新增：

- `controller/DatasetController.java`

接口职责仅限于：

1. 检查请求体不为 `null`。
2. 通过 `CurrentUserProvider.requireAccessContext()` 捕获当前用户权限快照。
3. 调用 `DatasetGenerationService.generate(query, accessContext)`。
4. 使用 `ApiResult.ok(...)` 包装结果。

Controller 不直接操作 `RagPipeline`、`ChatClient` 或数据库。

## 5. 兼容性注意事项

`RagResult` 增加字段后，需要同步更新现有单元测试中的构造调用，特别是：

- `RagPipelineTest`
- `ChatFlowTest`

现有 `RagChatFlow` 只使用 `prompt`、`rewrittenQuery` 和 `references`，新增 `chunks` 不应改变聊天链路的行为。

现有 `RagPromptInjector` 仍可以为聊天和 Dataset 生成构建带编号、来源 metadata 的内部 Prompt。Dataset HTTP 响应只从 `RagResult.chunks()` 取原始正文，不将 `RagReference` 暴露给调用方。

## 6. 测试计划

### 6.1 `RagPipelineTest`

增加断言：

- `chunks` 只包含 `RetrievalCandidate.text()`
- `chunks` 顺序与聚合后 candidates 顺序一致
- 空候选返回空列表

### 6.2 `DatasetGenerationServiceTest`

覆盖：

- 携带指定 `AccessContext` 调用 RAG Pipeline
- 使用原始 query，不将改写查询返回给用户
- 有检索结果时调用 LLM 并返回完整文本
- `chunks` 仅为正文字符串列表
- 空检索时返回固定回答且不调用 LLM
- 模型返回空响应时抛出明确异常

### 6.3 `DatasetControllerTest`

覆盖：

- Controller 从 `CurrentUserProvider` 获取 `AccessContext`
- 请求被正确传给 Dataset Service
- 响应使用 `ApiResult`
- 请求体为 `null` 或 `query` 为空时返回 HTTP 400
- JSON `data` 中只有 `query`、`response`、`chunks`
- JSON 中不包含 `citation`、`sourceUrl`、`docId`、`chunkId`、`score` 和 `rerankedScore`

### 6.4 回归测试

至少执行：

```bash
mvn test
```

确认现有聊天流式接口、非流式接口、RAG Prompt 注入和引用持久化测试不受影响。

## 7. 验收标准

- `POST /dataset/generate` 能够在已登录状态下成功调用。
- 检索使用现有查询改写、混合检索、融合、重排和 Top N 配置。
- 检索结果受当前用户文档访问权限约束。
- `data.query` 是用户原问题，不是改写后的查询。
- `data.response` 是基于本次检索 Prompt 生成的完整 AI 回答。
- `data.chunks` 与最终注入 Prompt 的 chunk 正文集合和顺序一致。
- HTTP 响应不暴露引用编号或来源 URL 等 metadata。
- 新接口不创建会话，不保存聊天消息。
- 空检索不调用最终回答 LLM。
- 全部自动化测试通过。

## 8. 待确认的可选项

当前计划将“不需要编号、来源 URL”理解为 Dataset 接口的 `chunks` 输出不携带 metadata。现有 RAG System Prompt 仍要求 AI 在回答中使用 `[1]`、`[2]` 等引用编号，因此 `response` 可能包含引用标记。

如果 RAGAS 数据中的 `response` 也不允许出现引用编号，应另外增加 Dataset 专用回答 Prompt，不建议在模型返回后通过正则表达式删除编号。
