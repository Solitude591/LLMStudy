"""读取 generate_dataset 产出的 JSONL，用 RAGAS 五个指标逐条打分。"""

from __future__ import annotations

import argparse
import asyncio
import json
import os
import statistics
import sys
from collections import defaultdict
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


async def _score_one(name: str, factory, retries: int) -> tuple[str, float | None, str | None]:
    last_error: Exception | None = None
    for attempt in range(1, max(1, retries) + 1):
        try:
            return name, _value(await factory()), None
        except Exception as exc:
            last_error = exc
            if attempt < max(1, retries):
                await asyncio.sleep(min(2 ** (attempt - 1), 4))
    assert last_error is not None
    return name, None, str(last_error)


async def score_row(
    row: dict,
    scorers: dict,
    existing: dict[str, float | None] | None = None,
    retries: int = 3,
) -> dict[str, float | None]:
    user_input = row["user_input"]
    response = row["response"]
    reference = row["reference"]
    contexts = [str(c) for c in row["retrieved_contexts"] if str(c).strip()]

    factories = {
        "Context Precision": lambda: scorers["context_precision"].ascore(
            user_input=user_input, reference=reference, retrieved_contexts=contexts
        ),
        "Context Recall": lambda: scorers["context_recall"].ascore(
            user_input=user_input, reference=reference, retrieved_contexts=contexts
        ),
        "Answer Relevancy": lambda: scorers["answer_relevancy"].ascore(
            user_input=user_input, response=response
        ),
        "Faithfulness": lambda: scorers["faithfulness"].ascore(
            user_input=user_input, response=response, retrieved_contexts=contexts
        ),
        "Answer Correctness": lambda: scorers["answer_correctness"].ascore(
            user_input=user_input, response=response, reference=reference
        ),
    }
    scores: dict[str, float | None] = {
        name: (existing or {}).get(name) for name in METRICS
    }
    pending = [name for name in METRICS if scores[name] is None]
    results = await asyncio.gather(*(
        _score_one(name, factories[name], retries) for name in pending
    ))
    for name, value, error in results:
        scores[name] = value
        if error:
            print(f"    {name} 失败: {error}", file=sys.stderr)
    return scores


def mean(values: list[float | None]) -> float | None:
    nums = [v for v in values if v is not None]
    return statistics.mean(nums) if nums else None


def result_payload(items: list[dict]) -> dict:
    grouped: dict[str, list[dict]] = defaultdict(list)
    for item in items:
        grouped[str(item.get("primary_category") or "unknown")].append(item)
    return {
        "mean": {name: mean([item.get(name) for item in items]) for name in METRICS},
        "by_primary_category": {
            category: {
                "count": len(rows),
                **{name: mean([row.get(name) for row in rows]) for name in METRICS},
            }
            for category, rows in sorted(grouped.items())
        },
        "metric_completion": {
            name: {
                "success": sum(item.get(name) is not None for item in items),
                "failure": sum(item.get(name) is None for item in items),
            }
            for name in METRICS
        },
        "items": items,
    }


async def run(rows: list[dict], output: Path, retries: int = 3) -> None:
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

    existing_items: dict[str, dict] = {}
    if output.is_file():
        try:
            payload = json.loads(output.read_text(encoding="utf-8"))
            for item in payload.get("items", []):
                key = str(item.get("question_id") or item.get("user_input") or "")
                if key:
                    existing_items[key] = item
        except (json.JSONDecodeError, OSError, AttributeError):
            existing_items = {}
    items_by_key: dict[str, dict] = dict(existing_items)
    output.parent.mkdir(parents=True, exist_ok=True)
    for i, row in enumerate(rows, start=1):
        query = row["user_input"]
        key = str(row.get("question_id") or query)
        print(f"[{i}/{len(rows)}] {query[:60]}{'...' if len(query) > 60 else ''}")
        previous = items_by_key.get(key, {})
        if all(previous.get(name) is not None for name in METRICS):
            print("  resume: all five metrics already scored")
            continue
        scores = await score_row(row, scorers, previous, retries)
        for name in METRICS:
            print(f"  {name}={scores[name]}")
        items_by_key[key] = {
            "question_id": row.get("question_id"),
            "primary_category": row.get("primary_category"),
            "user_input": query,
            **scores,
        }
        items = [
            items_by_key[str(source.get("question_id") or source["user_input"])]
            for source in rows
            if str(source.get("question_id") or source["user_input"]) in items_by_key
        ]
        payload = result_payload(items)
        output.write_text(
            json.dumps(payload, ensure_ascii=False, indent=2) + "\n",
            encoding="utf-8",
        )

    items = [
        items_by_key[str(source.get("question_id") or source["user_input"])]
        for source in rows
        if str(source.get("question_id") or source["user_input"]) in items_by_key
    ]
    output.write_text(
        json.dumps(result_payload(items), ensure_ascii=False, indent=2) + "\n",
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
    parser.add_argument("--retries", type=int, default=3, help="单指标暂时性失败最大尝试次数")
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
    asyncio.run(run(rows, output_path, retries=args.retries))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
