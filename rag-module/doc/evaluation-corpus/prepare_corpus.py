#!/usr/bin/env python3
"""Download missing public PDFs and build validated JSONL/CSV corpus manifests."""

from __future__ import annotations

import argparse
import csv
import hashlib
import json
import subprocess
import time
import urllib.request
from collections import Counter
from pathlib import Path

HERE = Path(__file__).resolve().parent
REPO_ROOT = HERE.parents[2]
SOURCES = HERE / "corpus-sources.json"


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def command(*args: str) -> subprocess.CompletedProcess[str]:
    return subprocess.run(args, capture_output=True, text=True, check=False)


def download(url: str, destination: Path, retries: int) -> tuple[bool, int, str | None]:
    destination.parent.mkdir(parents=True, exist_ok=True)
    partial = destination.with_suffix(destination.suffix + ".part")
    last_error: str | None = None
    for attempt in range(1, max(1, retries) + 1):
        try:
            request = urllib.request.Request(url, headers={"User-Agent": "LLMStudy-RAG-Evaluation/1.0"})
            with urllib.request.urlopen(request, timeout=120) as response, partial.open("wb") as output:
                while block := response.read(1024 * 1024):
                    output.write(block)
            if partial.stat().st_size < 10_000:
                raise RuntimeError("downloaded file is unexpectedly small")
            partial.replace(destination)
            return True, attempt - 1, None
        except Exception as exc:
            last_error = str(exc)
            if attempt < max(1, retries):
                time.sleep(min(2 ** (attempt - 1), 4))
    partial.unlink(missing_ok=True)
    return False, max(1, retries) - 1, last_error


def validate_pdf(path: Path) -> dict:
    info = command("pdfinfo", str(path))
    if info.returncode != 0:
        return {"valid": False, "error": info.stderr.strip() or "pdfinfo failed"}
    fields = {}
    for line in info.stdout.splitlines():
        if ":" in line:
            key, value = line.split(":", 1)
            fields[key.strip()] = value.strip()
    pages = int(fields.get("Pages", "0"))
    first_text = command("pdftotext", "-f", "1", "-l", "1", "-layout", str(path), "-")
    if pages < 1 or first_text.returncode != 0 or len(first_text.stdout.strip()) < 80:
        return {"valid": False, "error": "missing pages or extractable first-page text"}
    full_text = command("pdftotext", "-layout", str(path), "-")
    image_list = command("pdfimages", "-list", str(path))
    has_tables = "table " in full_text.stdout.casefold() or "table\n" in full_text.stdout.casefold()
    image_rows = [line for line in image_list.stdout.splitlines() if line.strip()[:1].isdigit()]
    return {
        "valid": True,
        "pages": pages,
        "has_tables": has_tables,
        "has_images": bool(image_rows),
        "pdf_version": fields.get("PDF version"),
        "page_size": fields.get("Page size"),
    }


def write_manifests(records: list[dict]) -> None:
    jsonl = HERE / "corpus-manifest.jsonl"
    with jsonl.open("w", encoding="utf-8") as stream:
        for record in records:
            stream.write(json.dumps(record, ensure_ascii=False) + "\n")
    csv_path = HERE / "corpus-manifest.csv"
    fields = [
        "corpus_id", "filename", "title", "authors", "year", "topic", "source_url",
        "local_path", "pages", "sha256", "has_tables", "has_images", "download_status",
        "validation_status", "retry_count", "failure_reason",
    ]
    with csv_path.open("w", encoding="utf-8", newline="") as stream:
        writer = csv.DictWriter(stream, fieldnames=fields, extrasaction="ignore")
        writer.writeheader()
        for record in records:
            row = dict(record)
            row["authors"] = "; ".join(record.get("authors", []))
            writer.writerow(row)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--no-download", action="store_true")
    parser.add_argument("--retries", type=int, default=3)
    args = parser.parse_args()
    sources = json.loads(SOURCES.read_text(encoding="utf-8"))
    records: list[dict] = []
    hashes: dict[str, str] = {}
    for index, source in enumerate(sources, start=1):
        path = REPO_ROOT / source["local_path"]
        downloaded = False
        retry_count = 0
        failure: str | None = None
        if not path.is_file() and source.get("download_url") and not args.no_download:
            downloaded, retry_count, failure = download(source["download_url"], path, args.retries)
        if not path.is_file():
            record = {
                **source,
                "pages": None, "sha256": None, "has_tables": None, "has_images": None,
                "download_status": "failed" if failure else "missing",
                "validation_status": "not-validated", "retry_count": retry_count,
                "failure_reason": failure,
            }
        else:
            validation = validate_pdf(path)
            digest = sha256(path)
            duplicate_of = hashes.get(digest)
            hashes.setdefault(digest, source["corpus_id"])
            valid = validation.get("valid") is True and duplicate_of is None
            record = {
                **source,
                "pages": validation.get("pages"),
                "sha256": digest,
                "has_tables": validation.get("has_tables"),
                "has_images": validation.get("has_images"),
                "download_status": (
                    "downloaded" if downloaded else "cached" if source.get("download_url") else "existing"
                ),
                "validation_status": "valid" if valid else "invalid",
                "retry_count": retry_count,
                "failure_reason": (
                    f"duplicate of {duplicate_of}" if duplicate_of else validation.get("error")
                ),
            }
        records.append(record)
        print(f"[{index}/{len(sources)}] {source['corpus_id']} {record['validation_status']}")
    write_manifests(records)
    summary = {
        "count": len(records),
        "valid": sum(record["validation_status"] == "valid" for record in records),
        "invalid": sum(record["validation_status"] != "valid" for record in records),
        "topics": dict(Counter(record["topic"] for record in records)),
    }
    (HERE / "corpus-summary.json").write_text(
        json.dumps(summary, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )
    print(json.dumps(summary, ensure_ascii=False))
    return 0 if summary["valid"] == len(records) else 1


if __name__ == "__main__":
    raise SystemExit(main())
