"""Pure retrieval and latency metrics used by the real-service evaluation CLI."""

from __future__ import annotations

import math
import statistics
from collections import defaultdict
from pathlib import Path
from urllib.parse import unquote, urlparse


def percentile(values: list[float], quantile: float) -> float | None:
    """Return a linearly interpolated percentile without dropping slow samples."""
    if not values:
        return None
    if not 0.0 <= quantile <= 1.0:
        raise ValueError("quantile must be between 0 and 1")
    ordered = sorted(float(value) for value in values)
    position = (len(ordered) - 1) * quantile
    lower = math.floor(position)
    upper = math.ceil(position)
    if lower == upper:
        return ordered[lower]
    weight = position - lower
    return ordered[lower] * (1.0 - weight) + ordered[upper] * weight


def latency_summary(
    details: list[dict],
    stage_names: tuple[str, ...],
) -> dict:
    """Summarize successful measurements while retaining request failures."""
    request_success = sum(1 for row in details if row.get("success") is True)
    request_failure = len(details) - request_success
    stages: dict[str, dict] = {}
    for stage in stage_names:
        values = [
            float(row["stages_ms"][stage])
            for row in details
            if row.get("success") is True
            and isinstance(row.get("stages_ms"), dict)
            and isinstance(row["stages_ms"].get(stage), (int, float))
        ]
        stages[stage] = {
            "count": len(values),
            "min": min(values) if values else None,
            "mean": statistics.fmean(values) if values else None,
            "p50": percentile(values, 0.50),
            "p90": percentile(values, 0.90),
            "p95": percentile(values, 0.95),
            "p99": percentile(values, 0.99),
            "max": max(values) if values else None,
            "success": len(values),
            # A successful request can still miss one stage measurement (for example,
            # an older diagnose response). Count that as a stage failure instead of
            # silently making the stage denominator smaller.
            "failure": len(details) - len(values),
        }
    return {
        "request_count": len(details),
        "success": request_success,
        "failure": request_failure,
        "timeout": sum(1 for row in details if row.get("timeout") is True),
        "degradations": {
            "bm25": sum(1 for row in details if row.get("bm25_degraded") is True),
            "knn": sum(1 for row in details if row.get("knn_degraded") is True),
            "bge": sum(1 for row in details if row.get("bge_degraded") is True),
        },
        "stages": stages,
    }


def _text(value: object) -> str:
    return "" if value is None else str(value).strip()


def normalized_filename(value: object) -> str:
    raw = unquote(_text(value))
    if not raw:
        return ""
    parsed = urlparse(raw)
    path = parsed.path if parsed.scheme else raw
    return Path(path).name.casefold()


def _stable_match(evidence: dict, hit: dict) -> tuple[bool, str] | None:
    expected_chunk = _text(evidence.get("chunk_id"))
    if expected_chunk:
        return expected_chunk == _text(hit.get("chunkId")), "stable-chunk-id"

    pairs = (
        ("version_id", "versionId"),
        ("document_id", "docId"),
        ("doc_id", "docId"),
    )
    expected_ids = [
        (_text(evidence.get(evidence_key)), _text(hit.get(hit_key)))
        for evidence_key, hit_key in pairs
        if _text(evidence.get(evidence_key))
    ]
    if not expected_ids:
        return None
    # Prefer the most specific supplied identity (version before document).
    # A matching docId must not mask a conflicting published version.
    if any(actual and expected != actual for expected, actual in expected_ids) or not any(
        expected == actual for expected, actual in expected_ids
    ):
        return False, "stable-document-id-mismatch"
    overlap = _page_overlap(evidence, hit)
    if overlap is None:
        return False, "missing-page-metadata"
    return overlap, "stable-document-page"


def _file_matches(evidence: dict, hit: dict) -> bool:
    expected = normalized_filename(evidence.get("document_file"))
    if not expected:
        return False
    candidates = {
        normalized_filename(hit.get("documentFile")),
        normalized_filename(hit.get("sourceUrl")),
    }
    candidates.discard("")
    return expected in candidates


def _page_overlap(evidence: dict, hit: dict) -> bool | None:
    expected_start = evidence.get("page_start")
    expected_end = evidence.get("page_end", expected_start)
    actual_start = hit.get("pageStart")
    actual_end = hit.get("pageEnd", actual_start)
    if not all(isinstance(value, int) for value in (
        expected_start, expected_end, actual_start, actual_end
    )):
        return None
    return max(expected_start, actual_start) <= min(expected_end, actual_end)


def match_evidence(evidence: dict, hit: dict) -> tuple[bool, str]:
    """Match by stable IDs first, then by filename plus page overlap."""
    stable = _stable_match(evidence, hit)
    if stable is not None:
        return stable
    if _file_matches(evidence, hit):
        overlap = _page_overlap(evidence, hit)
        if overlap is True:
            return True, "file-page"
        if overlap is None:
            return False, "missing-page-metadata"
    return False, "no-match"


