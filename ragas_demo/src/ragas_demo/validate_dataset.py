"""Validate the evidence-annotated Golden Set before any external model calls."""

from __future__ import annotations

import argparse
import json
import re
import subprocess
import sys
import unicodedata
from collections import Counter, defaultdict
from pathlib import Path

from ragas_demo.metrics import is_refusal

ALLOWED_CATEGORIES = {
    "fact", "table", "cross_section", "cross_document", "ambiguous", "unanswerable"
}
MINIMUM_COUNTS = {
    "fact": 70,
    "table": 30,
    "cross_section": 35,
    "cross_document": 35,
    "ambiguous": 25,
    "unanswerable": 25,
}
REQUIRED_EVIDENCE = {
    "document_file", "document_title", "page_start", "page_end", "section", "evidence_quote"
}


def load_jsonl(path: Path) -> list[dict]:
    rows: list[dict] = []
    with path.open("r", encoding="utf-8") as stream:
        for line_no, line in enumerate(stream, start=1):
            if not line.strip():
                continue
            try:
                row = json.loads(line)
            except json.JSONDecodeError as exc:
                raise RuntimeError(f"{path}:{line_no}: invalid JSON") from exc
            if not isinstance(row, dict):
                raise RuntimeError(f"{path}:{line_no}: expected an object")
            rows.append(row)
    return rows


def _normalized(value: object) -> str:
    text = unicodedata.normalize("NFKC", "" if value is None else str(value))
    text = re.sub(r"-\s+", "", text)
    return re.sub(r"\s+", " ", text).strip().casefold()


def _question_tokens(question: str) -> set[str]:
    return set(re.findall(r"[a-z0-9]+|[\u4e00-\u9fff]", _normalized(question)))


def _language(question: str) -> str:
    latin = len(re.findall(r"[A-Za-z]", question))
    han = len(re.findall(r"[\u4e00-\u9fff]", question))
    if latin >= 20 and han == 0:
        return "en"
    if latin >= 10 and han >= 4:
        return "mixed"
    return "zh"


def load_manifest(path: Path | None) -> dict[str, dict]:
    if path is None:
        return {}
    records = load_jsonl(path)
    result: dict[str, dict] = {}
    for record in records:
        filename = str(record.get("filename") or Path(str(record.get("local_path", ""))).name)
        if filename:
            result[filename.casefold()] = record
    return result


def _pdf_path(record: dict, manifest_path: Path) -> Path:
    raw = Path(str(record.get("local_path", "")))
    if raw.is_absolute():
        return raw
    repo_root = manifest_path.resolve().parents[3]
    return repo_root / raw


def _page_text(pdf: Path, start: int, end: int, cache: dict[tuple[Path, int, int], str]) -> str:
    key = (pdf, start, end)
    if key not in cache:
        process = subprocess.run(
            ["pdftotext", "-f", str(start), "-l", str(end), "-raw", str(pdf), "-"],
            check=True,
            capture_output=True,
            text=True,
        )
        cache[key] = _normalized(process.stdout)
    return cache[key]


