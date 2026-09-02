# RAGAS 评测

## 产物布局

每次 `generate_dataset` 会新建一个 run，**不会覆盖**历史结果：

```text
ragas_demo/data/runs/
  20260813-105500/
    generated.jsonl   # /dataset/generate 产出
    scores.json       # evaluate 产出
  20260813-160405/
    generated.jsonl
    scores.json
```

首次运行时，若 `data/` 下仍有旧的扁平
`medical-image-segmentation-ragas-generated.jsonl` /
`medical-image-segmentation-ragas-scores.json`，会自动迁入
`data/runs/<修改时间戳>/`。

## 环境变量

复制 `.env.example` 为 `.env`。评测需要 **两套** OpenAI 兼容客户端：

- `OPENAI_*` / `RAGAS_LLM_MODEL`：LLM（如 DeepSeek）
- `EMBEDDING_*`：向量模型（默认 `text-embedding-v4` + DashScope compatible-mode）

Answer Relevancy / Answer Correctness 依赖 embedding；DeepSeek 无 embeddings 接口，不可共用 `OPENAI_BASE_URL`。

## 常用命令

```bash
# 生成新一轮样本（写入新 run）
uv run python -m ragas_demo.generate_dataset

# 评测最新 run（scores 写到同目录）
uv run python -m ragas_demo.evaluate

# 生成 + 评测一次跑完（同一次 run）
uv run python -m ragas_demo.run

# 评测指定历史 run
uv run python -m ragas_demo.evaluate \
  --input data/runs/20260813-105500/generated.jsonl
```
