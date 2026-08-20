"""评测产物路径：每次生成新 run，历史结果保留在 data/runs/<时间戳>/。"""

from __future__ import annotations

from datetime import datetime
from pathlib import Path

DATA_DIR = Path(__file__).resolve().parents[2] / "data"
RUNS_DIR = DATA_DIR / "runs"

GENERATED_NAME = "generated.jsonl"
SCORES_NAME = "scores.json"

# 迁移前的扁平文件名（兼容旧默认路径）
LEGACY_GENERATED = DATA_DIR / "medical-image-segmentation-ragas-generated.jsonl"
LEGACY_SCORES = DATA_DIR / "medical-image-segmentation-ragas-scores.json"


def create_run_dir(now: datetime | None = None) -> Path:
    """新建一次评测 run 目录，形如 data/runs/20260813-160405/。"""
    stamp = (now or datetime.now()).strftime("%Y%m%d-%H%M%S")
    path = RUNS_DIR / stamp
    # 同一秒内重复跑时追加序号，避免互相覆盖。
    if path.exists():
        index = 2
        while True:
            candidate = RUNS_DIR / f"{stamp}-{index}"
            if not candidate.exists():
                path = candidate
                break
            index += 1
    path.mkdir(parents=True, exist_ok=False)
    return path


def list_run_dirs() -> list[Path]:
    if not RUNS_DIR.is_dir():
        return []
    return sorted(
        (p for p in RUNS_DIR.iterdir() if p.is_dir()),
        key=lambda p: p.name,
    )


def latest_run_dir() -> Path | None:
    runs = list_run_dirs()
    return runs[-1] if runs else None


def generated_path(run_dir: Path) -> Path:
    return run_dir / GENERATED_NAME


def scores_path(run_dir: Path) -> Path:
    return run_dir / SCORES_NAME


def resolve_latest_generated() -> Path:
    """评测默认输入：最新 run 的 generated.jsonl；否则回退旧扁平文件。"""
    run = latest_run_dir()
    if run is not None:
        path = generated_path(run)
        if path.is_file():
            return path
    if LEGACY_GENERATED.is_file():
        return LEGACY_GENERATED
    raise FileNotFoundError(
        f"未找到可评测样本。请先运行 generate_dataset，或检查 {RUNS_DIR}"
    )


def default_scores_for(generated: Path) -> Path:
    """scores 与 generated 同目录；旧扁平 generated 则用带时间戳的扁平 scores。"""
    if generated.name == GENERATED_NAME:
        return generated.with_name(SCORES_NAME)
    stamp = datetime.now().strftime("%Y%m%d-%H%M%S")
    return generated.with_name(
        f"medical-image-segmentation-ragas-scores.{stamp}.json"
    )


def migrate_legacy_flat_files() -> Path | None:
    """
    把 data/ 下旧的扁平 generated/scores 挪进一次 run，避免下次误覆盖。

    已存在 runs/ 时不迁移（视为用户已按新布局管理）。
    """
    if list_run_dirs():
        return None
    if not LEGACY_GENERATED.is_file() and not LEGACY_SCORES.is_file():
        return None

    # 用 generated 修改时间命名，便于和当时跑分结果对齐。
    source = LEGACY_GENERATED if LEGACY_GENERATED.is_file() else LEGACY_SCORES
    mtime = datetime.fromtimestamp(source.stat().st_mtime)
    run_dir = create_run_dir(mtime)
    if LEGACY_GENERATED.is_file():
        LEGACY_GENERATED.rename(generated_path(run_dir))
    if LEGACY_SCORES.is_file():
        LEGACY_SCORES.rename(scores_path(run_dir))
    return run_dir
