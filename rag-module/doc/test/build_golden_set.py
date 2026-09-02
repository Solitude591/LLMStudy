#!/usr/bin/env python3
"""Build a deterministic 222-question Golden Set from verified PDF page text."""

from __future__ import annotations

import hashlib
import json
import re
import subprocess
import unicodedata
from collections import Counter, defaultdict
from datetime import datetime, timezone
from pathlib import Path

HERE = Path(__file__).resolve().parent
REPO_ROOT = HERE.parents[2]
MANIFEST = REPO_ROOT / "rag-module/doc/evaluation-corpus/corpus-manifest.jsonl"
INGESTION_STATE = REPO_ROOT / "rag-module/doc/evaluation-corpus/ingestion-state.json"


def load_jsonl(path: Path) -> list[dict]:
    return [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines() if line.strip()]


def normalize(text: str) -> str:
    text = unicodedata.normalize("NFKC", text)
    text = re.sub(r"-\s*\n\s*", "", text)
    text = re.sub(r"\s+", " ", text)
    return text.strip()


def extract_pages(path: Path) -> list[str]:
    process = subprocess.run(
        ["pdftotext", "-raw", str(path), "-"],
        check=True,
        capture_output=True,
        text=True,
    )
    pages = [normalize(page) for page in process.stdout.split("\f")]
    return [page for page in pages if page]


def sentences(text: str) -> list[str]:
    candidates = re.split(r"(?<=[.!?])\s+(?=[A-Z0-9(])", text)
    result: list[str] = []
    for candidate in candidates:
        candidate = normalize(candidate).strip(" •")
        if not 70 <= len(candidate) <= 330:
            continue
        if candidate[-1] not in ".!?":
            continue
        lowered = candidate.casefold()
        if any(marker in lowered for marker in (
            "arxiv:", "copyright", "all rights reserved", "http://", "https://",
            "references ", "proceedings of", "corresponding author", "equal contribution",
        )):
            continue
        if re.match(r"^(figure|fig\.|table)\s", candidate, re.I):
            continue
        if len(re.findall(r"[A-Za-z]", candidate)) < 45:
            continue
        if abs(candidate.count("(") - candidate.count(")")) > 1:
            continue
        if candidate.count("[") > 5 or candidate.count("{") > 1:
            continue
        result.append(candidate)
    return result


def choose(pages: list[str], page_numbers: list[int], used: set[str]) -> tuple[int, str]:
    for page_number in page_numbers:
        if not 1 <= page_number <= len(pages):
            continue
        ranked = sorted(
            sentences(pages[page_number - 1]),
            key=lambda value: (
                not bool(re.search(r"\b(we|our|propose|introduce|show|demonstrate|find)\b", value, re.I)),
                abs(len(value) - 180),
            ),
        )
        for candidate in ranked:
            key = candidate.casefold()
            if key not in used:
                used.add(key)
                return page_number, candidate
    raise RuntimeError(f"could not select evidence from pages {page_numbers}")


def choose_table(pages: list[str], used: set[str]) -> tuple[int, str]:
    patterns = (
        re.compile(r"\bTable\s+(?:\d+|[IVXLC]+)\s*[:.—-]?\s*(.{35,260}?[.!?])", re.I),
        re.compile(r"\bTable\s+(?:\d+|[IVXLC]+)\b(.{35,220}?)(?=\b(?:Figure|Table|Section)\b|$)", re.I),
    )
    for page_number, page in enumerate(pages, start=1):
        for pattern in patterns:
            for match in pattern.finditer(page):
                quote = normalize(match.group(0))
                if 45 <= len(quote) <= 330 and quote.casefold() not in used:
                    used.add(quote.casefold())
                    return page_number, quote
    for page_number, page in enumerate(pages, start=1):
        for sentence in sentences(page):
            if "table" in sentence.casefold() and sentence.casefold() not in used:
                used.add(sentence.casefold())
                return page_number, sentence
    raise RuntimeError("PDF does not contain an extractable table caption or table explanation")


