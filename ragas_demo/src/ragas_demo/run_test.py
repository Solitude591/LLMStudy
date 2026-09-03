from __future__ import annotations

import json
import tempfile
import unittest
from unittest.mock import patch
from pathlib import Path

from ragas_demo.run import main, _cost_estimate


class RunPipelineTest(unittest.TestCase):
    def test_cost_estimate_does_not_count_duplicate_retrieval(self) -> None:
        self.assertEqual(_cost_estimate(10, False)["estimated_total_llm_calls_range"], [20, 20])
        self.assertEqual(_cost_estimate(10, False, include_generation=False,
                                       warmup=1)["estimated_total_llm_calls_range"], [11, 11])

    def test_manifest_is_finalized_when_judge_fails_and_subset_is_exact(self) -> None:
        fixtures = [{"question_id": f"q{i}", "primary_category": "unanswerable",
                     "user_input": f"Unavailable question {i}", "reference": "无法回答",
                     "is_answerable": False, "relevant_evidence": []} for i in range(3)]
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            source, output = root / "golden-set-dev.jsonl", root / "run"
            source.write_text("\n".join(json.dumps(row) for row in fixtures) + "\n")
            with patch("ragas_demo.run.evaluate.main", side_effect=RuntimeError("judge down")), \
                 patch("ragas_demo.run.build_manifest", return_value={"outputs": {}}):
                with self.assertRaisesRegex(RuntimeError, "judge down"):
                    main(["--input", str(source), "--output", str(output),
                          "--corpus-manifest", str(root / "missing"), "--question-id", "q1",
                          "--skip-generate", "--skip-retrieval"])
            manifest = json.loads((output / "manifest.json").read_text())
            self.assertIn("validation.json", manifest["outputs"])
            self.assertEqual(json.loads((output / "cost-estimate.json").read_text())["question_count"], 1)

    def test_validate_only_creates_manifest_without_external_calls(self) -> None:
        fixture = {
            "question_id": "fact-001",
            "primary_category": "fact",
            "user_input": "What is stated?",
            "reference": "A grounded answer.",
            "is_answerable": True,
            "relevant_evidence": [{
                "document_file": "paper.pdf", "document_title": "Paper",
                "page_start": 1, "page_end": 1, "section": "Abstract",
                "evidence_quote": "A grounded answer.",
            }],
        }
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            input_path = root / "gold.jsonl"
            run_dir = root / "run"
            input_path.write_text(json.dumps(fixture) + "\n", encoding="utf-8")
            code = main([
                "--input", str(input_path), "--output", str(run_dir),
                "--corpus-manifest", str(root / "missing-manifest.jsonl"),
                "--limit", "1", "--validate-only",
            ])
            self.assertEqual(code, 0)
            self.assertTrue((run_dir / "manifest.json").is_file())
            self.assertTrue((run_dir / "validation.json").is_file())
            self.assertTrue((run_dir / "cost-estimate.json").is_file())

    def test_dev_split_skips_full_set_minimums(self) -> None:
        fixture = {
            "question_id": "fact-001",
            "primary_category": "fact",
            "user_input": "What is stated?",
            "reference": "A grounded answer.",
            "is_answerable": True,
            "relevant_evidence": [{
                "document_file": "paper.pdf", "document_title": "Paper",
                "page_start": 1, "page_end": 1, "section": "Abstract",
                "evidence_quote": "A grounded answer.",
            }],
        }
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            input_path = root / "golden-set-dev.jsonl"
            run_dir = root / "run"
            input_path.write_text(json.dumps(fixture) + "\n", encoding="utf-8")
            code = main([
                "--input", str(input_path), "--output", str(run_dir),
                "--corpus-manifest", str(root / "missing-manifest.jsonl"),
                "--validate-only",
            ])
            self.assertEqual(code, 0)
            validation = json.loads((run_dir / "validation.json").read_text(encoding="utf-8"))
            self.assertTrue(validation["valid"])


if __name__ == "__main__":
    unittest.main()
