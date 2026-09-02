#!/usr/bin/env python3
"""Resumable corpus upload, processing-status polling, and publication via rag-module APIs."""

from __future__ import annotations

import argparse
import json
import mimetypes
import os
import sys
import time
import urllib.error
import urllib.request
import uuid
from datetime import datetime, timezone
from pathlib import Path


DEFAULT_MANIFEST = Path(__file__).with_name("corpus-manifest.jsonl")
DEFAULT_STATE = Path(__file__).with_name("ingestion-state.json")
DEFAULT_REPORT = Path(__file__).with_name("ingestion-report.json")
TERMINAL_PROCESSING = {"VECTOR_STORED"}
FAILED_PROCESSING = {"FAILED"}


def load_jsonl(path: Path) -> list[dict]:
    return [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines() if line.strip()]


def write_json(path: Path, value: dict) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_suffix(path.suffix + ".tmp")
    temporary.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    temporary.replace(path)


def api_data(payload: object, operation: str) -> object:
    if not isinstance(payload, dict):
        raise RuntimeError(f"{operation}: response is not a JSON object")
    if payload.get("code") != 0:
        raise RuntimeError(f"{operation}: code={payload.get('code')} message={payload.get('message')}")
    return payload.get("data")


class RagApi:
    def __init__(self, base_url: str, timeout: float, retries: int) -> None:
        self.base_url = base_url.rstrip("/")
        self.timeout = timeout
        self.retries = max(1, retries)
        self.token = ""
        self.opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))

    def request(
        self,
        method: str,
        path: str,
        body: bytes | None = None,
        content_type: str | None = None,
        authenticated: bool = True,
    ) -> object:
        headers = {"Accept": "application/json"}
        if content_type:
            headers["Content-Type"] = content_type
        if authenticated:
            headers["Authorization"] = f"Bearer {self.token}"
        last_error: Exception | None = None
        for attempt in range(1, self.retries + 1):
            request = urllib.request.Request(
                self.base_url + path, data=body, headers=headers, method=method
            )
            try:
                with self.opener.open(request, timeout=self.timeout) as response:
                    return json.loads(response.read().decode("utf-8"))
            except urllib.error.HTTPError as exc:
                detail = exc.read().decode("utf-8", errors="replace")[:1000]
                last_error = RuntimeError(f"HTTP {exc.code} {method} {path}: {detail}")
                if 400 <= exc.code < 500:
                    break
            except (urllib.error.URLError, TimeoutError, json.JSONDecodeError) as exc:
                last_error = exc
            if attempt < self.retries:
                time.sleep(min(2 ** (attempt - 1), 5))
        raise RuntimeError(f"{method} {path} failed after {self.retries} attempt(s): {last_error}")

    def login(self, username: str, password: str) -> None:
        payload = self.request(
            "POST",
            "/auth/login",
            json.dumps({"username": username, "password": password}).encode("utf-8"),
            "application/json",
            authenticated=False,
        )
        data = api_data(payload, "login")
        if not isinstance(data, dict) or not data.get("token"):
            raise RuntimeError("login response is missing data.token")
        self.token = str(data["token"])

    def documents(self) -> list[dict]:
        data = api_data(self.request("GET", "/document/list"), "list documents")
        if not isinstance(data, list):
            raise RuntimeError("document list response is not a list")
        return data

    def upload(self, pdf: Path, title: str, visibility: str) -> dict:
        boundary = "----llmstudy-" + uuid.uuid4().hex
        parts: list[bytes] = []
        for name, value in (("docTitle", title), ("visibility", visibility)):
            parts.append(
                f"--{boundary}\r\nContent-Disposition: form-data; name=\"{name}\"\r\n\r\n"
                f"{value}\r\n".encode("utf-8")
            )
        mime = mimetypes.guess_type(pdf.name)[0] or "application/pdf"
        parts.append(
            f"--{boundary}\r\nContent-Disposition: form-data; name=\"file\"; "
            f"filename=\"{pdf.name}\"\r\nContent-Type: {mime}\r\n\r\n".encode("utf-8")
            + pdf.read_bytes()
            + b"\r\n"
        )
        parts.append(f"--{boundary}--\r\n".encode("ascii"))
        data = api_data(
            self.request(
                "POST",
                "/document/upload",
                b"".join(parts),
                f"multipart/form-data; boundary={boundary}",
            ),
            f"upload {pdf.name}",
        )
        if not isinstance(data, dict):
            raise RuntimeError(f"upload {pdf.name}: response data is not an object")
        return data

    def version(self, doc_id: str, version_id: str) -> dict:
        data = api_data(
            self.request("GET", f"/document/{doc_id}/versions/{version_id}"),
            f"get version {version_id}",
        )
        if not isinstance(data, dict):
            raise RuntimeError(f"version {version_id}: response data is not an object")
        return data

    def publish(self, doc_id: str, version_id: str) -> dict:
        data = api_data(
            self.request(
                "POST",
                f"/document/{doc_id}/versions/{version_id}/publish",
                b'{"expectedCurrentVersionId":null}',
                "application/json",
            ),
            f"publish {version_id}",
        )
        return data if isinstance(data, dict) else {}


