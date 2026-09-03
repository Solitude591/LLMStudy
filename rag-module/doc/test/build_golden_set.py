#!/usr/bin/env python3
"""Assemble the Golden Set from manually curated QA pairs.

This file deliberately does not extract PDF sentences or generate questions. The
human-readable source of every question and reference answer lives in
``test/curated/*.jsonl``. This utility only checks coverage, applies those reviewed
pairs to the evidence-bearing records, creates the deterministic dev/test split,
and refreshes summary hashes.
"""

from __future__ import annotations

import hashlib
import json
import re
from collections import Counter
from datetime import datetime, timezone
from pathlib import Path


HERE = Path(__file__).resolve().parent
CURATED_DIR = HERE / "curated"
CATEGORY_FILES = {
    "fact": "fact.jsonl",
    "table": "table.jsonl",
    "cross_section": "cross_section.jsonl",
    "cross_document": "cross_document.jsonl",
    "ambiguous": "ambiguous.jsonl",
    "unanswerable": "unanswerable.jsonl",
}
EXPECTED_COUNTS = {
    "fact": 70,
    "table": 32,
    "cross_section": 35,
    "cross_document": 35,
    "ambiguous": 25,
    "unanswerable": 25,
}

# These phrases came from the discarded extraction/template version.  Keep the
# assembler strict so a later rebuild cannot silently reintroduce them.
LEGACY_QUESTION_PATTERNS = (
    re.compile(r"第\s*\d+\s*页.*(?:明确说|具体陈述|表述了什么)"),
    re.compile(r"what main contribution or finding does .* state", re.I),
    re.compile(r"which specific statement on page \d+", re.I),
    re.compile(r"according to the table caption or explanation", re.I),
    re.compile(r"家庭住址|门牌号|序列号|电费账单"),
)


def load_jsonl(path: Path) -> list[dict]:
    return [
        json.loads(line)
        for line in path.read_text(encoding="utf-8").splitlines()
        if line.strip()
    ]


def write_jsonl(path: Path, rows: list[dict]) -> None:
    with path.open("w", encoding="utf-8") as stream:
        for row in rows:
            stream.write(json.dumps(row, ensure_ascii=False) + "\n")


def split_rows(rows: list[dict]) -> tuple[list[dict], list[dict]]:
    dev: list[dict] = []
    test: list[dict] = []
    category_index: Counter[str] = Counter()
    for row in rows:
        category = row["primary_category"]
        category_index[category] += 1
        number = category_index[category]
        if category == "fact":
            target = test if number % 2 == 1 else dev
        else:
            target = test if number % 5 in (1, 4) else dev
        target.append(row)
    return dev, test


def question_language(question: str) -> str:
    chinese = sum("\u4e00" <= char <= "\u9fff" for char in question)
    latin = sum(char.isascii() and char.isalpha() for char in question)
    if chinese and latin >= 8:
        return "mixed"
    return "zh" if chinese else "en"


def load_curated() -> dict[str, dict]:
    curated: dict[str, dict] = {}
    for category, filename in CATEGORY_FILES.items():
        rows = load_jsonl(CURATED_DIR / filename)
        if len(rows) != EXPECTED_COUNTS[category]:
            raise RuntimeError(
                f"{filename} expected {EXPECTED_COUNTS[category]} rows, got {len(rows)}"
            )
        for row in rows:
            question_id = row.get("question_id")
            if not question_id or question_id in curated:
                raise RuntimeError(f"duplicate or missing curated question_id: {question_id}")
            if not row.get("user_input") or not row.get("reference"):
                raise RuntimeError(f"incomplete curated QA pair: {question_id}")
            for pattern in LEGACY_QUESTION_PATTERNS:
                if pattern.search(str(row["user_input"])):
                    raise RuntimeError(
                        f"legacy/template-like question rejected: {question_id}"
                    )
            curated[question_id] = row
    return curated


def merge_curated_row(base: dict, pair: dict) -> dict:
    """Question/answer edits must explicitly carry reviewed evidence, never stale labels."""
    changed = any(base.get(key) != pair.get(key) for key in ("user_input", "reference"))
    reviewed = pair.get("relevant_evidence")
    if base.get("is_answerable") and changed and not reviewed:
        raise RuntimeError(f"{base['question_id']}: QA changed without reviewed relevant_evidence")
    if reviewed is not None and base.get("is_answerable") and not reviewed:
        raise RuntimeError(f"{base['question_id']}: answerable question requires nonempty evidence")
    row = dict(base)
    row.update(user_input=pair["user_input"], reference=pair["reference"],
               language=question_language(pair["user_input"]), curation="manual-paper-review")
    if reviewed is not None:
        row["relevant_evidence"] = reviewed
        row["reference_contexts"] = [item["evidence_quote"] for item in reviewed]
        row["evidence_review"] = pair.get("evidence_review")
    return row


def main() -> int:
    all_path = HERE / "golden-set-all.jsonl"
    base_rows = load_jsonl(all_path)
    curated = load_curated()
    base_ids = [row.get("question_id") for row in base_rows]
    if len(base_ids) != 222 or len(set(base_ids)) != 222:
        raise RuntimeError("golden-set-all must contain 222 unique evidence-bearing records")
    if set(base_ids) != set(curated):
        missing = sorted(set(base_ids) - set(curated))
        extra = sorted(set(curated) - set(base_ids))
        raise RuntimeError(f"curated ID mismatch: missing={missing}, extra={extra}")

    rows: list[dict] = []
    for base in base_rows:
        pair = curated[base["question_id"]]
        rows.append(merge_curated_row(base, pair))

    counts = Counter(row["primary_category"] for row in rows)
    if counts != Counter(EXPECTED_COUNTS):
        raise RuntimeError(f"unexpected category distribution: {counts}")

    dev, test = split_rows(rows)
    write_jsonl(all_path, rows)
    write_jsonl(HERE / "golden-set-dev.jsonl", dev)
    write_jsonl(HERE / "golden-set-test.jsonl", test)

    document_coverage = Counter()
    for row in rows:
        if row["is_answerable"]:
            document_coverage.update(set(row.get("evidence_corpus_ids") or []))
    summary = {
        "created_at": datetime.now(timezone.utc).astimezone().isoformat(),
        "curation_method": "manual-paper-review; scripts only assemble and validate",
        "question_count": len(rows),
        "answerable_count": sum(row["is_answerable"] for row in rows),
        "unanswerable_count": sum(not row["is_answerable"] for row in rows),
        "category_counts": dict(counts),
        "language_counts": dict(Counter(row["language"] for row in rows)),
        "difficulty_counts": dict(Counter(row["difficulty"] for row in rows)),
        "topic_counts": dict(Counter(row["topic"] for row in rows)),
        "document_answerable_coverage": dict(sorted(document_coverage.items())),
        "split_counts": {"dev": len(dev), "test": len(test)},
        "split_sha256": {
            name: hashlib.sha256((HERE / name).read_bytes()).hexdigest()
            for name in (
                "golden-set-all.jsonl",
                "golden-set-dev.jsonl",
                "golden-set-test.jsonl",
            )
        },
    }
    (HERE / "golden-set-summary.json").write_text(
        json.dumps(summary, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )
    print(json.dumps(summary, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
