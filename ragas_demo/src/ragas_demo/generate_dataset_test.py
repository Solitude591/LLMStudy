from __future__ import annotations

import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from ragas_demo.generate_dataset import load_jsonl, run_generation


class GenerateDatasetResumeTest(unittest.TestCase):
    def test_resume_keeps_success_and_only_calls_missing_row(self) -> None:
        rows = [
            {"question_id": "q1", "user_input": "first"},
            {"question_id": "q2", "user_input": "second"},
        ]
        with tempfile.TemporaryDirectory() as tmp:
            output = Path(tmp) / "generated.jsonl"
            output.write_text(
                '{"question_id":"q1","user_input":"first","response":"done",'
                '"retrieved_contexts":["ctx"]}\n',
                encoding="utf-8",
            )
            with patch(
                "ragas_demo.generate_dataset.generate",
                return_value={"response": "new", "retrieved_contexts": ["ctx2"]},
            ) as generate:
                generated, latency = run_generation(
                    rows, output, "http://rag", concurrency=1, retries=1
                )
            self.assertEqual(generate.call_count, 1)
            self.assertEqual(len(generated), 2)
            self.assertEqual(len(latency), 1)
            self.assertEqual([row["question_id"] for row in load_jsonl(output)], ["q1", "q2"])


if __name__ == "__main__":
    unittest.main()