def resolve_pdf(record: dict, manifest_path: Path) -> Path:
    path = Path(str(record.get("local_path", "")))
    if path.is_absolute():
        return path
    return manifest_path.resolve().parents[3] / path


def new_state(manifest: Path, base_url: str) -> dict:
    return {
        "schema_version": 1,
        "manifest": str(manifest.resolve()),
        "base_url": base_url,
        "started_at": datetime.now(timezone.utc).isoformat(),
        "updated_at": None,
        "documents": {},
        "summary": {},
    }


def elasticsearch_count(base_url: str, index: str, version_id: str, timeout: float) -> int:
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    body = json.dumps({
        "query": {"term": {"metadata.version_id.keyword": version_id}}
    }).encode("utf-8")
    request = urllib.request.Request(
        f"{base_url.rstrip('/')}/{index}/_count",
        data=body,
        headers={"Content-Type": "application/json"},
        method="POST",
    )
    with opener.open(request, timeout=timeout) as response:
        payload = json.loads(response.read().decode("utf-8"))
    return int(payload["count"])


def write_report(path: Path, state: dict) -> None:
    documents = []
    for corpus_id, entry in sorted(state["documents"].items()):
        documents.append({
            "corpus_id": corpus_id,
            "filename": entry.get("filename"),
            "topic": entry.get("topic"),
            "doc_id": entry.get("doc_id"),
            "version_id": entry.get("version_id"),
            "processing_status": entry.get("processing_status"),
            "release_status": entry.get("release_status"),
            "segment_count": entry.get("segment_count"),
            "retry_count": entry.get("retry_count", 0),
            "source": entry.get("source"),
            "result": entry.get("result"),
            "failure_reason": entry.get("error_message") or entry.get("error"),
        })
    summary = {
        "expected": len(documents),
        "success": sum(item["result"] == "PUBLISHED" for item in documents),
        "failed": sum(item["result"] == "FAILED" for item in documents),
        "uploaded": sum(item["source"] == "uploaded" for item in documents),
        "skipped_existing": sum(item["source"] == "existing" for item in documents),
        "retries": sum(int(item["retry_count"] or 0) for item in documents),
        "segments": sum(int(item["segment_count"] or 0) for item in documents),
    }
    write_json(path, {
        "generated_at": datetime.now(timezone.utc).isoformat(),
        "manifest": state.get("manifest"),
        "summary": summary,
        "documents": documents,
    })


