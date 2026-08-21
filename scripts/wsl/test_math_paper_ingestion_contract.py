"""Contract tests for provider selection, question-asset enrichment and Milvus search envelopes."""
from __future__ import annotations

import importlib.util
import json
from pathlib import Path
import sys

import pytest


SCRIPT_PATH = Path(__file__).with_name("run_2024_luna_milvus_ingestion.py")
SPECIFICATION = importlib.util.spec_from_file_location("math_paper_ingestion", SCRIPT_PATH)
assert SPECIFICATION and SPECIFICATION.loader
INGESTION = importlib.util.module_from_spec(SPECIFICATION)
sys.modules[SPECIFICATION.name] = INGESTION
SPECIFICATION.loader.exec_module(INGESTION)


ASSET_SCRIPT_PATH = Path(__file__).with_name("extract_math_paper_assets.py")
ASSET_SPECIFICATION = importlib.util.spec_from_file_location("math_paper_assets", ASSET_SCRIPT_PATH)
assert ASSET_SPECIFICATION and ASSET_SPECIFICATION.loader
ASSETS = importlib.util.module_from_spec(ASSET_SPECIFICATION)
sys.modules[ASSET_SPECIFICATION.name] = ASSETS
ASSET_SPECIFICATION.loader.exec_module(ASSETS)



def test_selected_files_accepts_nested_explicit_pdf_path(tmp_path: Path) -> None:
    """Categorized source folders remain valid without allowing a directory scan."""
    source_root = tmp_path / "source"
    selected = source_root / "national" / "paper.pdf"
    selected.parent.mkdir(parents=True)
    selected.write_bytes(b"pdf")

    assert INGESTION.resolve_selected_files({"selectedFiles": ["national/paper.pdf"]}, source_root) == [selected.resolve()]


@pytest.mark.parametrize("selector", ["../paper.pdf", "/tmp/paper.pdf", "nested\\paper.pdf", "paper.txt"])
def test_selected_files_rejects_unsafe_or_non_pdf_paths(tmp_path: Path, selector: str) -> None:
    """Source selection rejects traversal, host paths, platform separators, and non-PDF inputs."""
    source_root = tmp_path / "source"
    source_root.mkdir()

    with pytest.raises((ValueError, FileNotFoundError)):
        INGESTION.resolve_selected_files({"selectedFiles": [selector]}, source_root)


def test_selected_files_rejects_legacy_or_duplicate_contracts(tmp_path: Path) -> None:
    """A migration cannot silently combine old flat names with the new strict whitelist."""
    source_root = tmp_path / "source"
    source_root.mkdir()
    (source_root / "paper.pdf").write_bytes(b"pdf")

    with pytest.raises(ValueError, match="selectedFiles"):
        INGESTION.resolve_selected_files({"selectedFiles": ["paper.pdf"], "selectedFileNames": ["paper.pdf"]}, source_root)
    with pytest.raises(ValueError, match="duplicate"):
        INGESTION.resolve_selected_files({"selectedFiles": ["paper.pdf", "paper.pdf"]}, source_root)


def test_default_collection_matches_canonical_java_retrieval() -> None:
    """Default ingestion and Java canonical retrieval share the single production collection."""
    assert INGESTION.DEFAULT_COLLECTION == "gaokao_math"


def test_recognized_question_keeps_source_bound_question_assets() -> None:
    """A visual question receives only its matching pre-verified figure assets."""
    response = {
        "choices": [{"message": {"content": '{"questions":[{"number":"19","text":"如图求解","latex":[],"confidence":0.9}]}'}}]
    }
    assets = {"19": [{"assetId": "asset-opaque", "assetSha256": "a" * 64, "sourceSha256": "b" * 64}]}

    questions = INGESTION.recognized_questions(response, "数学模拟卷.pdf", 2, "terra", assets)

    assert questions[0]["metadata"]["extraction"] == "TERRA_VISUAL_PAGE"
    assert questions[0]["metadata"]["questionAssets"] == assets["19"]


