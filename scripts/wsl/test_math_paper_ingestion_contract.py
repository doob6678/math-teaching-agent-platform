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


def test_recognized_question_keeps_source_bound_question_assets() -> None:
    """A visual question receives only its matching pre-verified figure assets."""
    response = {
        "choices": [{"message": {"content": '{"questions":[{"number":"19","text":"如图求解","latex":[],"confidence":0.9}]}'}}]
    }
    assets = {"19": [{"path": "output/math-paper-assets/paper/figures/q019.png", "sha256": "a" * 64}]}

    questions = INGESTION.recognized_questions(response, "数学模拟卷.pdf", 2, "terra", assets)

    assert questions[0]["metadata"]["extraction"] == "TERRA_VISUAL_PAGE"
    assert questions[0]["metadata"]["questionAssets"] == assets["19"]


def test_search_hits_accepts_flat_and_nested_milvus_v2_responses() -> None:
    """Both deployed Milvus v2 response envelopes preserve every result row."""
    flat = {"data": [{"id": "first"}, {"id": "second"}]}
    nested = {"data": [[{"id": "first"}], [{"id": "second"}]]}

    assert [item["id"] for item in INGESTION.search_hits(flat)] == ["first", "second"]
    assert [item["id"] for item in INGESTION.search_hits(nested)] == ["first", "second"]


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
