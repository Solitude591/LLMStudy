from __future__ import annotations

import tempfile
import unittest
from datetime import datetime
from pathlib import Path
from unittest.mock import patch

from ragas_demo.paths import (
    GENERATED_NAME,
    SCORES_NAME,
    create_run_dir,
    default_scores_for,
    generated_path,
    latest_run_dir,
    list_run_dirs,
    migrate_legacy_flat_files,
    resolve_latest_generated,
    scores_path,
)


class PathsTest(unittest.TestCase):
    def test_create_run_dir_uses_timestamp_and_avoids_collision(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            runs = Path(tmp) / "runs"
            with patch("ragas_demo.paths.RUNS_DIR", runs):
                first = create_run_dir(datetime(2026, 8, 13, 16, 4, 5))
                second = create_run_dir(datetime(2026, 8, 13, 16, 4, 5))
            self.assertEqual(first.name, "20260813-160405")
            self.assertEqual(second.name, "20260813-160405-2")
            self.assertTrue(first.is_dir() and second.is_dir())

    def test_resolve_latest_generated_prefers_newest_run(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            runs = root / "runs"
            older = runs / "20260813-100000"
            newer = runs / "20260813-110000"
            older.mkdir(parents=True)
            newer.mkdir(parents=True)
            (older / GENERATED_NAME).write_text("{}\n", encoding="utf-8")
            (newer / GENERATED_NAME).write_text("{}\n", encoding="utf-8")
            with (
                patch("ragas_demo.paths.RUNS_DIR", runs),
                patch("ragas_demo.paths.LEGACY_GENERATED", root / "legacy.jsonl"),
            ):
                self.assertEqual(
                    resolve_latest_generated(), newer / GENERATED_NAME
                )

    def test_default_scores_for_same_run_directory(self) -> None:
        generated = Path("/tmp/runs/20260813-160405") / GENERATED_NAME
        self.assertEqual(
            default_scores_for(generated), generated.with_name(SCORES_NAME)
        )

    def test_migrate_legacy_flat_files_once(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            data = Path(tmp) / "data"
            data.mkdir()
            runs = data / "runs"
            legacy_generated = data / "medical-image-segmentation-ragas-generated.jsonl"
            legacy_scores = data / "medical-image-segmentation-ragas-scores.json"
            legacy_generated.write_text('{"user_input":"q"}\n', encoding="utf-8")
            legacy_scores.write_text('{"mean":{}}\n', encoding="utf-8")

            with (
                patch("ragas_demo.paths.DATA_DIR", data),
                patch("ragas_demo.paths.RUNS_DIR", runs),
                patch("ragas_demo.paths.LEGACY_GENERATED", legacy_generated),
                patch("ragas_demo.paths.LEGACY_SCORES", legacy_scores),
            ):
                run = migrate_legacy_flat_files()
                self.assertIsNotNone(run)
                assert run is not None
                self.assertTrue(generated_path(run).is_file())
                self.assertTrue(scores_path(run).is_file())
                self.assertFalse(legacy_generated.exists())
                self.assertFalse(legacy_scores.exists())
                self.assertIsNone(migrate_legacy_flat_files())
                self.assertEqual(latest_run_dir(), run)
                self.assertEqual(list_run_dirs(), [run])


if __name__ == "__main__":
    unittest.main()