def test_search_hits_accepts_flat_and_nested_milvus_v2_responses() -> None:
    """Both deployed Milvus v2 response envelopes preserve every result row."""
    flat = {"data": [{"id": "first"}, {"id": "second"}]}
    nested = {"data": [[{"id": "first"}], [{"id": "second"}]]}

    assert [item["id"] for item in INGESTION.search_hits(flat)] == ["first", "second"]
    assert [item["id"] for item in INGESTION.search_hits(nested)] == ["first", "second"]


def test_canonical_publication_keeps_full_text_questions_and_source_images(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    """规范目录必须保留全文、逐题 Markdown 和可校验的原始页图/题图谱系。"""
    source = tmp_path / "数学模拟卷.pdf"
    source.write_bytes(b"pdf-source")
    asset_root = tmp_path / "assets"
    page_image = asset_root / "page-images" / "page-001.png"
    figure = asset_root / "figures" / "q001.png"
    page_image.parent.mkdir(parents=True)
    figure.parent.mkdir(parents=True)
    page_image.write_bytes(b"rendered-page")
    figure.write_bytes(b"source-figure")
    monkeypatch.setattr(INGESTION, "PROJECT_ROOT", tmp_path)
    question = {
        "id": "question-1",
        "text": "1. 求函数值",
        "metadata": {
            "sourceFile": source.name,
            "pageStart": 1,
            "pageEnd": 1,
            "questionNumber": "1",
            "questionAssets": [{
                "assetId": "asset-question-1",
                "assetSha256": INGESTION.sha256_file(figure),
                "sourceSha256": INGESTION.sha256_file(source),
                "_sourceAssetPath": figure,
            }],
        },
    }

    published = INGESTION.publish_canonical_paper(
        tmp_path / "corpus", source, INGESTION.sha256_file(source), {1: "第 1 页完整公式 $x^2$"}, [question], asset_root)

    paper_root = published["paperRoot"]
    assert paper_root.name == source.name
    assert "第 1 页完整公式" in (paper_root / "document.md").read_text(encoding="utf-8")
    assert (paper_root / "questions" / "q-001.md").is_file()
    assert INGESTION.sha256_file(paper_root / "page-images" / "page-001.png") == INGESTION.sha256_file(page_image)
    assert INGESTION.sha256_file(paper_root / "figures" / "q-001-01.png") == INGESTION.sha256_file(figure)
    assert question["metadata"]["pageAssetIds"]
    assert question["metadata"]["questionAssets"] == [{
        "assetId": "asset-question-1",
        "assetSha256": INGESTION.sha256_file(figure),
        "sourceSha256": INGESTION.sha256_file(source),
    }]
    manifest = json.loads((paper_root / "source-manifest.json").read_text(encoding="utf-8"))
    assert manifest["documentMarkdownSha256"] == INGESTION.sha256_file(paper_root / "document.md")
    assert manifest["pages"] == [{
        "pageNo": 1,
        "canonicalAssetPath": "page-images/page-001.png",
        "assetId": question["metadata"]["pageAssetIds"][0],
        "assetSha256": INGESTION.sha256_file(page_image),
    }]
    assert manifest["questions"][0]["questionMarkdown"] == "questions/q-001.md"
    assert manifest["questions"][0]["sourcePages"] == [1]
    assert manifest["questions"][0]["assetIds"] == ["asset-question-1", question["metadata"]["pageAssetIds"][0]]


def test_python_document_ref_matches_java_uuid5_contract() -> None:
    """Published document references use actual newlines, matching Java's UUIDv5 recomputation."""
    document_name = "2024 高考数学.pdf"
    source_hash = "a" * 64

    python_ref = str(INGESTION.uuid.uuid5(INGESTION.uuid.NAMESPACE_URL, f"{document_name}\n{source_hash}"))

    assert python_ref == "01ff8f4f-4738-5c57-a817-1ce9beb79337"


def test_vector_metadata_rejects_all_filesystem_keys_and_values() -> None:
    """向量记录只能携带不透明引用和谱系，发布路径不得跨越 RAG 边界。"""
    safe = {
        "metadata": {
            "documentRef": "opaque-document-ref",
            "sourceSha256": "a" * 64,
            "questionAssets": [{"assetId": "opaque-asset", "assetSha256": "b" * 64}],
        }
    }

    assert INGESTION.vector_metadata(safe) == safe["metadata"]
    with pytest.raises(ValueError, match="filesystem key"):
        INGESTION.vector_metadata({"metadata": {"canonicalDocumentPath": "document.md"}})
    with pytest.raises(ValueError, match="filesystem value"):
        INGESTION.vector_metadata({"metadata": {"documentRef": "/app/data/math-paper-corpus/document.md"}})


def test_resolve_vision_bridge_container_uses_only_a_healthy_compose_worker(monkeypatch: pytest.MonkeyPatch) -> None:
    """运行器自动选取 Compose 当前健康 worker，不依赖短暂容器 ID。"""
    class Result:
        def __init__(self, returncode: int, stdout: str):
            self.returncode = returncode
            self.stdout = stdout

    calls: list[list[str]] = []

    def run(command: list[str], **_kwargs: object) -> Result:
        calls.append(command)
        if command[-2:] == ["-q", "ai-worker"]:
            return Result(0, "worker-id\n")
        return Result(0, "running healthy\n")

    monkeypatch.setattr(INGESTION.subprocess, "run", run)

    assert INGESTION.resolve_vision_bridge_container() == "worker-id"
    assert any(command[-2:] == ["-q", "ai-worker"] for command in calls)


def test_authoritative_page_text_is_required() -> None:
    """缺少 Terra 页级全文时不得发布为规范试卷材料。"""
    response = {"choices": [{"message": {"content": '{"questions":[]}'}}]}

    with pytest.raises(RuntimeError, match="authoritative pageText"):
        INGESTION.recognized_page_text(response, "terra")


def test_canonical_directory_uses_original_full_document_name(tmp_path: Path) -> None:
    """规范目录保留原始完整文件名，不能只以不带扩展名的近似标签命名。"""
    assert INGESTION.canonical_paper_directory_name(tmp_path / "数学模拟卷.pdf") == "数学模拟卷.pdf"


def test_question_asset_manifest_carries_source_and_asset_hashes(tmp_path: Path) -> None:
    """The vision stage can reject an asset copied from another source PDF or modified after extraction."""
    asset_path = tmp_path / "figure.png"
    asset_path.write_bytes(b"source-bound-figure")
    source_sha256 = "a" * 64

    record = ASSETS.question_asset_record(
        question_number=19,
        page_number=2,
        source_sha256=source_sha256,
        source_page_image="page-images/page-002.png",
        relative_asset_path="figures/q019_p002_f01.png",
        asset_path=asset_path,
        layout_label="image",
        layout_score=0.9,
        bbox_pixels=[1.0, 2.0, 3.0, 4.0],
        crop_bbox_pixels=[0.0, 1.0, 4.0, 5.0],
        binding_method="same_page_same_column_preceding_question_anchor",
    )

    assert record["sourceSha256"] == source_sha256
    assert record["assetSha256"] == ASSETS.sha256_file(asset_path)
    assert json.loads(json.dumps(record, ensure_ascii=False))["relativeAssetPath"] == "figures/q019_p002_f01.png"


def test_question_asset_manifest_rejects_cross_page_publication_without_review(tmp_path: Path) -> None:
    """Multi-page diagrams require a reviewer decision instead of an automatic merged publication."""
    asset_path = tmp_path / "figure.png"
    asset_path.write_bytes(b"diagram-part")

    with pytest.raises(ValueError, match="cross-page"):
        ASSETS.question_asset_record(
            question_number=14,
            page_number=2,
            source_sha256="a" * 64,
            source_page_image="page-images/page-002.png",
            relative_asset_path="figures/q014_p002_f02.png",
            asset_path=asset_path,
            layout_label="image",
            layout_score=0.7,
            bbox_pixels=[1.0, 2.0, 3.0, 4.0],
            crop_bbox_pixels=[0.0, 1.0, 4.0, 5.0],
            binding_method="cross_page_group_pending_review",
        )
