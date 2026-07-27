"""Focused deployment-contract tests for the offline textbook Milvus migrator."""

from scripts.local.migrate_textbook_indexes_to_milvus import resolve_spring_placeholder


def test_resolves_configured_environment_value() -> None:
    assert resolve_spring_placeholder(
        "${MATH_AGENT_VECTOR_INDEX_MILVUS_URI:http://127.0.0.1:19530}",
        {"MATH_AGENT_VECTOR_INDEX_MILVUS_URI": "http://127.0.0.1:19531"},
    ) == "http://127.0.0.1:19531"


def test_resolves_nested_default_value() -> None:
    assert resolve_spring_placeholder(
        "${PRIMARY_URI:${SECONDARY_URI:http://127.0.0.1:19530}}",
        {},
    ) == "http://127.0.0.1:19530"


def test_preserves_non_string_yaml_values() -> None:
    assert resolve_spring_placeholder(512, {}) == 512
