#!/usr/bin/env python3
"""Enterprise read-only knowledge-point recall evaluation for the canonical Gaokao corpus.

The evaluator separates three concerns that must not be conflated:

* ``seedObservations`` are the requested question-number observations (1, 3, 5).
  They are selected by neutral seed slots and are never treated as topic truth.
* ``goldSets`` are weak-supervision/manual-audit sets selected from canonical question
  stems and manifest identity.  No answer or solution text participates in selection.
* ``queryEvaluations`` run real embeddings and read-only Milvus searches.  A seed/topic
  mismatch is an explicitly invalid seed experiment, not a recall failure.

The evaluator never creates, loads, flushes, inserts, upserts, or updates Milvus.
"""
from __future__ import annotations

import argparse
from collections import Counter
import hashlib
import json
import math
import os
from pathlib import Path
import re
import sys
import time
from typing import Any, Iterable
from urllib.parse import urljoin
import uuid

import requests


PROJECT_ROOT = Path(__file__).resolve().parents[2]
DEFAULT_CORPUS_ROOT = PROJECT_ROOT / "output" / "math-paper-corpus"
DEFAULT_REPORT_PATH = PROJECT_ROOT / "output" / "acceptance" / "knowledge-point-recall-report.json"
DEFAULT_COLLECTION = "gaokao_math"
DEFAULT_EMBEDDING_URL = "http://127.0.0.1:8092/v1/embeddings"
DEFAULT_MILVUS_URI = "http://127.0.0.1:19531"
DEFAULT_EMBEDDING_MODEL = "local_bge_embedding"
VECTOR_DIMENSION = 512
VECTOR_FIELD = "vector"
DEFAULT_TOP_K = 10
DEFAULT_TIMEOUT_SECONDS = 120
DEFAULT_GOLD_PER_TOPIC = 3
ANSWER_MARKERS = ("【答案】", "【解析】", "【分析】", "【详解】")
TOPIC_ORDER = ("parabola", "probability_statistics", "spatial_vector")

# These are evidence terms for a transparent weak-supervision rule, not a claim that
# the corpus contains authoritative knowledge-point labels.  They are applied only to
# the question/options stem before ANSWER_MARKERS.
GOLD_EVIDENCE_TERMS: dict[str, tuple[str, ...]] = {
    "parabola": ("抛物线",),
    "probability_statistics": ("概率", "随机抽样", "频率", "正态分布", "频数", "相关性"),
    "spatial_vector": (
        "空间向量",
        "法向量",
        "二面角",
        "直三棱柱",
        "三棱锥",
        "四棱锥",
        "四棱柱",
    ),
}
TOPIC_QUERY_TERMS: dict[str, tuple[str, ...]] = {
    "parabola": ("抛物线", "焦点", "准线"),
    "probability_statistics": ("概率", "统计", "随机抽样", "频率", "分布"),
    "spatial_vector": ("空间向量", "立体几何", "法向量", "二面角", "垂直"),
}
OBSERVED_CONTENT_RULES: dict[str, tuple[str, ...]] = {
    "set_operation_evidence": ("集合", "交集"),
    "probability_statistics_evidence": ("概率", "随机抽样", "频率", "正态分布", "频数", "相关性"),
    "solid_geometry_volume_evidence": ("圆柱", "圆锥", "体积"),
    "spatial_geometry_evidence": (
        "空间向量",
        "法向量",
        "二面角",
        "直三棱柱",
        "三棱锥",
        "四棱锥",
        "四棱柱",
    ),
    "analytic_curve_evidence": ("抛物线", "椭圆", "双曲线"),
}

