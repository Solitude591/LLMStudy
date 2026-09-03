"""按题型分层抽样 generated.jsonl，用于在预算受限时只对子集跑 RAGAS。

抽样是确定性的：按 question_id 排序后在每个 primary_category 内等距取样，
所以同一份输入永远得到同一批题，基线和优化后可以在完全相同的题目上对比。

用法：
    uv run python -m ragas_demo.sample_generated \
        --input data/runs/<run>/generated.jsonl \
        --output data/runs/<run>/generated-sample40.jsonl \
        --size 40
"""

from __future__ import annotations

import argparse
import json
import sys
from collections import defaultdict
from pathlib import Path


def stratified(rows: list[dict], size: int) -> list[dict]:
    """在每个题型内等距取样，配额按题型占比分配，余数给最大的题型。"""
    buckets: dict[str, list[dict]] = defaultdict(list)
    for row in rows:
        buckets[str(row.get("primary_category") or "unknown")].append(row)
    for bucket in buckets.values():
        bucket.sort(key=lambda row: str(row.get("question_id") or ""))

    total = len(rows)
    quota = {name: max(1, round(size * len(bucket) / total))
             for name, bucket in buckets.items()}
    # round 之后配额可能对不上目标数量，从最大的题型上加减补平。
    order = sorted(buckets, key=lambda name: -len(buckets[name]))
    while sum(quota.values()) != size:
        step = 1 if sum(quota.values()) < size else -1
        for name in order:
            if 1 <= quota[name] + step <= len(buckets[name]):
                quota[name] += step
                break
        else:
            break

    picked: list[dict] = []
    for name, bucket in buckets.items():
        take = min(quota[name], len(bucket))
        if take <= 0:
            continue
        # 等距取样而不是取前 N 条：题号往往按章节聚集，取前 N 会偏向同几篇论文。
        stride = len(bucket) / take
        picked.extend(bucket[int(index * stride)] for index in range(take))
    picked.sort(key=lambda row: str(row.get("question_id") or ""))
    return picked


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--size", type=int, default=40)
    args = parser.parse_args(argv)

    if not args.input.is_file():
        print(f"输入文件不存在: {args.input}", file=sys.stderr)
        return 2
    rows = [json.loads(line) for line in
            args.input.read_text(encoding="utf-8").splitlines() if line.strip()]
    if args.size < 1 or args.size > len(rows):
        print(f"--size 必须在 1..{len(rows)} 之间", file=sys.stderr)
        return 2

    picked = stratified(rows, args.size)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    with args.output.open("w", encoding="utf-8") as stream:
        for row in picked:
            stream.write(json.dumps(row, ensure_ascii=False) + "\n")

    counts: dict[str, int] = defaultdict(int)
    for row in picked:
        counts[str(row.get("primary_category") or "unknown")] += 1
    print(f"抽样 {len(picked)} / {len(rows)} 条 -> {args.output}")
    for name in sorted(counts):
        print(f"  {name}: {counts[name]}")
    print("question_ids:", ",".join(str(row.get("question_id")) for row in picked))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
