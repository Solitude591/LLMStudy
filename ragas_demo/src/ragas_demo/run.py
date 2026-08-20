"""依次执行 generate_dataset 与 evaluate，写入同一次 run。"""

from __future__ import annotations

import argparse
import os
from pathlib import Path

from dotenv import load_dotenv

from ragas_demo import evaluate, generate_dataset
from ragas_demo.generate_dataset import DEFAULT_INPUT
from ragas_demo.paths import resolve_latest_generated


def main(argv: list[str] | None = None) -> int:
    load_dotenv()
    parser = argparse.ArgumentParser(
        description="先调用 /dataset/generate 生成样本，再用 RAGAS 评测（同一次 run）"
    )
    parser.add_argument("--input", type=Path, default=DEFAULT_INPUT, help="金标 JSONL 路径")
    parser.add_argument(
        "--base-url",
        default=os.getenv("RAG_BASE_URL", "http://localhost:8080"),
        help="rag-module 服务地址",
    )
    parser.add_argument("--limit", type=int, default=0, help="只处理前 N 条，0 表示全部")
    args = parser.parse_args(argv)

    code = generate_dataset.main(
        [
            "--input",
            str(args.input),
            "--base-url",
            args.base_url,
            "--limit",
            str(args.limit),
        ]
    )
    if code != 0:
        return code

    generated = resolve_latest_generated()
    return evaluate.main(
        [
            "--input",
            str(generated),
            "--limit",
            str(args.limit),
        ]
    )


if __name__ == "__main__":
    raise SystemExit(main())
