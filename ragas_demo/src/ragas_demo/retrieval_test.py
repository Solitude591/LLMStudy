from __future__ import annotations

import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from ragas_demo.retrieval import run_retrieval


class RetrievalResumeTest(unittest.TestCase):
    def test_resume_skips_successful_rows(self) -> None:
        rows = [
            {
                "question_id": "q1",
                "user_input": "first",
                "is_answerable": True,
                "relevant_evidence": [{"document_file": "a.pdf", "page_start": 1}],
            },
            {
                "question_id": "q2",
                "user_input": "second",
                "is_answerable": True,
                "relevant_evidence": [{"document_file": "b.pdf", "page_start": 2}],
            },
        ]
        with tempfile.TemporaryDirectory() as tmp:
            output = Path(tmp)
            (output / "retrieval-details.jsonl").write_text(
                '{"question_id":"q1","success":true,"is_answerable":true,'
                '"scorable":true,"hit":1,"reciprocal_rank":1.0}\n',
                encoding="utf-8",
            )
            (output / "latency-details.jsonl").write_text(
                '{"question_id":"q1","success":true,"stages_ms":{"total_retrieval":10}}\n',
                encoding="utf-8",
            )
            with patch(
                "ragas_demo.retrieval.diagnose",
                return_value=({"finalCandidates": [], "traceId": "t"}, 1.0, 0),
            ) as diagnose:
                run_retrieval(rows, output, "http://rag", retrieval_k=5, retries=1)
            self.assertEqual(diagnose.call_count, 1)
            details = [
                line for line in (output / "retrieval-details.jsonl").read_text().splitlines()
                if line.strip()
            ]
            self.assertEqual(len(details), 2)