def save_state(path: Path, state: dict, expected_count: int) -> None:
    documents = state["documents"]
    statuses: dict[str, int] = {}
    for entry in documents.values():
        status = str(entry.get("result") or entry.get("release_status") or "PENDING")
        statuses[status] = statuses.get(status, 0) + 1
    state["updated_at"] = datetime.now(timezone.utc).isoformat()
    state["summary"] = {
        "expected": expected_count,
        "tracked": len(documents),
        "published": sum(1 for item in documents.values() if item.get("result") == "PUBLISHED"),
        "failed": sum(1 for item in documents.values() if item.get("result") == "FAILED"),
        "statuses": dict(sorted(statuses.items())),
    }
    write_json(path, state)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--manifest", type=Path, default=DEFAULT_MANIFEST)
    parser.add_argument("--state", type=Path, default=DEFAULT_STATE)
    parser.add_argument("--report", type=Path, default=DEFAULT_REPORT)
    parser.add_argument("--base-url", default=os.getenv("RAG_BASE_URL", "http://127.0.0.1:8080"))
    parser.add_argument("--username", default=os.getenv("RAG_INGEST_USERNAME", "alice"))
    parser.add_argument("--password-env", default="RAG_INGEST_PASSWORD")
    parser.add_argument("--use-demo-password", action="store_true")
    parser.add_argument("--visibility", choices=("PRIVATE", "ORGANIZATION", "PUBLIC"), default="PRIVATE")
    parser.add_argument("--poll-interval", type=float, default=15.0)
    parser.add_argument("--processing-timeout", type=float, default=7200.0)
    parser.add_argument("--request-timeout", type=float, default=300.0)
    parser.add_argument("--retries", type=int, default=3)
    parser.add_argument("--max-pipeline-retries", type=int, default=3)
    parser.add_argument("--elasticsearch-url", default=os.getenv("ELASTICSEARCH_URI", "http://127.0.0.1:9200"))
    parser.add_argument("--elasticsearch-index", default=os.getenv("ELASTICSEARCH_VECTOR_INDEX", "know-engine"))
    parser.add_argument("--upload-only", action="store_true")
    args = parser.parse_args(argv)

    password = os.getenv(args.password_env)
    if not password and args.use_demo_password:
        password = "ChangeMe123!"
    if not password:
        print(
            f"set {args.password_env} or pass --use-demo-password (local demo account only)",
            file=sys.stderr,
        )
        return 2

    records = [
        row for row in load_jsonl(args.manifest)
        if row.get("validation_status") == "valid"
    ]
    state = (
        json.loads(args.state.read_text(encoding="utf-8"))
        if args.state.is_file() else new_state(args.manifest, args.base_url)
    )
    state.setdefault("documents", {})
    api = RagApi(args.base_url, args.request_timeout, args.retries)
    api.login(args.username, password)
    for entry in state["documents"].values():
        # A stable-state error is retried by rag-module's compensation task. An
        # earlier ingester version treated any error_message as terminal; clear
        # that local marker and let the server-side retry budget decide.
        if entry.get("result") == "FAILED" and entry.get("doc_id") and entry.get("version_id"):
            entry.pop("result", None)
            entry.pop("error", None)
    existing_by_title = {
        str(item.get("docTitle", "")).casefold(): item for item in api.documents()
    }

    for index, record in enumerate(records, start=1):
        corpus_id = str(record["corpus_id"])
        title = Path(str(record["filename"])).stem
        entry = state["documents"].setdefault(
            corpus_id,
            {"filename": record["filename"], "title": title, "topic": record.get("topic")},
        )
        if entry.get("doc_id") and entry.get("version_id"):
            print(f"[{index}/{len(records)}] resume {corpus_id} {entry.get('processing_status', 'UNKNOWN')}")
            continue
        existing = existing_by_title.get(title.casefold())
        if existing:
            entry.update({
                "doc_id": str(existing.get("docId")),
                "version_id": str(existing.get("versionId")),
                "processing_status": existing.get("processingStatus"),
                "release_status": existing.get("releaseStatus"),
                "source": "existing",
            })
            if existing.get("releaseStatus") == "PUBLISHED":
                entry["result"] = "PUBLISHED"
            print(f"[{index}/{len(records)}] existing {corpus_id} {entry['release_status']}")
        else:
            pdf = resolve_pdf(record, args.manifest)
            if not pdf.is_file():
                entry.update({"result": "FAILED", "error": f"PDF not found: {pdf}"})
                print(f"[{index}/{len(records)}] missing {corpus_id}", file=sys.stderr)
            else:
                try:
                    uploaded = api.upload(pdf, title, args.visibility)
                    entry.update({
                        "doc_id": str(uploaded.get("docId")),
                        "version_id": str(uploaded.get("versionId")),
                        "processing_status": uploaded.get("processingStatus"),
                        "release_status": uploaded.get("releaseStatus"),
                        "content_hash": uploaded.get("contentHash"),
                        "source": "uploaded",
                    })
                    print(f"[{index}/{len(records)}] uploaded {corpus_id} version={entry['version_id']}")
                except Exception as exc:
                    entry.update({"result": "FAILED", "error": str(exc)})
                    print(f"[{index}/{len(records)}] upload failed {corpus_id}: {exc}", file=sys.stderr)
        save_state(args.state, state, len(records))

    if args.upload_only:
        print(json.dumps(state["summary"], ensure_ascii=False))
        return 1 if state["summary"]["failed"] else 0

    deadline = time.monotonic() + max(1.0, args.processing_timeout)
    while True:
        pending = []
        for corpus_id, entry in state["documents"].items():
            if entry.get("result") in {"PUBLISHED", "FAILED"}:
                continue
            try:
                version = api.version(entry["doc_id"], entry["version_id"])
                entry["processing_status"] = version.get("processingStatus")
                entry["release_status"] = version.get("releaseStatus")
                entry["retry_count"] = version.get("retryCount")
                entry["error_message"] = version.get("errorMessage")
                if entry["release_status"] == "PUBLISHED":
                    entry["result"] = "PUBLISHED"
                elif entry["processing_status"] in TERMINAL_PROCESSING and entry["release_status"] == "READY":
                    api.publish(entry["doc_id"], entry["version_id"])
                    entry["release_status"] = "PUBLISHED"
                    entry["result"] = "PUBLISHED"
                    print(f"published {corpus_id}")
                elif (
                    entry["processing_status"] in {"UPLOADED", "CONVERTED", "CHUNKED"}
                    and entry.get("error_message")
                    and int(entry.get("retry_count") or 0) >= args.max_pipeline_retries
                ):
                    entry["result"] = "FAILED"
                else:
                    pending.append(corpus_id)
            except Exception as exc:
                entry["last_poll_error"] = str(exc)
                pending.append(corpus_id)
        save_state(args.state, state, len(records))
        print(
            f"processing: published={state['summary']['published']}/{len(records)} "
            f"failed={state['summary']['failed']} pending={len(pending)}"
        )
        if not pending:
            break
        if time.monotonic() >= deadline:
            print("processing timeout reached; state is resumable", file=sys.stderr)
            return 1
        time.sleep(max(1.0, args.poll_interval))

    for entry in state["documents"].values():
        if entry.get("result") != "PUBLISHED" or not entry.get("version_id"):
            continue
        try:
            entry["segment_count"] = elasticsearch_count(
                args.elasticsearch_url,
                args.elasticsearch_index,
                str(entry["version_id"]),
                min(args.request_timeout, 30.0),
            )
        except Exception as exc:
            entry["segment_count_error"] = str(exc)
    save_state(args.state, state, len(records))
    write_report(args.report, state)

    return 0 if state["summary"]["published"] == len(records) else 1


if __name__ == "__main__":
    raise SystemExit(main())
