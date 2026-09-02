"""Validate, generate, retrieve, score, and record one reproducible evaluation run."""

from __future__ import annotations

import argparse
import json
import os
import sys
from pathlib import Path

from dotenv import load_dotenv

from ragas_demo import evaluate
from ragas_demo.generate_dataset import DEFAULT_INPUT, run_generation
from ragas_demo.metrics import latency_summary
from ragas_demo.paths import create_run_dir, generated_path
from ragas_demo.retrieval import STAGES, run_retrieval
from ragas_demo.run_manifest import build_manifest, update_manifest_from_run
from ragas_demo.validate_dataset import load_jsonl, load_manifest, validate_rows

DEFAULT_CORPUS_MANIFEST = (
    Path(__file__).resolve().parents[3]
    / "rag-module" / "doc" / "evaluation-corpus" / "corpus-manifest.jsonl"
)


def _write_jsonl(path: Path, rows: list[dict]) -> None:
    with path.open("w", encoding="utf-8") as stream:
        for row in rows:
            stream.write(json.dumps(row, ensure_ascii=False) + "\n")


def _merge_generation_failures(run_dir: Path, generation_latency: list[dict]) -> None:
    latency_path = run_dir / "latency-details.jsonl"
    existing = []
    if latency_path.is_file():
        existing = [
            json.loads(line)
            for line in latency_path.read_text(encoding="utf-8").splitlines()
            if line.strip()
        ]
    existing_ids = {str(row.get("question_id")) for row in existing}
    for row in generation_latency:
        if row.get("success") is False and str(row.get("question_id")) not in existing_ids:
            existing.append(row)
    if not existing and generation_latency:
        existing = generation_latency
    if existing:
        _write_jsonl(latency_path, existing)
        metrics = latency_summary(existing, STAGES)
        current = run_dir / "latency-metrics.json"
        if current.is_file():
            metadata = json.loads(current.read_text(encoding="utf-8"))
            for field in ("warmup", "concurrency", "timeout_seconds", "retrieval_k"):
                if field in metadata:
                    metrics[field] = metadata[field]
        current.write_text(json.dumps(metrics, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def _cost_estimate(question_count: int, include_ragas: bool) -> dict:
    dataset_calls = question_count * 2
    ragas_low = question_count * 6 if include_ragas else 0
    ragas_high = question_count * 12 if include_ragas else 0
    return {
        "question_count": question_count,
        "estimated_dataset_llm_calls": dataset_calls,
        "estimated_ragas_llm_calls_range": [ragas_low, ragas_high],
        "estimated_total_llm_calls_range": [dataset_calls + ragas_low, dataset_calls + ragas_high],
        "note": "Call counts are planning estimates; monetary cost depends on configured model token pricing.",
    }


def main(argv: list[str] | None = None) -> int:
    load_dotenv()
    parser = argparse.ArgumentParser(description="Run the complete evidence-aware RAG evaluation")
    parser.add_argument("--input", type=Path, default=DEFAULT_INPUT)
    parser.add_argument("--corpus-manifest", type=Path, default=DEFAULT_CORPUS_MANIFEST)
    parser.add_argument("--output", type=Path, default=None, help="run directory; defaults to data/runs/<id>")
    parser.add_argument("--base-url", default=os.getenv("RAG_BASE_URL", "http://localhost:8080"))
    parser.add_argument("--limit", type=int, default=0)
    parser.add_argument("--retrieval-k", type=int, default=5)
    parser.add_argument("--warmup", type=int, default=0)
    parser.add_argument("--concurrency", type=int, default=1)
    parser.add_argument("--timeout", type=float, default=300.0)
    parser.add_argument("--retries", type=int, default=3)
    parser.add_argument("--validate-only", action="store_true")
    parser.add_argument("--skip-generate", action="store_true")
    parser.add_argument("--skip-retrieval", action="store_true")
    parser.add_argument("--skip-ragas", action="store_true")
    parser.add_argument("--check-quotes", action="store_true")
    args = parser.parse_args(argv)

    if not args.input.is_file():
        print(f"input not found: {args.input}", file=sys.stderr)
        return 2
    run_dir = args.output or create_run_dir()
    run_dir.mkdir(parents=True, exist_ok=True)
    rows = load_jsonl(args.input)
    if args.limit > 0:
        rows = rows[: args.limit]
    corpus_path = args.corpus_manifest if args.corpus_manifest.is_file() else None
    validation = validate_rows(
        rows,
        load_manifest(corpus_path),
        corpus_path,
        check_quotes=args.check_quotes,
        enforce_minimums=args.limit <= 0,
    )
    (run_dir / "validation.json").write_text(
        json.dumps(validation, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )
    if not validation["valid"]:
        print("Golden Set validation failed; no external calls were made", file=sys.stderr)
        return 1

    parameters = {
        "chunk_size": int(os.getenv("RAG_MARKDOWN_CHUNK_SIZE", "1000")),
        "chunk_overlap": int(os.getenv("RAG_MARKDOWN_CHUNK_OVERLAP", "100")),
        "per_query_top_k": int(os.getenv("RAG_RETRIEVAL_PER_QUERY_TOP_K", "10")),
        "final_top_n": int(os.getenv("RAG_RETRIEVAL_TOP_N", "5")),
        "rrf_k": int(os.getenv("RAG_RETRIEVAL_RRF_K", "60")),
        "bge_threshold": float(os.getenv("BGE_RERANKER_MIN_SCORE", "0")),
        "retrieval_k": args.retrieval_k,
        "warmup": args.warmup,
        "concurrency": args.concurrency,
        "timeout_seconds": args.timeout,
    }
    manifest = build_manifest(run_dir, args.input, corpus_path, args.base_url, parameters)
    (run_dir / "manifest.json").write_text(
        json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )
    estimate = _cost_estimate(len(rows), not args.skip_ragas)
    (run_dir / "cost-estimate.json").write_text(
        json.dumps(estimate, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )
    print(
        "estimated external LLM calls: "
        f"{estimate['estimated_total_llm_calls_range'][0]}-"
        f"{estimate['estimated_total_llm_calls_range'][1]} for {len(rows)} questions"
    )
    if args.validate_only:
        update_manifest_from_run(run_dir)
        return 0

    generated = generated_path(run_dir)
    generation_latency: list[dict] = []
    if not args.skip_generate:
        generated_rows, generation_latency = run_generation(
            rows, generated, args.base_url, args.warmup, args.concurrency,
            args.timeout, args.retries, resume=True,
        )
        _write_jsonl(run_dir / "generation-latency-details.jsonl", generation_latency)
        if len(generated_rows) != len(rows):
            print(
                f"generation incomplete: {len(generated_rows)}/{len(rows)}; artifacts preserved for resume",
                file=sys.stderr,
            )
    elif not generated.is_file():
        generated = args.input

    if not args.skip_retrieval:
        retrieval_rows = rows
        if not args.skip_generate and generated_path(run_dir).is_file():
            retrieval_rows = load_jsonl(generated_path(run_dir))
            if args.limit > 0:
                retrieval_rows = retrieval_rows[: args.limit]
        run_retrieval(
            retrieval_rows, run_dir, args.base_url, args.retrieval_k,
            args.warmup, args.concurrency, args.timeout, args.retries,
        )
    _merge_generation_failures(run_dir, generation_latency)

    ragas_code = 0
    if not args.skip_ragas:
        if not generated.is_file():
            print("RAGAS skipped because generated.jsonl is unavailable", file=sys.stderr)
            ragas_code = 1
        else:
            ragas_code = evaluate.main([
                "--input", str(generated),
                "--output", str(run_dir / "scores.json"),
                "--limit", str(args.limit),
                "--retries", str(args.retries),
            ])
    update_manifest_from_run(run_dir)
    print(f"run directory: {run_dir}")
    return ragas_code


if __name__ == "__main__":
    raise SystemExit(main())