def validate_rows(
    rows: list[dict],
    manifest: dict[str, dict] | None = None,
    manifest_path: Path | None = None,
    check_quotes: bool = False,
    enforce_minimums: bool = True,
) -> dict:
    manifest = manifest or {}
    errors: list[str] = []
    warnings: list[str] = []
    ids: set[str] = set()
    category_counts: Counter[str] = Counter()
    document_counts: Counter[str] = Counter()
    language_counts: Counter[str] = Counter()
    topic_counts: Counter[str] = Counter()
    cache: dict[tuple[Path, int, int], str] = {}

    for index, row in enumerate(rows, start=1):
        prefix = f"row {index}"
        question_id = row.get("question_id")
        if not isinstance(question_id, str) or not question_id.strip():
            errors.append(f"{prefix}: missing question_id")
        elif question_id in ids:
            errors.append(f"{prefix}: duplicate question_id {question_id}")
        else:
            ids.add(question_id)

        category = row.get("primary_category")
        if category not in ALLOWED_CATEGORIES:
            errors.append(f"{prefix}: invalid primary_category {category!r}")
        else:
            category_counts[category] += 1

        for field in ("user_input", "reference"):
            if not isinstance(row.get(field), str) or not row[field].strip():
                errors.append(f"{prefix}: missing {field}")
        if not isinstance(row.get("is_answerable"), bool):
            errors.append(f"{prefix}: is_answerable must be boolean")
            continue

        question = str(row.get("user_input", ""))
        language = str(row.get("language") or _language(question))
        language_counts[language] += 1
        topic_counts[str(row.get("topic") or "unknown")] += 1
        evidence = row.get("relevant_evidence")
        if not isinstance(evidence, list):
            errors.append(f"{prefix}: relevant_evidence must be a list")
            continue

        if row["is_answerable"] and not evidence:
            errors.append(f"{prefix}: answerable question has no evidence")
        if not row["is_answerable"]:
            if evidence:
                errors.append(f"{prefix}: unanswerable question must not contain evidence")
            if not is_refusal(row.get("reference")):
                errors.append(f"{prefix}: unanswerable reference lacks refusal semantics")
            continue

        evidence_documents: set[str] = set()
        evidence_locations: set[tuple[str, int, str]] = set()
        for evidence_index, item in enumerate(evidence, start=1):
            location = f"{prefix} evidence {evidence_index}"
            if not isinstance(item, dict):
                errors.append(f"{location}: expected object")
                continue
            missing = sorted(field for field in REQUIRED_EVIDENCE if field not in item)
            if missing:
                errors.append(f"{location}: missing {', '.join(missing)}")
                continue
            filename = str(item.get("document_file", ""))
            title = str(item.get("document_title", ""))
            section = str(item.get("section", ""))
            quote = str(item.get("evidence_quote", ""))
            page_start = item.get("page_start")
            page_end = item.get("page_end")
            evidence_documents.add(filename.casefold())
            if filename:
                document_counts[filename] += 1
            if not isinstance(page_start, int) or not isinstance(page_end, int):
                errors.append(f"{location}: pages must be integers")
                continue
            if page_start < 1 or page_end < page_start:
                errors.append(f"{location}: invalid page range {page_start}-{page_end}")
            evidence_locations.add((filename.casefold(), page_start, section.casefold()))
            record = manifest.get(filename.casefold())
            if manifest and record is None:
                errors.append(f"{location}: document is absent from corpus manifest")
                continue
            if record is not None:
                pages = record.get("pages")
                if isinstance(pages, int) and page_end > pages:
                    errors.append(f"{location}: page {page_end} exceeds PDF page count {pages}")
                if title and _normalized(title) != _normalized(record.get("title")):
                    warnings.append(f"{location}: title differs from corpus manifest")
                if check_quotes and manifest_path is not None:
                    pdf = _pdf_path(record, manifest_path)
                    if not pdf.is_file():
                        errors.append(f"{location}: PDF not found at {pdf}")
                    else:
                        try:
                            page_text = _page_text(pdf, page_start, page_end, cache)
                        except (subprocess.CalledProcessError, FileNotFoundError) as exc:
                            errors.append(f"{location}: PDF text extraction failed: {exc}")
                        else:
                            normalized_quote = _normalized(quote)
                            if not normalized_quote or normalized_quote not in page_text:
                                errors.append(f"{location}: evidence_quote not found on annotated page")

        if category == "cross_document" and len(evidence_documents) < 2:
            errors.append(f"{prefix}: cross_document requires evidence from at least two PDFs")
        if category == "cross_section" and len(evidence_locations) < 2:
            errors.append(f"{prefix}: cross_section requires at least two distinct locations")

    if enforce_minimums:
        if len(rows) < 220:
            errors.append(f"dataset has {len(rows)} rows; at least 220 required")
        for category, minimum in MINIMUM_COUNTS.items():
            actual = category_counts[category]
            if actual < minimum:
                errors.append(f"category {category} has {actual}; at least {minimum} required")
        if language_counts["en"] + language_counts["mixed"] < 20:
            errors.append("fewer than 20 English or mixed-language questions")
        if manifest:
            uncovered = sorted(
                filename for filename in manifest
                if sum(1 for row in rows if row.get("is_answerable") and any(
                    str(item.get("document_file", "")).casefold() == filename
                    for item in row.get("relevant_evidence", [])
                )) < 4
            )
            if uncovered:
                errors.append("documents with fewer than four answerable questions: " + ", ".join(uncovered))

    duplicate_pairs: list[tuple[str, str, float]] = []
    tokenized = [(str(row.get("question_id", "")), _question_tokens(str(row.get("user_input", "")))) for row in rows]
    for left in range(len(tokenized)):
        left_id, left_tokens = tokenized[left]
        if not left_tokens:
            continue
        for right in range(left + 1, len(tokenized)):
            right_id, right_tokens = tokenized[right]
            union = left_tokens | right_tokens
            similarity = len(left_tokens & right_tokens) / len(union) if union else 0.0
            if similarity >= 0.92:
                duplicate_pairs.append((left_id, right_id, round(similarity, 4)))
    if duplicate_pairs:
        warnings.append(f"near-duplicate question pairs: {len(duplicate_pairs)}")

    return {
        "valid": not errors,
        "question_count": len(rows),
        "category_counts": dict(sorted(category_counts.items())),
        "document_evidence_counts": dict(sorted(document_counts.items())),
        "language_counts": dict(sorted(language_counts.items())),
        "topic_counts": dict(sorted(topic_counts.items())),
        "near_duplicate_pairs": duplicate_pairs,
        "errors": errors,
        "warnings": warnings,
    }


