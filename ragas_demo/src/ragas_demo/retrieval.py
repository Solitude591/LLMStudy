"""Evaluate Hit Rate/MRR and latency through the real RagPipeline diagnose API."""

from __future__ import annotations

import argparse
import json
import os
import socket
import sys
import threading
import time
import urllib.error
import urllib.request
from concurrent.futures import ThreadPoolExecutor, as_completed
from pathlib import Path

from dotenv import load_dotenv

from ragas_demo.metrics import (
    aggregate_retrieval,
    is_refusal,
    latency_summary,
    score_answerable,
)
from ragas_demo.validate_dataset import load_jsonl

_OPENER = urllib.request.build_opener(urllib.request.ProxyHandler({}))
STAGES = (
    "query_rewrite",
    "parallel_recall",
    "rrf_parent_grouping",
    "bge_rerank",
    "parent_expand_selection",
    "total_retrieval",
    "retrieval_api_roundtrip",
    "dataset_api",
)


def _post(url: str, body: dict, timeout: float) -> tuple[dict, float]:
    request = urllib.request.Request(
        url,
        data=json.dumps(body, ensure_ascii=False).encode("utf-8"),
        headers={"Content-Type": "application/json"},
        method="POST",
    )
    started = time.perf_counter_ns()
    with _OPENER.open(request, timeout=timeout) as response:
        payload = json.loads(response.read().decode("utf-8"))
    elapsed_ms = (time.perf_counter_ns() - started) / 1_000_000
    if not isinstance(payload, dict):
        raise RuntimeError(f"diagnose response is not an object: {payload!r}")
    return payload, elapsed_ms


def diagnose(base_url: str, query: str, timeout: float, retries: int = 3,
             conversation_context: str = "无") -> tuple[dict, float, int]:
    url = f"{base_url.rstrip('/')}/dev/rag/retrieval/diagnose"
    last_error: Exception | None = None
    for attempt in range(1, max(1, retries) + 1):
        try:
            payload, elapsed_ms = _post(
                url,
                {"query": query, "conversationContext": conversation_context, "includeText": False},
                timeout,
            )
            return payload, elapsed_ms, attempt - 1
        except (urllib.error.URLError, urllib.error.HTTPError, TimeoutError, socket.timeout) as exc:
            last_error = exc
            if attempt < max(1, retries):
                time.sleep(min(2 ** (attempt - 1), 4))
    assert last_error is not None
    raise last_error


def _timings(payload: dict, roundtrip_ms: float | None, dataset_api_ms: object) -> dict[str, float]:
    raw = payload.get("timings") if isinstance(payload.get("timings"), dict) else {}
    stages = {
        "query_rewrite": raw.get("queryRewriteMs"),
        "parallel_recall": raw.get("parallelRecallMs"),
        "rrf_parent_grouping": raw.get("rrfParentGroupingMs"),
        "bge_rerank": raw.get("bgeRerankMs", payload.get("bgeElapsedMs")),
        "parent_expand_selection": raw.get("parentExpandSelectionMs"),
        "total_retrieval": raw.get("totalRetrievalMs"),
        "retrieval_api_roundtrip": roundtrip_ms,
        "dataset_api": dataset_api_ms,
    }
    return {name: float(value) for name, value in stages.items() if isinstance(value, (int, float))}


