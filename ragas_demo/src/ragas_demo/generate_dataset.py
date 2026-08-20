"""从金标 JSONL 读取 user_input，调用 POST /dataset/generate 补齐 RAG 回答与检索上下文。"""

from __future__ import annotations

import argparse
import json
import os
import sys
import urllib.error
import urllib.request
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
    / "medical-image-segmentation-ragas-qa.jsonl"
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


def generate(base_url: str, query: str) -> dict:
    data = _api_post(
        f"{base_url.rstrip('/')}/dataset/generate",
        {"query": query},
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

    with args.output.open("w", encoding="utf-8") as out:
        for i, row in enumerate(rows, start=1):
            query = row["user_input"].strip()
            print(f"[{i}/{len(rows)}] {query[:60]}{'...' if len(query) > 60 else ''}")
            try:
                generated = generate(args.base_url, query)
            except Exception as exc:
                print(f"  失败: {exc}", file=sys.stderr)
                return 1

            merged = {
                **row,
                "response": generated["response"],
                "retrieved_contexts": generated["retrieved_contexts"],
            }
            out.write(json.dumps(merged, ensure_ascii=False) + "\n")
            out.flush()
            print(f"  ok: contexts={len(generated['retrieved_contexts'])}")

    print("完成")
    print(f"评测请指定: --input {args.output}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
