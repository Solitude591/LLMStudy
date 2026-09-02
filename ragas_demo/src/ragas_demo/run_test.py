from __future__ import annotations

import json
import tempfile
import unittest
from pathlib import Path

from ragas_demo.run import main


class RunPipelineTest(unittest.TestCase):
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


if __name__ == "__main__":
    unittest.main()