def validate_split(all_rows: list[dict], dev_rows: list[dict], test_rows: list[dict]) -> list[str]:
    errors: list[str] = []
    all_ids = {row.get("question_id") for row in all_rows}
    dev_ids = {row.get("question_id") for row in dev_rows}
    test_ids = {row.get("question_id") for row in test_rows}
    overlap = sorted(str(value) for value in dev_ids & test_ids)
    if overlap:
        errors.append("dev/test overlap: " + ", ".join(overlap))
    if dev_ids | test_ids != all_ids:
        errors.append("dev/test union does not equal golden-set-all")
    for category in ALLOWED_CATEGORIES:
        if not any(row.get("primary_category") == category for row in dev_rows):
            errors.append(f"dev missing category {category}")
        if not any(row.get("primary_category") == category for row in test_rows):
            errors.append(f"test missing category {category}")
    return errors


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Validate the evidence-annotated Golden Set")
    parser.add_argument("--input", type=Path, required=True)
    parser.add_argument("--corpus-manifest", type=Path, default=None)
    parser.add_argument("--dev-input", type=Path, default=None)
    parser.add_argument("--test-input", type=Path, default=None)
    parser.add_argument("--output", type=Path, default=None)
    parser.add_argument("--check-quotes", action="store_true")
    parser.add_argument("--allow-small", action="store_true", help="skip 220/category minimums for smoke fixtures")
    args = parser.parse_args(argv)
    try:
        rows = load_jsonl(args.input)
        manifest = load_manifest(args.corpus_manifest)
        result = validate_rows(
            rows,
            manifest,
            args.corpus_manifest,
            check_quotes=args.check_quotes,
            enforce_minimums=not args.allow_small,
        )
        if (args.dev_input is None) != (args.test_input is None):
            raise RuntimeError("--dev-input and --test-input must be provided together")
        if args.dev_input is not None and args.test_input is not None:
            split_errors = validate_split(
                rows,
                load_jsonl(args.dev_input),
                load_jsonl(args.test_input),
            )
            result["split_validation"] = {
                "valid": not split_errors,
                "dev_input": str(args.dev_input),
                "test_input": str(args.test_input),
                "errors": split_errors,
            }
            if split_errors:
                result["errors"].extend(split_errors)
                result["valid"] = False
    except Exception as exc:
        print(str(exc), file=sys.stderr)
        return 2
    output = args.output or args.input.with_name("golden-set-validation.json")
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"valid={result['valid']} questions={result['question_count']} -> {output}")
    for error in result["errors"]:
        print(f"ERROR: {error}", file=sys.stderr)
    return 0 if result["valid"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
