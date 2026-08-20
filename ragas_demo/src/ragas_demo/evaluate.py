"""读取 generate_dataset 产出的 JSONL，用 RAGAS 五个指标逐条打分。"""

from __future__ import annotations

import argparse
import asyncio
import json
import os
import statistics
import sys
from pathlib import Path

from dotenv import load_dotenv
from openai import AsyncOpenAI
from ragas.embeddings.base import embedding_factory
from ragas.llms import llm_factory
from ragas.metrics.collections import (
    AnswerCorrectness,
    AnswerRelevancy,
    ContextPrecision,
    ContextRecall,
    Faithfulness,
)

from ragas_demo.paths import (
    default_scores_for,
    migrate_legacy_flat_files,
    resolve_latest_generated,
)

METRICS = (
    "Context Precision",
    "Context Recall",
    "Answer Relevancy",
    "Faithfulness",
    "Answer Correctness",
)


def load_jsonl(path: Path) -> list[dict]:
    rows: list[dict] = []
    with path.open("r", encoding="utf-8") as f:
        for line_no, line in enumerate(f, start=1):
            text = line.strip()
            if not text:
                continue
            row = json.loads(text)
            for key in ("user_input", "reference", "response", "retrieved_contexts"):
                if key not in row:
                    raise RuntimeError(f"{path}:{line_no} 缺少 {key}")
            if not isinstance(row["retrieved_contexts"], list):
                raise RuntimeError(f"{path}:{line_no} retrieved_contexts 不是列表")
            rows.append(row)
    return rows


def _value(result) -> float | None:
    if result is None:
        return None
    value = getattr(result, "value", result)
    if value is None:
        return None
    return float(value)


async def _score_one(name: str, coro) -> tuple[str, float | None, str | None]:
    try:
        return name, _value(await coro), None
    except Exception as exc:
        return name, None, str(exc)


async def score_row(row: dict, scorers: dict) -> dict[str, float | None]:
    user_input = row["user_input"]
    response = row["response"]
    reference = row["reference"]
    contexts = [str(c) for c in row["retrieved_contexts"] if str(c).strip()]

    results = await asyncio.gather(
        _score_one(
            "Context Precision",
            scorers["context_precision"].ascore(
                user_input=user_input, reference=reference, retrieved_contexts=contexts
            ),
        ),
        _score_one(
            "Context Recall",
            scorers["context_recall"].ascore(
                user_input=user_input, reference=reference, retrieved_contexts=contexts
            ),
        ),
        _score_one(
            "Answer Relevancy",
            scorers["answer_relevancy"].ascore(user_input=user_input, response=response),
        ),
        _score_one(
            "Faithfulness",
            scorers["faithfulness"].ascore(
                user_input=user_input, response=response, retrieved_contexts=contexts
            ),
        ),
        _score_one(
            "Answer Correctness",
            scorers["answer_correctness"].ascore(
                user_input=user_input, response=response, reference=reference
            ),
        ),
    )
    scores: dict[str, float | None] = {}
    for name, value, error in results:
        scores[name] = value
        if error:
            print(f"    {name} 失败: {error}", file=sys.stderr)
    return scores


def mean(values: list[float | None]) -> float | None:
    nums = [v for v in values if v is not None]
    return statistics.mean(nums) if nums else None


async def run(rows: list[dict], output: Path) -> None:
    # LLM（DeepSeek 等）与 embedding（DashScope text-embedding-v4）分 client，避免共用 BASE_URL。
    llm_client = AsyncOpenAI(
        api_key=os.getenv("OPENAI_API_KEY"),
        base_url=os.getenv("OPENAI_BASE_URL") or None,
    )
    embedding_client = AsyncOpenAI(
        api_key=os.getenv("EMBEDDING_API_KEY"),
        base_url=os.getenv("EMBEDDING_BASE_URL") or None,
    )
    eval_llm = llm_factory(
        os.getenv("RAGAS_LLM_MODEL", "deepseek-v4-pro"),
        client=llm_client,
        temperature=0,
        seed=42,
        extra_body={"thinking": {"type": "disabled"}},
        max_tokens=10240,
    )
    embeddings = embedding_factory(
        "openai",
        model=os.getenv("EMBEDDING_MODEL", "text-embedding-v4"),
        client=embedding_client,
    )
    scorers = {
        "context_precision": ContextPrecision(llm=eval_llm),
        "context_recall": ContextRecall(llm=eval_llm),
        "answer_relevancy": AnswerRelevancy(llm=eval_llm, embeddings=embeddings),
        "faithfulness": Faithfulness(llm=eval_llm),
        "answer_correctness": AnswerCorrectness(llm=eval_llm, embeddings=embeddings),
    }

    items: list[dict] = []
    output.parent.mkdir(parents=True, exist_ok=True)
    for i, row in enumerate(rows, start=1):
        query = row["user_input"]
        print(f"[{i}/{len(rows)}] {query[:60]}{'...' if len(query) > 60 else ''}")
        scores = await score_row(row, scorers)
        for name in METRICS:
            print(f"  {name}={scores[name]}")
        items.append({"user_input": query, **scores})
        payload = {
            "mean": {name: mean([item[name] for item in items]) for name in METRICS},
            "items": items,
        }
        output.write_text(
            json.dumps(payload, ensure_ascii=False, indent=2) + "\n",
            encoding="utf-8",
        )

    print("\n均值")
    for name in METRICS:
        print(f"  {name}={mean([item[name] for item in items])}")
    print(f"写出 -> {output}")


def main(argv: list[str] | None = None) -> int:
    load_dotenv()
    parser = argparse.ArgumentParser(
        description="用 RAGAS 评测 generate_dataset 产出的样本（默认最新 run）"
    )
    parser.add_argument(
        "--input",
        type=Path,
        default=None,
        help="generated.jsonl；默认取 data/runs 下最新一次",
    )
    parser.add_argument(
        "--output",
        type=Path,
        default=None,
        help="scores.json；默认写到与 --input 同目录",
    )
    parser.add_argument("--limit", type=int, default=0, help="只评前 N 条，0 表示全部")
    args = parser.parse_args(argv)

    migrated = migrate_legacy_flat_files()
    if migrated is not None:
        print(f"已将旧扁平结果迁移到 {migrated}")

    try:
        input_path = args.input or resolve_latest_generated()
    except FileNotFoundError as exc:
        print(str(exc), file=sys.stderr)
        return 1

    if not input_path.is_file():
        print(f"输入文件不存在: {input_path}", file=sys.stderr)
        return 1
    if not os.getenv("OPENAI_API_KEY"):
        print("缺少 OPENAI_API_KEY", file=sys.stderr)
        return 1
    if not os.getenv("EMBEDDING_API_KEY") or not os.getenv("EMBEDDING_BASE_URL"):
        print("缺少 EMBEDDING_API_KEY / EMBEDDING_BASE_URL（Answer Relevancy/Correctness 需要）", file=sys.stderr)
        return 1

    output_path = args.output or default_scores_for(input_path)

    rows = load_jsonl(input_path)
    if args.limit > 0:
        rows = rows[: args.limit]
    print(f"评测 {len(rows)} 条 -> {input_path}")
    print(f"分数写出 -> {output_path}")
    asyncio.run(run(rows, output_path))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