def evaluate_one(
    index: int,
    row: dict,
    base_url: str,
    retrieval_k: int,
    timeout: float,
    retries: int,
) -> tuple[dict, dict]:
    question_id = str(row.get("question_id") or f"row-{index:04d}")
    started = time.perf_counter_ns()
    try:
        payload = row.get("retrieval_diagnostics")
        if isinstance(payload, dict):
            roundtrip_ms, retry_count = None, 0
            retrieval_source = "dataset-generation-request"
        else:
            payload, roundtrip_ms, retry_count = diagnose(
                base_url, str(row["user_input"]), timeout, retries,
                str(row.get("conversation_context") or "无"),
            )
            retrieval_source = "separate-diagnose-request"
        hits = payload.get("finalCandidates")
        if not isinstance(hits, list):
            raise RuntimeError("diagnose response lacks finalCandidates")
        if row.get("is_answerable") is True:
            scored = score_answerable(row, hits, retrieval_k)
        else:
            scored = {
                "scorable": False,
                "unscorable_reason": "unanswerable-excluded",
                "first_relevant_rank": None,
                "hit": None,
                "reciprocal_rank": None,
                "refusal_correct": (
                    is_refusal(row.get("response")) if row.get("response") is not None else None
                ),
            }
        detail = {
            "question_id": question_id,
            "primary_category": row.get("primary_category"),
            "topic": row.get("topic"),
            "language": row.get("language"),
            "is_answerable": row.get("is_answerable"),
            "user_input": row.get("user_input"),
            "top_k": hits[: max(1, retrieval_k)],
            **scored,
            "trace_id": payload.get("traceId"),
            "success": True,
            "retry_count": retry_count,
            "retrieval_source": retrieval_source,
            "diagnostic_snapshot": payload,
        }
        latency = {
            "question_id": question_id,
            "success": True,
            "timeout": False,
            "retry_count": retry_count,
            "stages_ms": _timings(payload, roundtrip_ms, row.get("dataset_api_latency_ms")),
            "bm25_degraded": payload.get("bm25Degraded") is True,
            "knn_degraded": payload.get("knnDegraded") is True,
            "bge_degraded": payload.get("bgeUsed") is not True,
            "bge_reason": payload.get("bgeReason"),
        }
        return detail, latency
    except Exception as exc:
        elapsed_ms = (time.perf_counter_ns() - started) / 1_000_000
        timeout_failure = isinstance(exc, (TimeoutError, socket.timeout)) or "timed out" in str(exc).casefold()
        detail = {
            "question_id": question_id,
            "primary_category": row.get("primary_category"),
            "topic": row.get("topic"),
            "language": row.get("language"),
            "is_answerable": row.get("is_answerable"),
            "user_input": row.get("user_input"),
            "top_k": [],
            "scorable": False,
            "unscorable_reason": "request-failed",
            "first_relevant_rank": None,
            "hit": None,
            "reciprocal_rank": None,
            "success": False,
            "error": str(exc),
        }
        latency = {
            "question_id": question_id,
            "success": False,
            "timeout": timeout_failure,
            "stages_ms": {"retrieval_api_roundtrip": elapsed_ms},
            "bm25_degraded": False,
            "knn_degraded": False,
            "bge_degraded": False,
            "error": str(exc),
        }
        return detail, latency


