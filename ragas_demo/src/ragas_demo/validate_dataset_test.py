from __future__ import annotations

import unittest
from pathlib import Path
from tempfile import TemporaryDirectory

from unittest.mock import patch

from ragas_demo.validate_dataset import main, validate_rows, validate_split


def row(question_id: str, category: str = "fact") -> dict:
    return {
        "question_id": question_id,
        "primary_category": category,
        "user_input": f"Question {question_id}?",
        "reference": "An answer grounded in the cited sentence.",
        "is_answerable": True,
        "relevant_evidence": [{
            "document_file": "paper.pdf",
            "document_title": "Paper",
            "page_start": 1,
            "page_end": 1,
            "section": "Abstract",
            "evidence_quote": "A cited sentence.",
        }],
    }


class ValidateDatasetTest(unittest.TestCase):
    def test_valid_small_fixture(self) -> None:
        result = validate_rows([row("fact-001")], enforce_minimums=False)
        self.assertTrue(result["valid"])

    def test_rejects_unanswerable_evidence_and_non_refusal(self) -> None:
        item = row("unanswerable-001", "unanswerable")
        item["is_answerable"] = False
        item["reference"] = "The exact answer is 42."
        result = validate_rows([item], enforce_minimums=False)
        self.assertFalse(result["valid"])
        self.assertTrue(any("must not contain evidence" in error for error in result["errors"]))

    def test_cross_document_requires_two_pdfs(self) -> None:
        result = validate_rows([row("cross-document-001", "cross_document")], enforce_minimums=False)
        self.assertFalse(result["valid"])

    def test_split_requires_disjoint_complete_union(self) -> None:
        all_rows = []
        dev = []
        test = []
        for category in (
            "fact", "table", "cross_section", "cross_document", "ambiguous", "unanswerable"
        ):
            first = row(f"{category}-001", category)
            second = row(f"{category}-002", category)
            all_rows.extend((first, second))
            dev.append(first)
            test.append(second)
        self.assertEqual(validate_split(all_rows, dev, test), [])
        first = all_rows[0]
        self.assertTrue(validate_split([first], [first], [first]))

    def test_cli_requires_both_split_files(self) -> None:
        with TemporaryDirectory() as directory:
            input_path = Path(directory) / "all.jsonl"
            input_path.write_text(
                '{"question_id":"fact-001","primary_category":"fact",'
                '"user_input":"Question?","reference":"Answer",'
                '"is_answerable":true,"relevant_evidence":[]}\n',
                encoding="utf-8",
            )
            with patch("sys.stderr"):
                self.assertEqual(
                    main([
                        "--input", str(input_path),
                        "--dev-input", str(input_path),
                        "--allow-small",
                    ]),
                    2,
                )


if __name__ == "__main__":
    unittest.main()
