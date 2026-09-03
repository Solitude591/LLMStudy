"""从金标 JSONL 读取 user_input，调用 POST /dataset/generate 补齐 RAG 回答与检索上下文。"""

from __future__ import annotations

import argparse
import json
import os
import socket
import sys
import time
import urllib.error
import urllib.request
from concurrent.futures import ThreadPoolExecutor, as_completed
from pathlib import Path

from dotenv import load_dotenv

from ragas_demo.paths import (
    create_run_dir,
    generated_path,
    migrate_legacy_flat_files,
)

REPO_ROOT = Path(__file__).resolve().parents[3]
DEFAULT_INPUT = (
    REPO_ROOT
    / "rag-module"
    / "doc"
    / "test"
    / "golden-set-dev.jsonl"
)

# 直连 rag-module；urllib 默认会走 macOS 系统代理，localhost 常被打成 502。
_OPENER = urllib.request.build_opener(urllib.request.ProxyHandler({}))


def _api_post(url: str, body: dict, timeout: float = 300.0) -> dict:
    req = urllib.request.Request(
        url,
        data=json.dumps(body, ensure_ascii=False).encode("utf-8"),
        headers={"Content-Type": "application/json"},
        method="POST",
    )
    try:
        with _OPENER.open(req, timeout=timeout) as resp:
            payload = json.loads(resp.read().decode("utf-8"))
    except urllib.error.HTTPError as exc:
        detail = exc.read().decode("utf-8", errors="replace")
        raise RuntimeError(f"HTTP {exc.code} {url}: {detail}") from exc
    except urllib.error.URLError as exc:
        raise RuntimeError(f"请求失败 {url}: {exc}") from exc

    if not isinstance(payload, dict):
        raise RuntimeError(f"响应不是 JSON 对象: {payload!r}")
    if payload.get("code") != 0:
        raise RuntimeError(
            f"接口返回错误: code={payload.get('code')} message={payload.get('message')}"
        )
    data = payload.get("data")
    if not isinstance(data, dict):
        raise RuntimeError(f"响应 data 非法: {data!r}")
    return data


def generate(base_url: str, query: str, timeout: float = 300.0) -> dict:
    data = _api_post(
        f"{base_url.rstrip('/')}/dataset/generate",
        {"query": query},
        timeout=timeout,
    )
    response = data.get("response")
    chunks = data.get("chunks")
    if response is None:
        raise RuntimeError(f"generate 响应缺少 response: {data!r}")
    if not isinstance(chunks, list):
        raise RuntimeError(f"generate 响应 chunks 不是列表: {chunks!r}")
    return {
        "response": str(response),
        "retrieved_contexts": [str(c) for c in chunks],
        **({"retrieval_diagnostics": data["retrievalDiagnostics"]}
           if isinstance(data.get("retrievalDiagnostics"), dict) else {}),
    }


def load_jsonl(path: Path) -> list[dict]:
    rows: list[dict] = []
    with path.open("r", encoding="utf-8") as f:
        for line_no, line in enumerate(f, start=1):
            text = line.strip()
            if not text:
                continue
            try:
                row = json.loads(text)
            except json.JSONDecodeError as exc:
                raise RuntimeError(f"{path}:{line_no} JSON 解析失败") from exc
            if not isinstance(row, dict):
                raise RuntimeError(f"{path}:{line_no} 期望对象，得到 {type(row).__name__}")
            user_input = row.get("user_input")
            if not isinstance(user_input, str) or not user_input.strip():
                raise RuntimeError(f"{path}:{line_no} 缺少有效 user_input")
            rows.append(row)
    return rows


def _row_key(row: dict, index: int = 0) -> str:
    value = row.get("question_id") or row.get("user_input")
    return str(value) if value else f"row-{index:04d}"


def _generate_with_retry(
    base_url: str,
    row: dict,
    index: int,
    timeout: float,
    retries: int,
) -> tuple[dict | None, dict]:
    key = _row_key(row, index)
    attempt_latencies: list[float] = []
    total_started = time.perf_counter_ns()
    last_error: Exception | None = None
    for attempt in range(1, max(1, retries) + 1):
        started = time.perf_counter_ns()
        try:
            generated = generate(base_url, row["user_input"].strip(), timeout=timeout)
            attempt_latencies.append((time.perf_counter_ns() - started) / 1_000_000)
            total_ms = (time.perf_counter_ns() - total_started) / 1_000_000
            merged = {
                **row,
                "response": generated["response"],
                "retrieved_contexts": generated["retrieved_contexts"],
                "dataset_api_latency_ms": total_ms,
                "generation_retry_count": attempt - 1,
            }
            return merged, {
                "question_id": key,
                "success": True,
                "timeout": False,
                "retry_count": attempt - 1,
                "attempt_latency_ms": attempt_latencies,
                "stages_ms": {"dataset_api": total_ms},
            }
        except Exception as exc:
            attempt_latencies.append((time.perf_counter_ns() - started) / 1_000_000)
            last_error = exc
            if attempt < max(1, retries):
                time.sleep(min(2 ** (attempt - 1), 4))
    assert last_error is not None
    total_ms = (time.perf_counter_ns() - total_started) / 1_000_000
    timed_out = isinstance(last_error, (TimeoutError, socket.timeout)) or "timed out" in str(last_error).casefold()
    return None, {
        "question_id": key,
        "success": False,
        "timeout": timed_out,
        "retry_count": max(1, retries) - 1,
        "attempt_latency_ms": attempt_latencies,
        "stages_ms": {"dataset_api": total_ms},
        "error": str(last_error),
    }