def evidence(record: dict, page: int, section: str, quote: str) -> dict:
    item = {
        "document_file": record["filename"],
        "document_title": record["title"],
        "page_start": page,
        "page_end": page,
        "section": section,
        "evidence_quote": quote,
    }
    if record.get("document_id"):
        item["document_id"] = record["document_id"]
    if record.get("version_id"):
        item["version_id"] = record["version_id"]
    return item


def base_row(question_id: str, category: str, question: str, reference: str,
             evidence_items: list[dict], records: list[dict], language: str,
             difficulty: str) -> dict:
    return {
        "question_id": question_id,
        "primary_category": category,
        "user_input": question,
        "reference": reference,
        "reference_contexts": [item["evidence_quote"] for item in evidence_items],
        "is_answerable": category != "unanswerable",
        "relevant_evidence": evidence_items,
        "topic": records[0]["topic"] if len({record["topic"] for record in records}) == 1 else "cross-topic",
        "language": language,
        "difficulty": difficulty,
        "query_scope": "cross_document" if len(records) > 1 else "single_document",
        "evidence_corpus_ids": [record["corpus_id"] for record in records],
    }


def short_title(title: str) -> str:
    for token in ("nnU-Net", "Attention U-Net", "U-Net", "MedSAM", "SAM-Med2D", "DINOv2", "ColBERT", "REALM", "HyDE", "RAGAS", "ReAct", "Toolformer", "Reflexion", "HELM", "GSM8K", "ConvNeXt", "CLIP", "DETR"):
        if token.casefold() in title.casefold():
            return token
    words = re.findall(r"[A-Za-z0-9-]+", title)
    return " ".join(words[:4])


def write_jsonl(path: Path, rows: list[dict]) -> None:
    with path.open("w", encoding="utf-8") as stream:
        for row in rows:
            stream.write(json.dumps(row, ensure_ascii=False) + "\n")


def split_rows(rows: list[dict]) -> tuple[list[dict], list[dict]]:
    dev: list[dict] = []
    test: list[dict] = []
    category_index: Counter[str] = Counter()
    for row in rows:
        category = row["primary_category"]
        category_index[category] += 1
        number = category_index[category]
        if category == "fact":
            target = test if number % 2 == 1 else dev
        else:
            target = test if number % 5 in (1, 4) else dev
        target.append(row)
    return dev, test


