from __future__ import annotations

import unittest

from ragas_demo.metrics import (
    aggregate_retrieval,
    latency_summary,
    match_evidence,
    percentile,
    score_answerable,
)


class MetricsTest(unittest.TestCase):
    def test_hit_rate_and_mrr_use_first_relevant_top_five(self) -> None:
        row = {
            "is_answerable": True,
            "relevant_evidence": [{
                "document_file": "paper.pdf", "page_start": 3, "page_end": 4
            }],
        }
        hits = [
            {"sourceUrl": "https://store/other.pdf", "pageStart": 3, "pageEnd": 3},
            {"sourceUrl": "https://store/paper.pdf", "pageStart": 4, "pageEnd": 5},
        ]
        scored = score_answerable(row, hits, 5)
        self.assertEqual(scored["hit"], 1)
        self.assertEqual(scored["first_relevant_rank"], 2)
        self.assertEqual(scored["reciprocal_rank"], 0.5)

    def test_stable_id_has_priority_over_file_page(self) -> None:
        matched, method = match_evidence(
            {"chunk_id": "chunk-7", "document_file": "wrong.pdf", "page_start": 9},
            {"chunkId": "chunk-7", "sourceUrl": "right.pdf"},
        )
        self.assertTrue(matched)
        self.assertEqual(method, "stable-chunk-id")

    def test_stable_document_id_still_requires_page_overlap(self) -> None:
        matched, method = match_evidence(
            {"document_id": "doc-7", "page_start": 3, "page_end": 3},
            {"docId": "doc-7", "pageStart": 5, "pageEnd": 5},
        )
        self.assertFalse(matched)
        self.assertEqual(method, "stable-document-page")

    def test_missing_page_metadata_is_unscorable_not_fuzzy_match(self) -> None:
        row = {
            "is_answerable": True,
            "relevant_evidence": [{"document_file": "paper.pdf", "page_start": 3}],
        }
        scored = score_answerable(
            row, [{"sourceUrl": "https://store/paper.pdf", "pageStart": None}], 5
        )
        self.assertFalse(scored["scorable"])

    def test_unanswerable_is_excluded_from_retrieval_denominator(self) -> None:
        details = [
            {
                "is_answerable": True, "scorable": True, "hit": 1,
                "reciprocal_rank": 0.5, "primary_category": "fact",
            },
            {
                "is_answerable": False, "scorable": False,
                "refusal_correct": True, "primary_category": "unanswerable",
            },
        ]
        result = aggregate_retrieval(details, 5)
        self.assertEqual(result["overall"]["count"], 1)
        self.assertEqual(result["overall"]["hit_rate@5"], 1.0)
        self.assertEqual(result["unanswerable"]["refusal_accuracy"], 1.0)

    def test_missing_page_mixed_with_other_documents_is_unknown(self) -> None:
        row = {"relevant_evidence": [{"document_id": "d", "page_start": 3}]}
        scored = score_answerable(row, [{"docId": "other", "pageStart": 1, "pageEnd": 1},
                                        {"docId": "d", "pageStart": None}], 5)
        self.assertFalse(scored["scorable"])
        self.assertIsNone(scored["hit"])
        self.assertTrue(scored["all_documents_covered"])
        self.assertEqual(scored["unknown_evidence_count"], 1)

    def test_confirmed_hit_after_unknown_keeps_hit_but_not_exact_mrr(self) -> None:
        row = {"is_answerable": True, "relevant_evidence": [
            {"document_id": "d", "page_start": 3}]}
        hits = [{"docId": "d"}, {"docId": "d", "pageStart": 3, "pageEnd": 3}]
        scored = score_answerable(row, hits, 5)
        self.assertTrue(scored["scorable"])
        self.assertEqual(scored["hit"], 1)
        self.assertEqual(scored["first_confirmed_rank"], 2)
        self.assertIsNone(scored["reciprocal_rank"])
        result = aggregate_retrieval([{**row, **scored}], 5)
        self.assertEqual(result["overall"]["hit_rate@5"], 1)
        self.assertEqual(result["overall"]["mrr_scorable_count"], 0)
        self.assertIsNone(result["overall"]["mrr@5"])
        self.assertEqual(score_answerable(row, list(reversed(hits)), 5)["reciprocal_rank"], 1)

    def test_unrelated_missing_page_is_still_a_definite_miss(self) -> None:
        row = {"relevant_evidence": [{"document_id": "d", "page_start": 3}]}
        self.assertEqual(score_answerable(row, [{"docId": "other"}], 5)["hit"], 0)
        self.assertEqual(score_answerable(row, [], 5)["hit"], 0)

    def test_same_document_wrong_version_cannot_match(self) -> None:
        self.assertFalse(match_evidence(
            {"document_id": "d", "version_id": "v2", "page_start": 1},
            {"docId": "d", "versionId": "v1", "pageStart": 1, "pageEnd": 1},
        )[0])

    def test_any_hit_is_not_all_required_documents_covered(self) -> None:
        row = {"relevant_evidence": [
            {"document_id": "d1", "page_start": 1},
            {"document_id": "d2", "page_start": 2}]}
        result = score_answerable(row, [{"docId": "d1", "pageStart": 1, "pageEnd": 1}], 5)
        self.assertEqual(result["hit"], 1)
        self.assertFalse(result["all_documents_covered"])
        self.assertEqual(result["evidence_coverage_lower_bound"], 0.5)

    def test_percentiles_and_failures_are_retained(self) -> None:
        self.assertEqual(percentile([10, 20, 30, 40], 0.5), 25)
        result = latency_summary(
            [
                {"success": True, "stages_ms": {"total_retrieval": 10}},
                {"success": True, "stages_ms": {"total_retrieval": 30}},
                {"success": False, "timeout": True, "stages_ms": {}},
            ],
            ("total_retrieval",),
        )
        self.assertEqual(result["failure"], 1)
        self.assertEqual(result["timeout"], 1)
        self.assertEqual(result["stages"]["total_retrieval"]["p50"], 20)


if __name__ == "__main__":
    unittest.main()