def _write_jsonl(path: Path, rows: list[dict]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8") as stream:
        for row in rows:
            stream.write(json.dumps(row, ensure_ascii=False) + "\n")


def run_generation(
    rows: list[dict],
    output: Path,
    base_url: str,
    warmup: int = 0,
    concurrency: int = 1,
    timeout: float = 300.0,
    retries: int = 3,
    resume: bool = True,
) -> tuple[list[dict], list[dict]]:
    existing: dict[str, dict] = {}
    if resume and output.is_file():
        for index, row in enumerate(load_jsonl(output), start=1):
            if row.get("response") is not None and isinstance(row.get("retrieved_contexts"), list):
                existing[_row_key(row, index)] = row

    pending = [
        (index, row)
        for index, row in enumerate(rows, start=1)
        if _row_key(row, index) not in existing
    ]
    if rows and warmup > 0:
        print(f"warmup={warmup} (excluded from percentiles)")
        for _ in range(warmup):
            generate(base_url, rows[0]["user_input"].strip(), timeout=timeout)

    generated_by_key = dict(existing)
    latency_details: list[dict] = []
    with ThreadPoolExecutor(max_workers=max(1, concurrency)) as executor:
        futures = {
            executor.submit(
                _generate_with_retry, base_url, row, index, timeout, retries
            ): (index, row)
            for index, row in pending
        }
        for future in as_completed(futures):
            index, row = futures[future]
            generated, latency = future.result()
            latency_details.append(latency)
            if generated is not None:
                generated_by_key[_row_key(row, index)] = generated
            print(
                f"[{len(latency_details)}/{len(pending)}] {_row_key(row, index)} "
                f"success={latency['success']} retries={latency['retry_count']}"
            )
            ordered_partial = [
                generated_by_key[_row_key(source, source_index)]
                for source_index, source in enumerate(rows, start=1)
                if _row_key(source, source_index) in generated_by_key
            ]
            _write_jsonl(output, ordered_partial)

    ordered = [
        generated_by_key[_row_key(row, index)]
        for index, row in enumerate(rows, start=1)
        if _row_key(row, index) in generated_by_key
    ]
    _write_jsonl(output, ordered)
    return ordered, sorted(latency_details, key=lambda item: item["question_id"])


def main(argv: list[str] | None = None) -> int:
    load_dotenv()

    parser = argparse.ArgumentParser(
        description="调用 /dataset/generate 生成 Ragas 评测样本（每次写入新的 run 目录）"
    )
    parser.add_argument("--input", type=Path, default=DEFAULT_INPUT, help="金标 JSONL 路径")
    parser.add_argument(
        "--output",
        type=Path,
        default=None,
        help="输出 JSONL；默认写入 data/runs/<时间戳>/generated.jsonl",
    )
    parser.add_argument(
        "--base-url",
        default=os.getenv("RAG_BASE_URL", "http://localhost:8080"),
        help="rag-module 服务地址",
    )
    parser.add_argument("--limit", type=int, default=0, help="只处理前 N 条，0 表示全部")
    parser.add_argument("--warmup", type=int, default=0, help="预热请求数，不计入延迟分位数")
    parser.add_argument("--concurrency", type=int, default=1, help="低并发生成，默认串行")
    parser.add_argument("--timeout", type=float, default=300.0, help="单次 API 超时（秒）")
    parser.add_argument("--retries", type=int, default=3, help="暂时性失败最大尝试次数")
    parser.add_argument("--no-resume", action="store_true", help="不复用现有成功记录")
    args = parser.parse_args(argv)

    if not args.input.is_file():
        print(f"输入文件不存在: {args.input}", file=sys.stderr)
        return 1

    migrated = migrate_legacy_flat_files()
    if migrated is not None:
        print(f"已将旧扁平结果迁移到 {migrated}")

    if args.output is None:
        run_dir = create_run_dir()
        args.output = generated_path(run_dir)
        print(f"新建 run: {run_dir.name}")

    rows = load_jsonl(args.input)
    if args.limit > 0:
        rows = rows[: args.limit]

    args.output.parent.mkdir(parents=True, exist_ok=True)

    print(f"读取 {len(rows)} 条 -> {args.input}")
    print(f"调用 {args.base_url.rstrip('/')}/dataset/generate")
    print(f"写出 -> {args.output}")

    generated, latency_details = run_generation(
        rows,
        args.output,
        args.base_url,
        warmup=args.warmup,
        concurrency=args.concurrency,
        timeout=args.timeout,
        retries=args.retries,
        resume=not args.no_resume,
    )
    latency_path = args.output.with_name("generation-latency-details.jsonl")
    _write_jsonl(latency_path, latency_details)
    failures = [row for row in latency_details if not row["success"]]
    if failures:
        _write_jsonl(args.output.with_name("generation-failures.jsonl"), failures)

    print(f"完成: success={len(generated)} failure={len(failures)}")
    print(f"评测请指定: --input {args.output}")
    return 0 if not failures else 1


if __name__ == "__main__":
    raise SystemExit(main())