def main() -> int:
    manifest = load_jsonl(MANIFEST)
    if len(manifest) < 30 or any(record.get("validation_status") != "valid" for record in manifest):
        raise RuntimeError("corpus manifest must contain at least 30 validated PDFs")
    if INGESTION_STATE.is_file():
        ingestion = json.loads(INGESTION_STATE.read_text(encoding="utf-8"))
        documents = ingestion.get("documents", {})
        for record in manifest:
            entry = documents.get(record["corpus_id"], {})
            if entry.get("doc_id") and entry.get("version_id"):
                record["document_id"] = str(entry["doc_id"])
                record["version_id"] = str(entry["version_id"])

    extracted: dict[str, dict] = {}
    for index, record in enumerate(manifest, start=1):
        pdf = REPO_ROOT / record["local_path"]
        pages = extract_pages(pdf)
        if len(pages) != record["pages"]:
            raise RuntimeError(f"page extraction mismatch for {record['filename']}")
        used: set[str] = set()
        opening = choose(pages, [1, 2], used)
        early = choose(pages, list(range(2, min(len(pages), 7) + 1)), used)
        later_start = max(2, int(len(pages) * 0.55))
        later = choose(pages, list(range(later_start, max(later_start + 1, len(pages) - 1))), used)
        table = choose_table(pages, used)
        extra = choose(pages, list(range(2, max(3, len(pages) - 1))), used)
        extracted[record["corpus_id"]] = {
            "record": record,
            "opening": opening,
            "early": early,
            "later": later,
            "table": table,
            "extra": extra,
        }
        print(f"[{index}/{len(manifest)}] extracted {record['corpus_id']}")

    rows: list[dict] = []
    fact_templates_zh = (
        "《{title}》在第 {page} 页如何描述其核心做法或发现？",
        "根据《{title}》第 {page} 页，作者明确陈述了什么设计选择？",
        "《{title}》对所研究问题给出的关键论断是什么？请依据第 {page} 页作答。",
        "阅读《{title}》第 {page} 页后，可以直接核实哪项方法或结果陈述？",
        "《{title}》第 {page} 页怎样概括该工作的主要技术点？",
        "在《{title}》中，作者用什么陈述说明方案的作用？依据第 {page} 页回答。",
    )
    table_templates = (
        "According to the table caption or explanation on page {page} of {title}, what comparison or analysis does the table present?",
        "《{title}》第 {page} 页的表格说明具体比较或汇总了什么？",
        "请定位《{title}》第 {page} 页的表格文字：该表关注的实验或分析对象是什么？",
        "What does the table-related passage on page {page} of {title} say the table reports?",
        "《{title}》的表格证据位于第 {page} 页；其说明文字直接陈述了什么？",
    )

    # Two fact questions per paper: one English abstract query and one varied Chinese query.
    for index, record in enumerate(manifest):
        data = extracted[record["corpus_id"]]
        page, quote = data["opening"]
        rows.append(base_row(
            f"fact-{len([r for r in rows if r['primary_category']=='fact']) + 1:03d}",
            "fact",
            f"What main contribution or finding does the abstract/opening of {record['title']} state?",
            quote,
            [evidence(record, page, "Abstract or opening", quote)],
            [record], "en", "easy",
        ))
        page, quote = data["early"]
        template = fact_templates_zh[index % len(fact_templates_zh)]
        rows.append(base_row(
            f"fact-{len([r for r in rows if r['primary_category']=='fact']) + 1:03d}",
            "fact", template.format(title=record["title"], page=page), quote,
            [evidence(record, page, "Early method or analysis", quote)],
            [record], "zh", "easy",
        ))

    # Six additional, non-identical factual probes reach the required 70.
    for record in manifest[:6]:
        page, quote = extracted[record["corpus_id"]]["extra"]
        rows.append(base_row(
            f"fact-{len([r for r in rows if r['primary_category']=='fact']) + 1:03d}",
            "fact",
            f"Which specific statement on page {page} of {record['title']} is relevant to understanding its implementation or evaluation?",
            quote,
            [evidence(record, page, "Implementation or evaluation", quote)],
            [record], "en", "medium",
        ))

    # One page-located table question per PDF (32 total).
    for index, record in enumerate(manifest):
        page, quote = extracted[record["corpus_id"]]["table"]
        template = table_templates[index % len(table_templates)]
        language = "en" if template.startswith(("According", "What")) else "zh"
        rows.append(base_row(
            f"table-{index + 1:03d}", "table",
            template.format(title=record["title"], page=page), quote,
            [evidence(record, page, "Table caption or table explanation", quote)],
            [record], language, "medium",
        ))

    # One cross-section question per PDF plus three deeper variants (35 total).
    for index, record in enumerate(manifest):
        opening_page, opening_quote = extracted[record["corpus_id"]]["opening"]
        later_page, later_quote = extracted[record["corpus_id"]]["later"]
        if opening_page == later_page:
            raise RuntimeError(f"cross-section pages are not distinct for {record['filename']}")
        question = (
            f"How does {record['title']} connect the motivation or claim on page {opening_page} "
            f"with the later design/result statement on page {later_page}?"
            if index % 3 == 0 else
            f"综合《{record['title']}》第 {opening_page} 页与第 {later_page} 页的证据，论文前期主张与后续论述如何对应？"
        )
        language = "en" if question.startswith("How") else "zh"
        rows.append(base_row(
            f"cross-section-{index + 1:03d}", "cross_section", question,
            f"Opening: {opening_quote} Later: {later_quote}",
            [
                evidence(record, opening_page, "Abstract or opening", opening_quote),
                evidence(record, later_page, "Later method, results, or discussion", later_quote),
            ],
            [record], language, "hard",
        ))
    for offset, record in enumerate(manifest[:3], start=33):
        early_page, early_quote = extracted[record["corpus_id"]]["early"]
        later_page, later_quote = extracted[record["corpus_id"]]["later"]
        rows.append(base_row(
            f"cross-section-{offset:03d}", "cross_section",
            f"《{record['title']}》在第 {early_page} 页提出的具体做法，与第 {later_page} 页的后续陈述怎样共同界定该方案？",
            f"Earlier section: {early_quote} Later section: {later_quote}",
            [
                evidence(record, early_page, "Early method or analysis", early_quote),
                evidence(record, later_page, "Later method, results, or discussion", later_quote),
            ],
            [record], "zh", "hard",
        ))

    # 35 real within-cluster comparisons, each backed by two PDFs.
    by_topic: dict[str, list[dict]] = defaultdict(list)
    for record in manifest:
        by_topic[record["topic"]].append(record)
    pairs: list[tuple[dict, dict]] = []
    for records in by_topic.values():
        for index in range(8):
            pairs.append((records[index], records[(index + 1) % 8]))
    pairs.extend([
        (by_topic["medical-segmentation-foundation-models"][0], by_topic["medical-segmentation-foundation-models"][4]),
        (by_topic["rag-embedding-retrieval-reranking"][0], by_topic["rag-embedding-retrieval-reranking"][6]),
        (by_topic["llm-reasoning-agents-evaluation"][0], by_topic["llm-reasoning-agents-evaluation"][7]),
    ])
    cross_document_templates = (
        "对比《{left}》与《{right}》的所引原文，两篇论文分别如何表述自己的贡献或发现？",
        "把《{left}》的核心陈述与《{right}》的对应段落并列起来：二者各自强调什么？",
        "从引用证据看，《{left}》和《{right}》对自身方法或发现的定位有何不同？",
        "Compare the cited statements from {left} and {right}: how does each paper characterize its own contribution or finding?",
        "若要区分《{left}》与《{right}》的主要贡献，所引段落分别提供了什么直接证据？",
        "请只依据给定段落，概括《{left}》和《{right}》各自声称完成了什么。",
    )
    for index, (left, right) in enumerate(pairs, start=1):
        left_page, left_quote = extracted[left["corpus_id"]]["opening"]
        right_page, right_quote = extracted[right["corpus_id"]]["opening"]
        template = cross_document_templates[(index - 1) % len(cross_document_templates)]
        question = template.format(left=left["title"], right=right["title"])
        rows.append(base_row(
            f"cross-document-{index:03d}", "cross_document", question,
            f"{left['title']}: {left_quote} {right['title']}: {right_quote}",
            [
                evidence(left, left_page, "Abstract or opening", left_quote),
                evidence(right, right_page, "Abstract or opening", right_quote),
            ],
            [left, right], "en" if question.startswith("Compare") else "zh", "hard",
        ))

    # 25 resolvable colloquial/abbreviated queries.
    ambiguous_templates = (
        "刚才提到那个“{short}”工作，它在第 {page} 页到底明确说了什么？",
        "那个常被简称成“{short}”的方案，第 {page} 页给出的直接陈述是什么？",
        "顺着前文的“{short}”继续问：作者在第 {page} 页具体主张了什么？",
        "你说的那篇“{short}”论文里，第 {page} 页哪句话能回答这个追问？",
        "In that '{short}' paper we were discussing, what concrete claim is made on page {page}?",
    )
    for index, record in enumerate(manifest[:25], start=1):
        page, quote = extracted[record["corpus_id"]]["early"]
        shorthand = short_title(record["title"])
        question = ambiguous_templates[(index - 1) % len(ambiguous_templates)].format(
            short=shorthand, page=page
        )
        rows.append(base_row(
            f"ambiguous-{index:03d}", "ambiguous", question, quote,
            [evidence(record, page, "Resolved shorthand: " + shorthand, quote)],
            [record], "en" if question.startswith("In") else "mixed", "medium",
        ))

    unanswerable_prompts = (
        "请给出《{title}》实验期间首席作者家庭住址的完整门牌号。",
        "What exact serial number did the primary training GPU have in {title}?",
        "《{title}》的匿名审稿人真实姓名分别是什么？",
        "请从《{title}》中查出作者为该实验支付的精确电费账单金额。",
        "Which private patient names are listed in the raw training records for {title}?",
        "《{title}》未公开补充材料的 SHA-256 校验值是多少？",
        "请说明《{title}》每次训练产生的精确千克二氧化碳排放量。",
    )
    for index, record in enumerate(manifest[:25], start=1):
        prompt = unanswerable_prompts[(index - 1) % len(unanswerable_prompts)].format(title=record["title"])
        rows.append(base_row(
            f"unanswerable-{index:03d}", "unanswerable", prompt,
            "根据给定论文无法回答；该问题要求的是论文未提供的私人、硬件序列号、账单、匿名审稿或未公开数据，不能据常识补全。",
            [], [record], "en" if prompt.startswith(("What", "Which")) else "zh", "adversarial",
        ))

    counts = Counter(row["primary_category"] for row in rows)
    expected = {
        "fact": 70, "table": 32, "cross_section": 35,
        "cross_document": 35, "ambiguous": 25, "unanswerable": 25,
    }
    if counts != Counter(expected) or len(rows) != 222:
        raise RuntimeError(f"unexpected distribution: {counts}, total={len(rows)}")

    dev, test = split_rows(rows)
    write_jsonl(HERE / "golden-set-all.jsonl", rows)
    write_jsonl(HERE / "golden-set-dev.jsonl", dev)
    write_jsonl(HERE / "golden-set-test.jsonl", test)

    document_coverage = Counter()
    for row in rows:
        if row["is_answerable"]:
            document_coverage.update(set(row["evidence_corpus_ids"]))
    summary = {
        "created_at": datetime.now(timezone.utc).astimezone().isoformat(),
        "question_count": len(rows),
        "answerable_count": sum(row["is_answerable"] for row in rows),
        "unanswerable_count": sum(not row["is_answerable"] for row in rows),
        "category_counts": dict(counts),
        "language_counts": dict(Counter(row["language"] for row in rows)),
        "difficulty_counts": dict(Counter(row["difficulty"] for row in rows)),
        "topic_counts": dict(Counter(row["topic"] for row in rows)),
        "document_answerable_coverage": dict(sorted(document_coverage.items())),
        "split_counts": {"dev": len(dev), "test": len(test)},
        "split_sha256": {
            name: hashlib.sha256((HERE / name).read_bytes()).hexdigest()
            for name in ("golden-set-all.jsonl", "golden-set-dev.jsonl", "golden-set-test.jsonl")
        },
    }
    (HERE / "golden-set-summary.json").write_text(
        json.dumps(summary, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )
    changelog = HERE / "golden-set-changelog.md"
    line = (
        f"- {summary['created_at']}: rebuilt 222 extraction-backed questions; "
        f"SHA-256 in golden-set-summary.json. document/version IDs taken from "
        f"ingestion-state.json. No test-split answers were inspected.\n"
    )
    if changelog.is_file():
        changelog.write_text(changelog.read_text(encoding="utf-8").rstrip() + "\n" + line, encoding="utf-8")
    else:
        changelog.write_text("# Golden Set changelog\n\n" + line, encoding="utf-8")
    print(json.dumps(summary, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