# Neutral selectors preserve the requested 1/3/5 observation group.  The selectors
# intentionally have no topic key.  Their stable list order is used only to pair one
# seed observation with each direction for a diagnostic query, never for gold labeling.
DEFAULT_SEED_MAPPING: dict[str, dict[str, str]] = {
    "seed_1": {
        "questionNumber": "1",
        "sourceFile": "2024年高考数学试卷（新课标Ⅰ卷）（解析卷）.pdf",
    },
    "seed_3": {
        "questionNumber": "3",
        "sourceFile": "2023年高考数学试卷（新课标Ⅱ卷）（解析卷）.pdf",
    },
    "seed_5": {
        "questionNumber": "5",
        "sourceFile": "2024年高考数学试卷（新课标Ⅰ卷）（空白卷）.pdf",
    },
}
SEED_SLOTS = ("seed_1", "seed_3", "seed_5")
FORBIDDEN_REFERENCE_MARKERS = (
    "http://",
    "https://",
    "file://",
    "data:",
    "base64",
    "/mnt/",
    "/app/",
    "c:\\",
    "d:\\",
    "page-images",
    "page_images",
    "pageimages",
)
OPAQUE_ASSET_ID = re.compile(r"^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
FIGURE_REFERENCE = re.compile(r"^figures/[^/\\]+$")


class AcceptanceError(RuntimeError):
    """Raised when the real acceptance input violates a published contract."""


def load_dotenv_values(path: Path) -> dict[str, str]:
    """Read local configuration without ever returning it in an acceptance report."""
    if not path.is_file():
        return {}
    values: dict[str, str] = {}
    for line in path.read_text(encoding="utf-8-sig").splitlines():
        stripped = line.strip()
        if not stripped or stripped.startswith("#") or "=" not in stripped:
            continue
        key, value = stripped.split("=", 1)
        values[key.strip()] = value.strip().strip("\"'")
    return values


def configured_value(name: str, dotenv: dict[str, str], default: str = "") -> str:
    """Prefer the process environment while retaining repository Compose defaults."""
    return os.environ.get(name, "").strip() or dotenv.get(name, "").strip() or default


def sha256_file(path: Path) -> str:
    """Hash a canonical file in bounded chunks for source and figure verification."""
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def document_ref(source_file: str, source_sha256: str) -> str:
    """Recompute the opaque documentRef used by the canonical publisher."""
    return str(uuid.uuid5(uuid.NAMESPACE_URL, f"{source_file}\n{source_sha256}"))


def _safe_relative_path(paper_root: Path, relative: str, required_prefix: str = "") -> Path:
    """Resolve a manifest path only after enforcing the canonical paper boundary."""
    candidate_text = str(relative).replace("\\", "/")
    if not candidate_text or candidate_text.startswith("/") or ".." in candidate_text.split("/"):
        raise AcceptanceError("manifest path escapes canonical paper")
    if required_prefix and not candidate_text.startswith(required_prefix):
        raise AcceptanceError("manifest asset is outside the required canonical directory")
    candidate = (paper_root / Path(candidate_text)).resolve()
    root = paper_root.resolve()
    if not candidate.is_relative_to(root):
        raise AcceptanceError("manifest path escapes canonical paper")
    return candidate


def extract_question_stem(markdown: str) -> str:
    """Return only the question/options text before answer and solution sections."""
    lines = markdown.splitlines()
    body_start = 0
    for index, line in enumerate(lines):
        if line.startswith("- 跨页连续："):
            body_start = index + 1
            break
    body = "\n".join(lines[body_start:]).strip()
    marker_positions = [position for marker in ANSWER_MARKERS if (position := body.find(marker)) >= 0]
    if marker_positions:
        body = body[: min(marker_positions)].rstrip()
    # Image locations are neither query evidence nor visible references in this report.
    body = re.sub(r"!\[[^\]]*\]\([^)]*\)", "", body)
    body = re.sub(r"\n{3,}", "\n\n", body).strip()
    if not body:
        raise AcceptanceError("canonical question Markdown has no question stem")
    return body


def normalize_query_text(text: str, limit: int = 1200) -> str:
    """Collapse Markdown whitespace while keeping the source stem text unchanged semantically."""
    return " ".join(text.split())[:limit]


def summarize_stem(stem: str, limit: int = 360) -> str:
    """Create a bounded answer-free summary for result inspection."""
    normalized = normalize_query_text(stem, limit + 1)
    return normalized if len(normalized) <= limit else normalized[:limit].rstrip() + "..."


def _parse_seed_mapping(raw: str) -> dict[str, dict[str, str]]:
    """Parse neutral seed slots; topic-keyed mappings are rejected by design."""
    if not raw.strip():
        return {slot: dict(selector) for slot, selector in DEFAULT_SEED_MAPPING.items()}
    source = raw[1:] if raw.startswith("@") else raw
    try:
        parsed = json.loads(Path(source).read_text(encoding="utf-8") if raw.startswith("@") else source)
    except (OSError, json.JSONDecodeError) as error:
        raise AcceptanceError("--seed-mapping must be JSON or @JSON_FILE") from error
    if isinstance(parsed, list):
        parsed = {str(item.get("seedSlot", "")): item for item in parsed if isinstance(item, dict)}
    if not isinstance(parsed, dict) or set(parsed) != set(SEED_SLOTS):
        raise AcceptanceError("seed mapping must contain exactly seed_1, seed_3, and seed_5")
    result: dict[str, dict[str, str]] = {}
    for slot in SEED_SLOTS:
        value = parsed[slot]
        if not isinstance(value, dict):
            raise AcceptanceError(f"seed mapping for {slot} must be an object")
        number = str(value.get("questionNumber", value.get("sampleQuestionNumber", ""))).strip()
        source_file = str(value.get("sourceFile", "")).strip()
        if number not in {"1", "3", "5"} or not source_file:
            raise AcceptanceError(f"seed mapping for {slot} needs sourceFile and one of question numbers 1, 3, 5")
        result[slot] = {"questionNumber": number, "sourceFile": source_file}
    if {item["questionNumber"] for item in result.values()} != {"1", "3", "5"}:
        raise AcceptanceError("seed mapping must preserve question numbers 1, 3, and 5")
    return result


def load_manifest_index(corpus_root: Path) -> dict[str, tuple[Path, dict[str, Any]]]:
    """Load only published source manifests from the canonical corpus root."""
    if not corpus_root.is_dir():
        raise AcceptanceError("canonical corpus root does not exist")
    manifests: dict[str, tuple[Path, dict[str, Any]]] = {}
    for manifest_path in sorted(corpus_root.glob("*/source-manifest.json")):
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
        source_file = str(manifest.get("documentFullName", "")).strip()
        if not source_file or source_file in manifests:
            raise AcceptanceError("canonical manifests must have unique complete source names")
        manifests[source_file] = (manifest_path.parent, manifest)
    if not manifests:
        raise AcceptanceError("no canonical source manifests were published")
    return manifests


def _manifest_question(manifest: dict[str, Any], question_number: str) -> dict[str, Any]:
    """Return exactly one manifest question by printed question number."""
    matches = [item for item in manifest.get("questions", []) if str(item.get("questionNumber", "")) == question_number]
    if len(matches) != 1:
        raise AcceptanceError(f"canonical manifest does not contain one question {question_number}")
    return matches[0]


def _read_question(manifests: dict[str, tuple[Path, dict[str, Any]]], source_file: str, question_number: str) -> dict[str, Any]:
    """Read a canonical question and bind it to immutable source identity."""
    if source_file not in manifests:
        raise AcceptanceError(f"source is not a canonical manifest: {source_file}")
    paper_root, manifest = manifests[source_file]
    question = _manifest_question(manifest, question_number)
    markdown_name = str(question.get("questionMarkdown", ""))
    markdown_path = _safe_relative_path(paper_root, markdown_name, "questions/")
    if not markdown_path.is_file():
        raise AcceptanceError("canonical question Markdown is missing")
    source_sha256 = str(manifest.get("sourceSha256", "")).strip()
    if not re.fullmatch(r"[0-9a-fA-F]{64}", source_sha256):
        raise AcceptanceError("canonical source manifest has no valid source hash")
    stem = extract_question_stem(markdown_path.read_text(encoding="utf-8"))
    question_id = str(question.get("questionId", "")).strip()
    if not question_id:
        raise AcceptanceError("canonical question has no questionId")
    return {
        "sourceFile": source_file,
        "sourceSha256": source_sha256,
        "questionNumber": question_number,
        "questionId": question_id,
        "documentRef": document_ref(source_file, source_sha256),
        "stem": stem,
        "stemSummary": summarize_stem(stem),
        "paperRoot": paper_root,
        "manifest": manifest,
        "question": question,
    }


def _observed_content_labels(stem: str) -> list[dict[str, Any]]:
    """Report evidence labels found in a seed stem without claiming authoritative tags."""
    labels: list[dict[str, Any]] = []
    for label, terms in OBSERVED_CONTENT_RULES.items():
        hits = [term for term in terms if term in stem]
        if hits:
            labels.append({"label": label, "evidenceTerms": hits})
    return labels


def _topic_alignment(stem: str, topic: str) -> dict[str, Any]:
    """Determine seed alignment from the same weak-supervision evidence rule as gold selection."""
    hits = [term for term in GOLD_EVIDENCE_TERMS[topic] if term in stem]
    return {
        "aligned": bool(hits),
        "evidenceTerms": hits,
        "decision": "aligned_by_stem_evidence" if hits else "sample_topic_mismatch",
    }


def build_seed_samples(corpus_root: Path, mapping: dict[str, dict[str, str]]) -> list[dict[str, Any]]:
    """Build the neutral 1/3/5 seed observation group from current canonical files."""
    manifests = load_manifest_index(corpus_root)
    samples: list[dict[str, Any]] = []
    for slot in SEED_SLOTS:
        selector = mapping[slot]
        item = _read_question(manifests, selector["sourceFile"], selector["questionNumber"])
        item["seedSlot"] = slot
        item["observedContentLabels"] = _observed_content_labels(item["stem"])
        item["topicAlignment"] = {topic: _topic_alignment(item["stem"], topic) for topic in TOPIC_ORDER}
        samples.append(item)
    if {item["questionNumber"] for item in samples} != {"1", "3", "5"}:
        raise AcceptanceError("seed observations must contain question numbers 1, 3, and 5")
    return samples


def build_question_catalog(manifests: dict[str, tuple[Path, dict[str, Any]]]) -> dict[tuple[str, str], dict[str, Any]]:
    """Scan all published question Markdown files into an auditable identity catalog."""
    catalog: dict[tuple[str, str], dict[str, Any]] = {}
    for source_file in sorted(manifests):
        manifest = manifests[source_file][1]
        for question in manifest.get("questions", []):
            number = str(question.get("questionNumber", "")).strip()
            if not number:
                continue
            item = _read_question(manifests, source_file, number)
            key = (source_file, number)
            if key in catalog:
                raise AcceptanceError(f"duplicate canonical question identity: {source_file} #{number}")
            catalog[key] = item
    if not catalog:
        raise AcceptanceError("canonical corpus has no question records")
    return catalog


def _numeric_question_number(value: str) -> int:
    """Sort printed question numbers numerically while keeping malformed values last."""
    return int(value) if value.isdigit() else 10**9


def _gold_identity(item: dict[str, Any]) -> tuple[str, str, str]:
    return item["sourceFile"], item["questionNumber"], item["questionId"]


def select_gold_sets(
    catalog: dict[tuple[str, str], dict[str, Any]],
    seeds: list[dict[str, Any]],
    gold_per_topic: int = DEFAULT_GOLD_PER_TOPIC,
) -> tuple[dict[str, list[dict[str, Any]]], dict[str, dict[str, Any]]]:
    """Select source-diverse weak-supervision gold refs without answer/solution text."""
    if gold_per_topic < 2:
        raise ValueError("gold_per_topic must be at least 2")
    excluded_ids = {item["questionId"] for item in seeds}
    gold_sets: dict[str, list[dict[str, Any]]] = {}
    selection_audits: dict[str, dict[str, Any]] = {}
    for topic in TOPIC_ORDER:
        candidates: list[dict[str, Any]] = []
        for item in catalog.values():
            evidence_terms = [term for term in GOLD_EVIDENCE_TERMS[topic] if term in item["stem"]]
            if evidence_terms:
                candidates.append({**item, "evidenceTerms": evidence_terms})
        candidates.sort(key=lambda item: (item["sourceFile"], _numeric_question_number(item["questionNumber"]), item["questionId"]))
        eligible = [item for item in candidates if item["questionId"] not in excluded_ids]
        selected: list[dict[str, Any]] = []
        selected_sources: set[str] = set()
        # Prefer different complete source files so the gold set is not one duplicated paper.
        for item in eligible:
            if item["sourceFile"] in selected_sources:
                continue
            selected.append(item)
            selected_sources.add(item["sourceFile"])
            if len(selected) == gold_per_topic:
                break
        if len(selected) < gold_per_topic:
            for item in eligible:
                if item in selected:
                    continue
                selected.append(item)
                if len(selected) == gold_per_topic:
                    break
        if len(selected) < 2:
            raise AcceptanceError(f"weak-supervision gold set for {topic} has fewer than two eligible questions")
        gold_sets[topic] = selected
        selection_audits[topic] = {
            "supervision": "weak_supervision_manual_audit",
            "authoritativeKnowledgeLabelsPresent": False,
            "evidenceSource": "canonical question stem before answer/solution markers",
            "rule": "stem contains at least one configured strong domain evidence term; deterministic source-diverse ordering; seed questionIds excluded",
            "configuredEvidenceTerms": list(GOLD_EVIDENCE_TERMS[topic]),
            "candidateCount": len(candidates),
            "excludedSeedCount": len(candidates) - len(eligible),
            "selectedCount": len(selected),
        }
    return gold_sets, selection_audits


def public_question_ref(item: dict[str, Any], evidence_terms: list[str] | None = None) -> dict[str, Any]:
    """Serialize canonical identity and bounded answer-free text without local paths."""
    result: dict[str, Any] = {
        "sourceFile": item["sourceFile"],
        "sourceSha256": item["sourceSha256"],
        "questionNumber": item["questionNumber"],
        "questionId": item["questionId"],
        "documentRef": item["documentRef"],
        "stemSummary": item["stemSummary"],
    }
    if evidence_terms is not None:
        result["evidenceTerms"] = evidence_terms
    return result


def build_query(topic: str, stem: str = "", mode: str = "stem_only", include_topic_terms: bool | None = None) -> str:
    """Build one of the explicit query modes using no answer or solution text.

    ``include_topic_terms`` remains accepted for compatibility with the previous focused
    tests, but new callers should use the named mode values in the report.
    """
    if topic not in TOPIC_ORDER:
        raise AcceptanceError(f"unsupported knowledge-point direction: {topic}")
    if include_topic_terms is not None:
        mode = "aligned_topic_plus_stem" if include_topic_terms else "seed_stem_only"
    if mode == "topic_only":
        return normalize_query_text(" ".join(TOPIC_QUERY_TERMS[topic]))
    normalized_stem = normalize_query_text(extract_question_stem(stem))
    if not normalized_stem:
        raise AcceptanceError("stem-only query cannot be empty")
    if mode == "seed_stem_only":
        return normalized_stem
    if mode == "aligned_topic_plus_stem":
        return normalize_query_text(" ".join((*TOPIC_QUERY_TERMS[topic], normalized_stem)))
    if mode == "stem_only":
        return normalized_stem
    raise AcceptanceError(f"unsupported query mode: {mode}")


def _walk_strings(value: Any) -> Iterable[str]:
    if isinstance(value, str):
        yield value
    elif isinstance(value, dict):
        for child in value.values():
            yield from _walk_strings(child)
    elif isinstance(value, list):
        for child in value:
            yield from _walk_strings(child)


def _walk_keys(value: Any) -> Iterable[str]:
    """Yield metadata field names so hidden path fields cannot pass the contract."""
    if isinstance(value, dict):
        for key, child in value.items():
            yield str(key)
            yield from _walk_keys(child)
    elif isinstance(value, list):
        for child in value:
            yield from _walk_keys(child)


def _metadata_from_hit(hit: dict[str, Any]) -> dict[str, Any]:
    """Decode Milvus JSON metadata while keeping response text out of the report."""
    entity = hit.get("entity") if isinstance(hit.get("entity"), dict) else {}
    raw = hit.get("metadata", entity.get("metadata", {}))
    if isinstance(raw, str):
        try:
            raw = json.loads(raw)
        except json.JSONDecodeError:
            raw = {}
    return raw if isinstance(raw, dict) else {}


def hit_identity(hit: dict[str, Any]) -> dict[str, str]:
    """Extract canonical identity fields from a Milvus row without exposing hit text."""
    entity = hit.get("entity") if isinstance(hit.get("entity"), dict) else {}
    metadata = _metadata_from_hit(hit)
    source_file = str(metadata.get("sourceFile") or metadata.get("documentFullName") or entity.get("sourceFile") or "").strip()
    question_number = str(metadata.get("questionNumber") or entity.get("questionNumber") or "").strip()
    document_reference = str(metadata.get("documentRef") or entity.get("documentRef") or "").strip()
    question_id = str(metadata.get("questionId") or entity.get("questionId") or "").strip()
    record_id = str(hit.get("id") or entity.get("id") or "").strip()
    if not question_id and question_number and OPAQUE_ASSET_ID.fullmatch(record_id):
        # Ingestion uses the canonical question UUID as the Milvus primary key.
        question_id = record_id
    return {
        "sourceFile": source_file,
        "questionNumber": question_number,
        "questionId": question_id,
        "documentRef": document_reference,
        "recordId": record_id,
    }


def _hit_key(identity: dict[str, str], fallback: str) -> tuple[str, ...]:
    """Build the source/question primary key before falling back to an opaque row ID."""
    if identity["sourceFile"] and identity["questionNumber"]:
        return ("sourceQuestion", identity["sourceFile"], identity["questionNumber"])
    if identity["questionId"]:
        return ("questionId", identity["questionId"])
    return ("row", fallback)


def deduplicate_hits(hits: list[dict[str, Any]]) -> list[dict[str, Any]]:
    """Collapse repeated canonical question rows while retaining the earliest best rank."""
    seen: set[tuple[str, ...]] = set()
    unique: list[dict[str, Any]] = []
    for raw_rank, hit in enumerate(hits, start=1):
        identity = hit_identity(hit)
        key = _hit_key(identity, str(hit.get("id") or raw_rank))
        if key in seen:
            continue
        seen.add(key)
        unique.append({"hit": hit, "rawRank": raw_rank})
    return unique


def raw_duplicate_observation(hits: list[dict[str, Any]], top_k: int) -> dict[str, Any]:
    """Report repeated identities in the raw result window before diagnostic collapse."""
    if top_k < 1:
        raise ValueError("top_k must be positive")
    window = hits[:top_k]
    grouped: dict[tuple[str, ...], list[dict[str, Any]]] = {}
    for raw_rank, hit in enumerate(window, start=1):
        identity = hit_identity(hit)
        key = _hit_key(identity, str(hit.get("id") or raw_rank))
        grouped.setdefault(key, []).append({"identity": identity, "rawRank": raw_rank})
    repeated = []
    for key, rows in grouped.items():
        if len(rows) < 2:
            continue
        identity = rows[0]["identity"]
        repeated.append({
            "keyType": key[0],
            "sourceFile": identity["sourceFile"],
            "questionNumber": identity["questionNumber"],
            "questionId": identity["questionId"],
            "occupancy": len(rows),
            "rawRanks": [row["rawRank"] for row in rows],
        })
    repeated.sort(key=lambda item: (item["rawRanks"][0], item["sourceFile"], item["questionNumber"]))
    return {
        "rawTopKDuplicateKeyCount": len(repeated),
        "rawTopKDuplicateRowCount": sum(item["occupancy"] - 1 for item in repeated),
        "rawTopKLargestRepeatedKeyOccupancy": max((item["occupancy"] for item in repeated), default=1),
        "rawTopKRepeatedKeys": repeated,
    }

def _matches_gold(identity: dict[str, str], gold: dict[str, Any]) -> bool:
    """Require complete source/question identity and reject a conflicting questionId."""
    if identity["sourceFile"] != gold["sourceFile"] or identity["questionNumber"] != gold["questionNumber"]:
        return False
    return not identity["questionId"] or identity["questionId"] == gold["questionId"]


def _grade_for_rank(rank: int | None, top_k: int) -> str:
    """Apply the stable A/B/C/F rank bands without treating rank zero as a hit."""
    if rank == 1:
        return "A"
    if rank is not None and 2 <= rank <= min(3, top_k):
        return "B"
    if rank is not None and 4 <= rank <= top_k:
        return "C"
    return "F"


def score_gold_recall(
    hits: list[dict[str, Any]],
    gold_refs: list[dict[str, Any]],
    top_k: int,
) -> tuple[dict[str, Any], list[dict[str, Any]]]:
    """Score Recall@k, Precision@k, MRR, nDCG and rank bands over a gold set."""
    if top_k < 1:
        raise ValueError("top_k must be positive")
    unique_rows = deduplicate_hits(hits)
    window = unique_rows[:top_k]
    hit_gold: dict[str, int] = {}
    false_positive_count = 0
    row_matches: list[dict[str, Any]] = []
    for rank, row in enumerate(window, start=1):
        identity = hit_identity(row["hit"])
        matched = [gold for gold in gold_refs if _matches_gold(identity, gold)]
        if matched:
            for gold in matched:
                hit_gold[gold["questionId"]] = rank
        else:
            false_positive_count += 1
        row_matches.append({"rank": rank, "rawRank": row["rawRank"], "goldQuestionIds": [gold["questionId"] for gold in matched]})
    ranks = sorted(hit_gold.values())
    first_rank = ranks[0] if ranks else None
    gold_count = len(gold_refs)
    relevant_count = len(hit_gold)
    returned_count = len(window)
    recall = relevant_count / gold_count if gold_count else 0.0
    precision = relevant_count / returned_count if returned_count else 0.0
    mrr = 1 / first_rank if first_rank else 0.0
    dcg = sum(1 / math.log2(rank + 1) for rank in ranks)
    ideal_count = min(gold_count, top_k)
    ideal_dcg = sum(1 / math.log2(rank + 1) for rank in range(1, ideal_count + 1))
    ndcg = dcg / ideal_dcg if ideal_dcg else 0.0
    score = {
        "topKHit": bool(ranks),
        "goldCount": gold_count,
        "relevantHitCount": relevant_count,
        "falsePositiveCount": false_positive_count,
        "returnedUniqueHitCount": returned_count,
        "recallAtK": round(recall, 6),
        "precisionAtK": round(precision, 6),
        "mrr": round(mrr, 6),
        "nDCGAtK": round(ndcg, 6),
        "firstGoldHitRank": first_rank,
        "goldHitRanks": {gold["questionId"]: hit_gold.get(gold["questionId"]) for gold in gold_refs},
        "grade": _grade_for_rank(first_rank, top_k),
    }
    return score, row_matches


def _asset_ids_from_metadata(metadata: dict[str, Any]) -> list[str]:
    """Extract only question-figure IDs; internal page IDs are not visible image assets."""
    assets = metadata.get("questionAssets")
    if isinstance(assets, list):
        return [
            str(item.get("assetId", "")).strip()
            for item in assets
            if isinstance(item, dict) and str(item.get("assetId", "")).strip()
        ]
    direct = metadata.get("assetIds")
    page_asset_ids = {
        str(value).strip()
        for value in metadata.get("pageAssetIds", [])
        if str(value).strip()
    } if isinstance(metadata.get("pageAssetIds"), list) else set()
    if isinstance(direct, list):
        return [str(value).strip() for value in direct if str(value).strip() and str(value).strip() not in page_asset_ids]
    return []


def resolve_question_assets(
    paper_root: Path,
    question_entry: dict[str, Any],
    hit: dict[str, Any],
    expected_source_sha256: str = "",
) -> dict[str, Any]:
    """Separate vector metadata asset presence from controlled manifest figure resolution."""
    reasons: list[str] = []
    expected_figures: dict[str, str] = {}
    manifest_asset_ids = {str(value).strip() for value in question_entry.get("assetIds", []) if str(value).strip()}
    manifest_errors: list[str] = []
    for asset in question_entry.get("assets", []):
        if not isinstance(asset, dict):
            manifest_errors.append("manifest_asset_not_object")
            continue
        asset_id = str(asset.get("assetId", "")).strip()
        relative = str(asset.get("canonicalAssetPath", "")).replace("\\", "/").strip()
        if not asset_id or not OPAQUE_ASSET_ID.fullmatch(asset_id):
            manifest_errors.append("manifest_asset_id_not_opaque")
        if not FIGURE_REFERENCE.fullmatch(relative):
            manifest_errors.append("manifest_asset_not_figures")
            continue
        try:
            path = _safe_relative_path(paper_root, relative, "figures/")
        except AcceptanceError:
            manifest_errors.append("manifest_asset_path_invalid")
            continue
        if not path.is_file():
            manifest_errors.append("manifest_figure_missing")
        expected_hash = str(asset.get("assetSha256", "")).strip()
        if expected_hash and path.is_file() and sha256_file(path) != expected_hash:
            manifest_errors.append("manifest_figure_hash_mismatch")
        if expected_source_sha256 and str(asset.get("sourceSha256", "")).strip() != expected_source_sha256:
            manifest_errors.append("manifest_figure_source_hash_mismatch")
        expected_figures[asset_id] = relative
    if any(asset_id not in manifest_asset_ids for asset_id in expected_figures):
        manifest_errors.append("manifest_figure_id_missing_from_question_asset_ids")

    metadata = _metadata_from_hit(hit)
    metadata_asset_ids = _asset_ids_from_metadata(metadata)
    metadata_keys = list(_walk_keys(metadata))
    metadata_values = list(_walk_strings(metadata))
    forbidden_key_markers = ("path", "root", "directory", "url", "base64", "dataurl", "sourcepageimage")
    forbidden_keys = [
        key for key in metadata_keys
        if any(marker in key.lower() for marker in forbidden_key_markers)
    ]
    forbidden_value = any(marker in value.lower() for value in metadata_values for marker in FORBIDDEN_REFERENCE_MARKERS)
    metadata_paths_present = any(
        isinstance(item, dict) and any(key in item for key in ("canonicalAssetPath", "assetPath", "sourcePath"))
        for item in (metadata.get("questionAssets") if isinstance(metadata.get("questionAssets"), list) else [])
    )
    if forbidden_keys or forbidden_value or metadata_paths_present:
        reasons.append("forbidden_asset_reference")
    for asset_id in metadata_asset_ids:
        if not OPAQUE_ASSET_ID.fullmatch(asset_id):
            reasons.append("metadata_asset_id_not_opaque")
        elif asset_id not in expected_figures:
            reasons.append("metadata_asset_not_bound_to_manifest_figure")
    if expected_figures and not metadata_asset_ids:
        reasons.append("metadata_asset_ids_missing")
    if manifest_errors:
        reasons.extend(manifest_errors)
    return {
        "status": "pass" if not reasons else "fail",
        "reasons": sorted(set(reasons)),
        "vectorMetadataHasAssetIds": bool(metadata_asset_ids),
        "vectorMetadataAssetIds": metadata_asset_ids,
        "controlledManifestResolution": "pass" if expected_figures and not manifest_errors else ("not_required" if not expected_figures else "fail"),
        "controlledManifestFigureCount": len(expected_figures),
        "canonicalFigureReferences": sorted(set(expected_figures.values())) if not manifest_errors else [],
        "forbiddenReferenceDetected": bool(forbidden_keys or forbidden_value or metadata_paths_present),
    }


def validate_question_assets(
    paper_root: Path,
    question_entry: dict[str, Any],
    hit: dict[str, Any],
    expected_source_sha256: str = "",
) -> dict[str, Any]:
    """Backward-compatible name for the controlled asset resolver."""
    return resolve_question_assets(paper_root, question_entry, hit, expected_source_sha256)


def _safe_distance(hit: dict[str, Any]) -> float | None:
    """Return a finite Milvus distance for report display, if present."""
    value = hit.get("distance", hit.get("score"))
    try:
        number = float(value)
    except (TypeError, ValueError):
        return None
    return number if math.isfinite(number) else None


def _safe_hit_text_summary(hit: dict[str, Any]) -> str | None:
    """Summarize returned Milvus text before answer/solution markers for audit display."""
    entity = hit.get("entity") if isinstance(hit.get("entity"), dict) else {}
    raw = hit.get("text", entity.get("text", ""))
    if not isinstance(raw, str) or not raw.strip():
        return None
    try:
        stem = extract_question_stem(raw)
    except AcceptanceError:
        stem = raw
    return summarize_stem(stem)


def _gold_ids_for_hit(hit: dict[str, Any], gold_refs: list[dict[str, Any]]) -> list[str]:
    identity = hit_identity(hit)
    return [gold["questionId"] for gold in gold_refs if _matches_gold(identity, gold)]


def audit_hit_contract(
    hit: dict[str, Any],
    rank: int,
    raw_rank: int,
    manifests: dict[str, tuple[Path, dict[str, Any]]],
    catalog: dict[tuple[str, str], dict[str, Any]],
    gold_refs: list[dict[str, Any]],
) -> tuple[dict[str, Any], list[str]]:
    """Return one inspectable hit row and explicit contract failure categories."""
    identity = hit_identity(hit)
    entry: dict[str, Any] = {
        "rank": rank,
        "rawRank": raw_rank,
        "recordId": identity["recordId"],
        "sourceFile": identity["sourceFile"],
        "questionNumber": identity["questionNumber"],
        "questionId": identity["questionId"],
        "documentRef": identity["documentRef"],
        "distance": _safe_distance(hit),
        "textSummary": _safe_hit_text_summary(hit),
        "isGold": bool(_gold_ids_for_hit(hit, gold_refs)),
        "goldQuestionIds": _gold_ids_for_hit(hit, gold_refs),
    }
    errors: list[str] = []
    if not identity["questionNumber"]:
        entry["recordType"] = "full_document_or_non_question"
        entry["stemSummary"] = None
        entry["assetContract"] = {"status": "not_question_record", "reasons": []}
        return entry, errors
    entry["recordType"] = "question"
    item = catalog.get((identity["sourceFile"], identity["questionNumber"]))
    if item is None:
        errors.append("source_or_question_not_in_canonical_catalog")
        entry["stemSummary"] = None
        entry["assetContract"] = {"status": "fail", "reasons": ["source_or_question_not_in_canonical_catalog"]}
        return entry, errors
    entry["stemSummary"] = item["stemSummary"]
    if identity["questionId"] and identity["questionId"] != item["questionId"]:
        errors.append("question_id_mismatch")
    expected_ref = item["documentRef"]
    if not identity["documentRef"]:
        errors.append("document_ref_missing")
    elif identity["documentRef"] != expected_ref:
        errors.append("document_ref_mismatch")
    paper_root, manifest = manifests[identity["sourceFile"]]
    asset_contract = resolve_question_assets(
        paper_root,
        item["question"],
        hit,
        expected_source_sha256=item["sourceSha256"],
    )
    entry["assetContract"] = asset_contract
    errors.extend(asset_contract["reasons"])
    return entry, errors


def _post_json(uri: str, token: str, path: str, payload: dict[str, Any], timeout: int) -> dict[str, Any]:
    """POST a bounded read request without copying response text into the report."""
    headers = {"Content-Type": "application/json"}
    if token:
        headers["Authorization"] = f"Bearer {token}"
    endpoint = urljoin(uri.rstrip("/") + "/", path.lstrip("/"))
    last_error: Exception | None = None
    for attempt in range(3):
        try:
            response = requests.post(endpoint, headers=headers, json=payload, timeout=timeout)
            body = response.json()
            if not response.ok or body.get("code", 0) != 0:
                raise AcceptanceError(f"read endpoint returned HTTP {response.status_code}")
            return body
        except (requests.RequestException, ValueError, AcceptanceError) as error:
            last_error = error
            if attempt < 2:
                time.sleep(1 + attempt)
    raise AcceptanceError(f"real service request failed: {type(last_error).__name__}") from last_error


def embed_queries(queries: list[str], url: str, api_key: str, timeout: int) -> list[list[float]]:
    """Call the real local embedding endpoint and enforce the 512-dimensional contract."""
    headers = {"Content-Type": "application/json"}
    if api_key:
        headers["Authorization"] = f"Bearer {api_key}"
    try:
        response = requests.post(
            url,
            headers=headers,
            json={"model": DEFAULT_EMBEDDING_MODEL, "input": queries},
            timeout=timeout,
        )
        body = response.json()
    except (requests.RequestException, ValueError) as error:
        raise AcceptanceError(f"real embedding request failed: {type(error).__name__}") from error
    if not response.ok:
        raise AcceptanceError(f"real embedding endpoint returned HTTP {response.status_code}")
    vectors = [item.get("embedding") for item in body.get("data", []) if isinstance(item, dict)]
    if len(vectors) != len(queries) or any(not isinstance(vector, list) or len(vector) != VECTOR_DIMENSION for vector in vectors):
        raise AcceptanceError("real embedding response violates the 512-dimensional contract")
    return vectors


def search_milvus(vector: list[float], collection: str, uri: str, token: str, top_k: int, timeout: int) -> list[dict[str, Any]]:
    """Run one read-only COSINE vector search against the configured collection."""
    payload = {
        "collectionName": collection,
        "data": [vector],
        "annsField": VECTOR_FIELD,
        "limit": top_k,
        "outputFields": ["id", "metadata", "text"],
        "searchParams": {"metricType": "COSINE", "params": {}},
    }
    response = _post_json(uri, token, "/v2/vectordb/entities/search", payload, timeout)
    raw = response.get("data", [])
    if not isinstance(raw, list):
        raise AcceptanceError("Milvus search response has no list data field")
    hits: list[dict[str, Any]] = []
    pending: list[Any] = list(raw)
    while pending:
        item = pending.pop(0)
        if isinstance(item, list):
            pending[0:0] = item
        elif isinstance(item, dict):
            hits.append(item)
        else:
            raise AcceptanceError("Milvus search response contains a non-object hit")
    return hits


def _aggregate_scores(scores: list[dict[str, Any]]) -> dict[str, Any]:
    """Macro-average applicable query scores and expose sample size explicitly."""
    if not scores:
        return {
            "queryCount": 0,
            "recallAtK": 0.0,
            "precisionAtK": 0.0,
            "mrr": 0.0,
            "nDCGAtK": 0.0,
            "grades": {grade: 0 for grade in ("A", "B", "C", "F")},
        }
    grades = {grade: sum(1 for score in scores if score["grade"] == grade) for grade in ("A", "B", "C", "F")}
    return {
        "queryCount": len(scores),
        "recallAtK": round(sum(float(score["recallAtK"]) for score in scores) / len(scores), 6),
        "precisionAtK": round(sum(float(score["precisionAtK"]) for score in scores) / len(scores), 6),
        "mrr": round(sum(float(score["mrr"]) for score in scores) / len(scores), 6),
        "nDCGAtK": round(sum(float(score["nDCGAtK"]) for score in scores) / len(scores), 6),
        "grades": grades,
    }


def _report_contains_secret(value: Any, secrets: set[str]) -> bool:
    """Reject configured secret values before writing the report."""
    return any(secret and secret in text for text in _walk_strings(value) for secret in secrets)


def _public_seed_observation(seed: dict[str, Any]) -> dict[str, Any]:
    """Serialize a seed with its actual answer-free stem and all direction decisions."""
    return {
        "seedSlot": seed["seedSlot"],
        "sourceFile": seed["sourceFile"],
        "sourceSha256": seed["sourceSha256"],
        "questionNumber": seed["questionNumber"],
        "questionId": seed["questionId"],
        "documentRef": seed["documentRef"],
        "stem": normalize_query_text(seed["stem"], 2000),
        "stemSummary": seed["stemSummary"],
        "observedContentLabels": seed["observedContentLabels"],
        "topicAlignment": seed["topicAlignment"],
    }


def run_acceptance(args: argparse.Namespace) -> dict[str, Any]:
    """Run all real embedding/search comparisons and persist a secret-free report."""
    started_epoch = time.time()
    run_id = f"kp-recall-{uuid.uuid4()}"
    dotenv = load_dotenv_values(PROJECT_ROOT / ".env")
    corpus_root = args.corpus_root.resolve()
    seed_mapping = _parse_seed_mapping(args.seed_mapping)
    manifests = load_manifest_index(corpus_root)
    seeds = build_seed_samples(corpus_root, seed_mapping)
    catalog = build_question_catalog(manifests)
    gold_sets, selection_audits = select_gold_sets(catalog, seeds, args.gold_per_topic)
    embedding_url = args.embedding_url or configured_value("MATH_AGENT_EMBEDDING_BASE_URL", dotenv, DEFAULT_EMBEDDING_URL)
    milvus_uri = args.milvus_uri or configured_value("MATH_AGENT_VECTOR_INDEX_MILVUS_URI", dotenv, DEFAULT_MILVUS_URI)
    worker_key = configured_value("MATH_AGENT_WORKER_API_KEY", dotenv) or configured_value("MATH_AGENT_EMBEDDING_API_KEY", dotenv)
    token = configured_value("MATH_AGENT_MILVUS_TOKEN", dotenv)
    if not token:
        password = configured_value("MATH_AGENT_MILVUS_ROOT_PASSWORD", dotenv)
        token = f"root:{password}" if password else ""

    # Pair directions with seed slots by stable neutral order solely for diagnostics.
    planned: list[dict[str, Any]] = []
    for topic, seed in zip(TOPIC_ORDER, seeds, strict=True):
        aligned = bool(seed["topicAlignment"][topic]["aligned"])
        planned.append({
            "direction": topic,
            "seedSlot": seed["seedSlot"],
            "mode": "topic_only",
            "evaluationStatus": "evaluated",
            "query": build_query(topic, seed["stem"], mode="topic_only"),
        })
        planned.append({
            "direction": topic,
            "seedSlot": seed["seedSlot"],
            "mode": "seed_stem_only",
            "evaluationStatus": "evaluated" if aligned else "invalid_seed_mismatch",
            "query": build_query(topic, seed["stem"], mode="seed_stem_only"),
        })
        planned.append({
            "direction": topic,
            "seedSlot": seed["seedSlot"],
            "mode": "aligned_topic_plus_stem",
            "evaluationStatus": "evaluated" if aligned else "not_run_seed_mismatch",
            "query": build_query(topic, seed["stem"], mode="aligned_topic_plus_stem") if aligned else None,
        })
    executable = [item for item in planned if item["query"] is not None]
    vectors = embed_queries([item["query"] for item in executable], embedding_url, worker_key, args.timeout_seconds)
    vector_by_plan = {id(item): vector for item, vector in zip(executable, vectors, strict=True)}

    direction_cases: dict[str, list[dict[str, Any]]] = {topic: [] for topic in TOPIC_ORDER}
    contract_errors: Counter[str] = Counter()
    diagnostic_counts: Counter[str] = Counter()
    for item in planned:
        topic = item["direction"]
        seed = next(seed for seed in seeds if seed["seedSlot"] == item["seedSlot"])
        gold_items = gold_sets[topic]
        gold_refs = [public_question_ref(gold, gold["evidenceTerms"]) for gold in gold_items]
        evaluation_status = item["evaluationStatus"]
        if evaluation_status == "not_run_seed_mismatch":
            case = {
                "direction": topic,
                "seedSlot": item["seedSlot"],
                "mode": item["mode"],
                "evaluationStatus": evaluation_status,
                "query": None,
                "score": None,
                "hits": [],
                "goldRefs": gold_refs,
                "note": "aligned topic+stem is intentionally skipped because the assigned seed stem is not aligned",
            }
            direction_cases[topic].append(case)
            continue
        hits = search_milvus(vector_by_plan[id(item)], args.collection, args.milvus_uri or milvus_uri, token, args.top_k, args.timeout_seconds)
        unique_rows = deduplicate_hits(hits)
        score: dict[str, Any] | None = None
        row_matches: list[dict[str, Any]] = []
        if evaluation_status == "evaluated":
            score, row_matches = score_gold_recall(hits, gold_refs, args.top_k)
        else:
            # Run the invalid seed query for diagnosis, but never turn its result into a
            # direction failure or a gold metric.
            diagnostic_counts["sample_topic_mismatch"] += 1
        hit_details: list[dict[str, Any]] = []
        for rank, row in enumerate(unique_rows[:args.top_k], start=1):
            detail, errors = audit_hit_contract(row["hit"], rank, row["rawRank"], manifests, catalog, gold_refs)
            hit_details.append(detail)
            contract_errors.update(errors)
            if not detail["isGold"]:
                diagnostic_counts["false_positive_hit"] += 1
        duplicate_count = len(hits) - len(unique_rows)
        raw_duplicate_stats = raw_duplicate_observation(hits, args.top_k)
        if duplicate_count:
            diagnostic_counts["duplicate_hit_collapsed"] += duplicate_count
        case = {
            "direction": topic,
            "seedSlot": seed["seedSlot"],
            "mode": item["mode"],
            "evaluationStatus": evaluation_status,
            "query": item["query"],
            "queryInput": "topic terms only" if item["mode"] == "topic_only" else "answer-free canonical seed stem",
            "score": score,
            "goldRefs": gold_refs,
            "returnedHitCount": len(hits),
            **raw_duplicate_stats,
            "duplicateHitCountCollapsed": duplicate_count,
            "hits": hit_details,
            "rowMatches": row_matches,
        }
        direction_cases[topic].append(case)

    direction_reports: dict[str, Any] = {}
    all_applicable_scores: list[dict[str, Any]] = []
    mode_scores: dict[str, list[dict[str, Any]]] = {"topic_only": [], "seed_stem_only": [], "aligned_topic_plus_stem": []}
    for topic in TOPIC_ORDER:
        cases = direction_cases[topic]
        for case in cases:
            if case["evaluationStatus"] == "evaluated" and case["score"] is not None:
                all_applicable_scores.append(case["score"])
                mode_scores[case["mode"]].append(case["score"])
        seed = next(seed for seed in seeds if seed["seedSlot"] == cases[0]["seedSlot"])
        direction_reports[topic] = {
            "seed": {
                "seedSlot": seed["seedSlot"],
                "questionNumber": seed["questionNumber"],
                "sourceFile": seed["sourceFile"],
                "questionId": seed["questionId"],
                "topicAlignment": seed["topicAlignment"][topic],
            },
            "goldSelection": selection_audits[topic],
            "goldRefs": [public_question_ref(item, [term for term in GOLD_EVIDENCE_TERMS[topic] if term in item["stem"]]) for item in gold_sets[topic]],
            "queryEvaluations": cases,
            "metricsByMode": {
                mode: {
                    "evaluationStatus": "evaluated" if any(case["mode"] == mode and case["evaluationStatus"] == "evaluated" for case in cases) else (
                        "invalid_seed_mismatch" if any(case["mode"] == mode and case["evaluationStatus"] == "invalid_seed_mismatch" for case in cases) else "not_run_seed_mismatch"
                    ),
                    "score": next((case["score"] for case in cases if case["mode"] == mode and case["evaluationStatus"] == "evaluated"), None),
                }
                for mode in ("topic_only", "seed_stem_only", "aligned_topic_plus_stem")
            },
        }

    report: dict[str, Any] = {
        "status": "asset_contract_failed" if contract_errors else "verified",
        "runId": run_id,
        "generatedAtUtc": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
        "durationSeconds": round(max(0.0, time.time() - started_epoch), 3),
        "readOnly": True,
        "corpusEvidence": {
            "sourceManifestCount": len(manifests),
            "questionCount": len(catalog),
            "sourceFiles": [
                {"sourceFile": source, "sourceSha256": manifest[1].get("sourceSha256", "")}
                for source, manifest in sorted(manifests.items())
            ],
            "selection": "current canonical source-manifest.json and questions/*.md only",
        },
        "collection": args.collection,
        "embedding": {
            "model": DEFAULT_EMBEDDING_MODEL,
            "dimension": VECTOR_DIMENSION,
            "transport": "real_local_endpoint",
        },
        "milvus": {
            "transport": "real_read_only_search",
            "metric": "COSINE",
            "vectorField": VECTOR_FIELD,
            "outputFields": ["id", "metadata", "text"],
            "writeOperations": [],
        },
        "topK": args.top_k,
        "seedAssignment": {
            "seedSlots": list(SEED_SLOTS),
            "questionNumbers": [seed["questionNumber"] for seed in seeds],
            "directionPairing": "stable seed-slot order only; not a topic label or gold truth",
        },
        "queryModes": {
            "topic_only": "configured topic evidence terms only",
            "seed_stem_only": "answer-free stem of the assigned neutral seed slot",
            "aligned_topic_plus_stem": "configured topic terms plus seed stem; executed only when stem evidence aligns",
        },
        "seedObservations": [_public_seed_observation(seed) for seed in seeds],
        "goldSets": {
            topic: {
                "selection": selection_audits[topic],
                "refs": [public_question_ref(item, [term for term in GOLD_EVIDENCE_TERMS[topic] if term in item["stem"]]) for item in gold_sets[topic]],
            }
            for topic in TOPIC_ORDER
        },
        "directions": direction_reports,
        "overallMetrics": {
            "applicableQueryMetrics": _aggregate_scores(all_applicable_scores),
            "byMode": {
                mode: {
                    "excludedInvalidOrSkippedCount": sum(
                        1
                        for topic_cases in direction_cases.values()
                        for case in topic_cases
                        if case["mode"] == mode and case["evaluationStatus"] != "evaluated"
                    ),
                    "metrics": _aggregate_scores(scores),
                }
                for mode, scores in mode_scores.items()
            },
        },
        "assetContract": {
            "status": "fail" if contract_errors else "pass",
            "vectorMetadataContract": "opaque assetId only; no visible locator is required",
            "controlledResolutionContract": "manifest question assets resolve only to canonical figures/ references",
            "forbiddenReferenceKinds": ["page_image", "remote_locator", "encoded_binary", "filesystem_path"],
            "errors": sorted(contract_errors),
        },
        "failureClassification": {
            "counts": dict(sorted({**diagnostic_counts, **Counter(contract_errors)}.items())),
            "seedMismatchIsExcludedFromDirectionMetrics": True,
            "goldMissesAreCountedOnlyForEvaluatedModes": True,
        },
        "limitations": [
            "The canonical manifests do not publish authoritative knowledge-point labels.",
            "Gold sets are weak-supervision/manual-audit samples selected from answer-free canonical stems; they are not automatic ground truth.",
            "Seed slots 1/3/5 are an observation group. Stable slot order pairs them to directions only for query diagnostics; a mismatch is invalid rather than a recall failure.",
            "Milvus outputFields intentionally omit text; each question hit receives a bounded answer-free stem summary from the verified canonical Markdown.",
        ],
    }
    secrets = {worker_key, token, configured_value("OPENAI_API_KEY", dotenv)}
    if _report_contains_secret(report, secrets):
        raise AcceptanceError("refusing to write a report containing a configured secret")
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    return report


def main() -> int:
    parser = argparse.ArgumentParser(description="Read-only real Gaokao knowledge-point recall evaluation")
    parser.add_argument("--corpus-root", type=Path, default=DEFAULT_CORPUS_ROOT)
    parser.add_argument("--collection", default=DEFAULT_COLLECTION)
    parser.add_argument("--top-k", type=int, default=DEFAULT_TOP_K)
    parser.add_argument("--gold-per-topic", type=int, default=DEFAULT_GOLD_PER_TOPIC)
    parser.add_argument("--output", type=Path, default=DEFAULT_REPORT_PATH)
    parser.add_argument("--embedding-url", default="", help="override the local embedding endpoint")
    parser.add_argument("--milvus-uri", default="", help="override the Milvus REST base URL")
    parser.add_argument("--timeout-seconds", type=int, default=DEFAULT_TIMEOUT_SECONDS)
    parser.add_argument(
        "--seed-mapping",
        "--sample-mapping",
        dest="seed_mapping",
        default="",
        help="neutral JSON or @JSON_FILE mapping seed_1/seed_3/seed_5 to sourceFile and questionNumber",
    )
    args = parser.parse_args()
    if args.top_k < 1 or args.timeout_seconds < 1 or args.gold_per_topic < 2:
        parser.error("--top-k, --timeout-seconds, and --gold-per-topic must be valid positive values")
    try:
        report = run_acceptance(args)
    except (AcceptanceError, OSError, ValueError) as error:
        print(f"acceptance failed: {error}", file=sys.stderr)
        return 1
    print(json.dumps({"status": report["status"], "runId": report["runId"], "output": str(args.output.resolve()), "overallMetrics": report["overallMetrics"]}, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
