"""Reproducibility manifest for every evaluation run (never records credentials)."""

from __future__ import annotations

import hashlib
import json
import os
import platform
import subprocess
import urllib.request
from datetime import datetime, timezone
from pathlib import Path


def sha256(path: Path | None) -> str | None:
    if path is None or not path.is_file():
        return None
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def _command(*args: str, cwd: Path | None = None) -> str | None:
    try:
        process = subprocess.run(
            list(args), cwd=cwd, capture_output=True, text=True, timeout=15, check=False
        )
    except (OSError, subprocess.SubprocessError):
        return None
    output = (process.stdout or process.stderr).strip()
    return output or None


def _elasticsearch_version(base_url: str) -> str | None:
    request = urllib.request.Request(base_url.rstrip("/") + "/")
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    try:
        with opener.open(request, timeout=3) as response:
            payload = json.loads(response.read().decode("utf-8"))
        return str(payload.get("version", {}).get("number") or "") or None
    except Exception:
        return None


def _ingestion_versions(corpus_manifest: Path | None) -> dict[str, set[str]]:
    if corpus_manifest is None:
        return {}
    state_path = corpus_manifest.with_name("ingestion-state.json")
    if not state_path.is_file():
        return {}
    try:
        state = json.loads(state_path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        return {}
    versions: dict[str, set[str]] = {}
    for entry in state.get("documents", {}).values():
        if entry.get("result") != "PUBLISHED":
            continue
        doc_id = str(entry.get("doc_id") or "")
        version_id = str(entry.get("version_id") or "")
        if doc_id and version_id:
            versions.setdefault(doc_id, set()).add(version_id)
    return versions


def build_manifest(
    run_dir: Path,
    golden_set: Path,
    corpus_manifest: Path | None,
    base_url: str,
    parameters: dict,
) -> dict:
    repo_root = Path(__file__).resolve().parents[3]
    status = _command("git", "status", "--porcelain", cwd=repo_root) or ""
    java_version = _command("java", "-version")
    published_versions = _ingestion_versions(corpus_manifest)
    return {
        "run_id": run_dir.name,
        "created_at": datetime.now(timezone.utc).astimezone().isoformat(),
        "git": {
            "commit": _command("git", "rev-parse", "HEAD", cwd=repo_root),
            "dirty": bool(status),
            "changed_paths": [line[3:] for line in status.splitlines() if len(line) >= 4],
        },
        "inputs": {
            "golden_set": str(golden_set.resolve()),
            "golden_set_sha256": sha256(golden_set),
            "corpus_manifest": str(corpus_manifest.resolve()) if corpus_manifest else None,
            "corpus_manifest_sha256": sha256(corpus_manifest),
        },
        "models": {
            "chat": os.getenv("CHAT_MODEL") or os.getenv("RAG_CHAT_MODEL") or "service-config",
            "evaluation_llm": os.getenv("RAGAS_LLM_MODEL", "deepseek-v4-pro"),
            "embedding": os.getenv("EMBEDDING_MODEL", "text-embedding-v4"),
            "reranker": os.getenv("BGE_RERANKER_MODEL_NAME", "local-bge-cross-encoder"),
        },
        "parameters": parameters,
        "runtime": {
            "python": platform.python_version(),
            "java": java_version.splitlines()[0] if java_version else None,
            "elasticsearch": _elasticsearch_version(
                os.getenv("ELASTICSEARCH_URI", "http://localhost:9200")
            ),
            "rag_base_url": base_url,
        },
        "published_document_versions": [
            {"doc_id": doc_id, "version_ids": sorted(version_ids)}
            for doc_id, version_ids in sorted(published_versions.items())
        ],
        "counts": {"success": 0, "failure": 0, "timeout": 0, "retry": 0},
        "outputs": {},
    }


def update_manifest_from_run(run_dir: Path) -> dict:
    path = run_dir / "manifest.json"
    payload = json.loads(path.read_text(encoding="utf-8"))
    versions: dict[str, set[str]] = {
        str(item.get("doc_id")): set(str(value) for value in item.get("version_ids", []))
        for item in payload.get("published_document_versions", [])
        if item.get("doc_id")
    }
    retrieval_details = run_dir / "retrieval-details.jsonl"
    if retrieval_details.is_file():
        for line in retrieval_details.read_text(encoding="utf-8").splitlines():
            if not line.strip():
                continue
            row = json.loads(line)
            for hit in row.get("top_k", []):
                doc_id = str(hit.get("docId") or "")
                version_id = str(hit.get("versionId") or "")
                if doc_id and version_id:
                    versions.setdefault(doc_id, set()).add(version_id)
    payload["published_document_versions"] = [
        {"doc_id": doc_id, "version_ids": sorted(version_ids)}
        for doc_id, version_ids in sorted(versions.items())
    ]
    latency = run_dir / "latency-details.jsonl"
    latency_rows = []
    if latency.is_file():
        latency_rows = [json.loads(line) for line in latency.read_text(encoding="utf-8").splitlines() if line.strip()]
    payload["counts"] = {
        "success": sum(1 for row in latency_rows if row.get("success") is True),
        "failure": sum(1 for row in latency_rows if row.get("success") is not True),
        "timeout": sum(1 for row in latency_rows if row.get("timeout") is True),
        "retry": sum(int(row.get("retry_count") or 0) for row in latency_rows),
    }
    for output in sorted(run_dir.iterdir()):
        if output.is_file() and output.name != "manifest.json":
            payload["outputs"][output.name] = sha256(output)
    path.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    return payload
