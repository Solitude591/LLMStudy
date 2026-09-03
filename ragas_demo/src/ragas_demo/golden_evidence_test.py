"""Guard the assembler against silently reusing evidence after a QA edit."""
import importlib.util
from pathlib import Path
import unittest

path = Path(__file__).resolve().parents[3] / "rag-module/doc/test/build_golden_set.py"
spec = importlib.util.spec_from_file_location("golden_assembler", path)
assembler = importlib.util.module_from_spec(spec)
spec.loader.exec_module(assembler)


class GoldenEvidenceTest(unittest.TestCase):
    def test_changed_answer_requires_reviewed_evidence(self):
        base = {"question_id": "q", "is_answerable": True, "user_input": "old", "reference": "old"}
        with self.assertRaisesRegex(RuntimeError, "without reviewed"):
            assembler.merge_curated_row(base, {"user_input": "new", "reference": "new"})

    def test_reviewed_evidence_updates_reference_contexts_together(self):
        base = {"question_id": "q", "is_answerable": True, "user_input": "old", "reference": "old",
                "reference_contexts": ["stale"]}
        merged = assembler.merge_curated_row(base, {"user_input": "new", "reference": "new",
            "relevant_evidence": [{"evidence_quote": "reviewed"}]})
        self.assertEqual(merged["reference_contexts"], ["reviewed"])