def _write_jsonl(path: Path, rows: list[dict]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8") as stream:
        for row in rows:
            stream.write(json.dumps(row, ensure_ascii=False) + "\n")


def _load_jsonl(path: Path) -> list[dict]:
    if not path.is_file():
        return []
    return [
        json.loads(line)
        for line in path.read_text(encoding="utf-8").splitlines()
        if line.strip()
    ]


def _question_id(row: dict, index: int) -> str:
    return str(row.get("question_id") or f"row-{index:04d}")


def run_retrieval(
    rows: list[dict],
    output_dir: Path,
    base_url: str,
    retrieval_k: int = 5,
    warmup: int = 0,
    concurrency: int = 1,
    timeout: float = 300.0,
    retries: int = 3,
) -> tuple[dict, dict]:
    output_dir.mkdir(parents=True, exist_ok=True)
    details_path = output_dir / "retrieval-details.jsonl"
    latency_path = output_dir / "latency-details.jsonl"
    existing_details = {
        str(row.get("question_id")): row
        for row in _load_jsonl(details_path)
        if row.get("success") is True and row.get("question_id")
    }
    existing_latency = {
        str(row.get("question_id")): row
        for row in _load_jsonl(latency_path)
        if row.get("success") is True and row.get("question_id")
    }

    details: list[dict | None] = [None] * len(rows)
    latency_details: list[dict | None] = [None] * len(rows)
    pending: list[int] = []
    for index, row in enumerate(rows):
        question_id = _question_id(row, index + 1)
        if question_id in existing_details:
            details[index] = existing_details[question_id]
            latency_details[index] = existing_latency.get(
                question_id, existing_details[question_id]
            )
        else:
            pending.append(index)
    if existing_details:
        print(
            f"resume: kept {len(existing_details)} successful retrieval rows, "
            f"{len(pending)} remaining"
        )

    warmup_pending = [index for index in pending if not isinstance(rows[index].get("retrieval_diagnostics"), dict)]
    if warmup_pending and warmup > 0:
        print(f"warmup={warmup} (excluded from percentiles)")
        for _ in range(warmup):
            warmup_row = rows[warmup_pending[0]]
            diagnose(base_url, str(warmup_row["user_input"]), timeout, retries,
                     str(warmup_row.get("conversation_context") or "无"))

    lock = threading.Lock()
    finished = len(rows) - len(pending)

    def persist() -> None:
        _write_jsonl(details_path, [row for row in details if row is not None])
        _write_jsonl(latency_path, [row for row in latency_details if row is not None])

    def work(index: int) -> dict:
        nonlocal finished
        detail, latency = evaluate_one(
            index + 1, rows[index], base_url, retrieval_k, timeout, retries
        )
        with lock:
            details[index] = detail
            latency_details[index] = latency
            persist()
            finished += 1
            print(f"[{finished}/{len(rows)}] {detail['question_id']} success={detail['success']}")
        return detail

    if pending:
        with ThreadPoolExecutor(max_workers=max(1, concurrency)) as executor:
            futures = [executor.submit(work, index) for index in pending]
            for future in as_completed(futures):
                future.result()

    details_out = [row for row in details if row is not None]
    latency_out = [row for row in latency_details if row is not None]
    retrieval_metrics = aggregate_retrieval(details_out, retrieval_k)
    retrieval_metrics["request_success"] = sum(1 for row in details_out if row["success"])
    retrieval_metrics["request_failure"] = sum(1 for row in details_out if not row["success"])
    latency_metrics = latency_summary(latency_out, STAGES)
    latency_metrics["warmup"] = warmup
    latency_metrics["concurrency"] = max(1, concurrency)
    latency_metrics["timeout_seconds"] = timeout
    latency_metrics["retrieval_k"] = retrieval_k

    persist()
    (output_dir / "retrieval-metrics.json").write_text(
        json.dumps(retrieval_metrics, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )
    (output_dir / "latency-metrics.json").write_text(
        json.dumps(latency_metrics, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )
    return retrieval_metrics, latency_metrics


def main(argv: list[str] | None = None) -> int:
    load_dotenv()
    parser = argparse.ArgumentParser(description="Evaluate retrieval and latency through RagPipeline")
    parser.add_argument("--input", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True, help="output run directory")
    parser.add_argument("--base-url", default=os.getenv("RAG_BASE_URL", "http://localhost:8080"))
    parser.add_argument("--retrieval-k", type=int, default=5)
    parser.add_argument("--limit", type=int, default=0)
    parser.add_argument("--warmup", type=int, default=0)
    parser.add_argument("--concurrency", type=int, default=1)
    parser.add_argument("--timeout", type=float, default=300.0)
    parser.add_argument("--retries", type=int, default=3)
    args = parser.parse_args(argv)
    if not args.input.is_file():
        print(f"input not found: {args.input}", file=sys.stderr)
        return 2
    if args.retrieval_k < 1 or args.concurrency < 1 or args.warmup < 0:
        print("retrieval-k/concurrency must be positive and warmup non-negative", file=sys.stderr)
        return 2
    rows = load_jsonl(args.input)
    if args.limit > 0:
        rows = rows[: args.limit]
    try:
        run_retrieval(
            rows, args.output, args.base_url, args.retrieval_k,
            args.warmup, args.concurrency, args.timeout, args.retries,
        )
    except Exception as exc:
        print(str(exc), file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