def score_answerable(row: dict, hits: list[dict], retrieval_k: int) -> dict:
    evidence = row.get("relevant_evidence") or []
    if not evidence:
        return {
            "scorable": False,
            "unscorable_reason": "missing-relevant-evidence",
            "first_relevant_rank": None,
            "hit": None,
            "reciprocal_rank": None,
        }
    top_hits = hits[: max(1, retrieval_k)]
    first_rank: int | None = None
    match_method: str | None = None
    unknown_before_hit = False
    for hit_index, hit in enumerate(top_hits, start=1):
        hit_missing_page = False
        for item in evidence:
            matched, method = match_evidence(item, hit)
            if matched:
                first_rank = hit_index
                match_method = method
                break
            hit_missing_page = hit_missing_page or method == "missing-page-metadata"
        if first_rank is not None:
            break
        unknown_before_hit = unknown_before_hit or hit_missing_page
    coverage = evidence_coverage(row, top_hits)
    if first_rank is None and unknown_before_hit:
        return {
            **coverage,
            "scorable": False,
            "unscorable_reason": "retrieval-results-missing-page-metadata",
            "first_relevant_rank": None,
            "hit": None,
            "reciprocal_rank": None,
            "mrr_scorable": False,
        }
    return {
        **coverage,
        "scorable": True,
        "unscorable_reason": None,
        "first_relevant_rank": first_rank if not unknown_before_hit else None,
        "first_confirmed_rank": first_rank,
        "match_method": match_method,
        "hit": 1 if first_rank is not None else 0,
        "reciprocal_rank": (
            None if unknown_before_hit else 1.0 / first_rank if first_rank is not None else 0.0
        ),
        "mrr_scorable": not unknown_before_hit,
    }


def evidence_coverage(row: dict, hits: list[dict]) -> dict:
    """Supplement any-hit with document coverage and conservative passage coverage.

    Document coverage deliberately ignores pages; it is not evidence sufficiency.
    Unknown pages never count as a confirmed evidence match.
    """
    evidence = row.get("relevant_evidence") or []
    documents: dict[tuple[str, str], bool] = {}
    matched_count = 0
    unknown_count = 0
    for item in evidence:
        matches = [match_evidence(item, hit) for hit in hits]
        matched = any(result[0] for result in matches)
        matched_count += matched
        unknown_count += not matched and any(
            method == "missing-page-metadata" for _, method in matches
        )
        if item.get("version_id"):
            key = ("versionId", _text(item["version_id"]))
            covered = any(_text(hit.get(key[0])) == key[1] for hit in hits)
        elif item.get("document_id") or item.get("doc_id"):
            key = ("docId", _text(item.get("document_id") or item.get("doc_id")))
            covered = any(_text(hit.get(key[0])) == key[1] for hit in hits)
        elif item.get("document_file"):
            key = ("file", normalized_filename(item["document_file"]))
            covered = any(_file_matches(item, hit) for hit in hits)
        else:
            continue
        documents[key] = documents.get(key, False) or covered
    return {
        "required_document_count": len(documents),
        "covered_document_count": sum(documents.values()),
        "all_documents_covered": all(documents.values()) if documents else None,
        "required_evidence_count": len(evidence),
        "matched_evidence_count": matched_count,
        "unknown_evidence_count": unknown_count,
        "evidence_coverage_lower_bound": matched_count / len(evidence) if evidence else None,
    }


def is_refusal(response: object) -> bool:
    normalized = _text(response).casefold()
    markers = (
        "无法回答", "不能回答", "未提供", "没有提供", "材料中没有",
        "根据给定材料无法", "insufficient evidence", "cannot answer",
        "not provided", "does not report", "do not contain",
    )
    return bool(normalized) and any(marker in normalized for marker in markers)


def _group_key(row: dict, key: str) -> str:
    if key == "scope":
        return "cross_document" if row.get("primary_category") == "cross_document" else "single_document"
    value = row.get(key)
    return _text(value) or "unknown"


def aggregate_retrieval(details: list[dict], retrieval_k: int) -> dict:
    answerable = [row for row in details if row.get("is_answerable") is True]
    scorable = [row for row in answerable if row.get("scorable") is True]
    unanswerable = [row for row in details if row.get("is_answerable") is False]

    def metrics(rows: list[dict]) -> dict:
        mrr_rows = [row for row in rows if row.get("reciprocal_rank") is not None]
        return {
            "count": len(rows),
            f"hit_rate@{retrieval_k}": (
                statistics.fmean(row["hit"] for row in rows) if rows else None
            ),
            f"mrr@{retrieval_k}": (
                statistics.fmean(row["reciprocal_rank"] for row in mrr_rows) if mrr_rows else None
            ),
            "mrr_scorable_count": len(mrr_rows),
        }

    grouped: dict[str, dict] = {}
    for dimension in ("primary_category", "topic", "language", "scope"):
        buckets: dict[str, list[dict]] = defaultdict(list)
        for row in scorable:
            buckets[_group_key(row, dimension)].append(row)
        grouped[dimension] = {name: metrics(rows) for name, rows in sorted(buckets.items())}

    refusal_scored = [row for row in unanswerable if row.get("refusal_correct") is not None]
    document_rows = [row for row in answerable if row.get("all_documents_covered") is not None]
    cross_document_rows = [row for row in document_rows if row.get("primary_category") == "cross_document"]
    return {
        "metric_schema_version": 2,
        "retrieval_k": retrieval_k,
        "answerable_count": len(answerable),
        "scorable_count": len(scorable),
        "unscorable_count": len(answerable) - len(scorable),
        "matching_coverage": len(scorable) / len(answerable) if answerable else None,
        "overall": metrics(scorable),
        "by_dimension": grouped,
        "document_coverage": {
            "count": len(document_rows),
            f"all_documents_covered@{retrieval_k}": (
                statistics.fmean(row["all_documents_covered"] for row in document_rows)
                if document_rows else None
            ),
            "cross_document_count": len(cross_document_rows),
            f"cross_document_all_covered@{retrieval_k}": (
                statistics.fmean(row["all_documents_covered"] for row in cross_document_rows)
                if cross_document_rows else None
            ),
            "note": "Document identity coverage ignores pages and does not imply sufficient evidence.",
        },
        "unanswerable": {
            "count": len(unanswerable),
            "scored_count": len(refusal_scored),
            "refusal_accuracy": (
                statistics.fmean(1.0 if row["refusal_correct"] else 0.0 for row in refusal_scored)
                if refusal_scored else None
            ),
        },
    }
