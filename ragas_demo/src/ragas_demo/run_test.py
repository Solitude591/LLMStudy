from __future__ import annotations

import unittest
from pathlib import Path
from unittest.mock import patch

from ragas_demo.run import main


class RunPipelineTest(unittest.TestCase):
    def test_calls_generate_then_evaluate_on_same_run(self) -> None:
        generated = Path("/tmp/runs/20260815-110000/generated.jsonl")
        with (
            patch("ragas_demo.run.generate_dataset.main", return_value=0) as generate,
            patch("ragas_demo.run.evaluate.main", return_value=0) as evaluate,
            patch("ragas_demo.run.resolve_latest_generated", return_value=generated),
        ):
            self.assertEqual(
                main(
                    [
                        "--input",
                        "gold.jsonl",
                        "--base-url",
                        "http://rag",
                        "--limit",
                        "2",
                    ]
                ),
                0,
            )

        generate.assert_called_once_with(
            ["--input", "gold.jsonl", "--base-url", "http://rag", "--limit", "2"]
        )
        evaluate.assert_called_once_with(
            ["--input", str(generated), "--limit", "2"]
        )

    def test_skips_evaluate_when_generate_fails(self) -> None:
        with (
            patch("ragas_demo.run.generate_dataset.main", return_value=1) as generate,
            patch("ragas_demo.run.evaluate.main") as evaluate,
        ):
            self.assertEqual(main([]), 1)

        generate.assert_called_once()
        evaluate.assert_not_called()


if __name__ == "__main__":
    unittest.main()
