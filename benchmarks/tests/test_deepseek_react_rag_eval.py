from benchmarks.deepseek_react_rag_eval import (
    _extract_first_json_object,
    _first_rank,
    _materialize_runtime_dataset,
    _teacher_filter_arguments,
)


def test_runtime_dataset_is_generated_under_output_dir(tmp_path) -> None:
    dataset = _materialize_runtime_dataset(tmp_path)

    assert dataset["topics"]
    assert dataset["queries"]
    assert all(str(tmp_path) in topic["path"] for topic in dataset["topics"])


def test_json_probe_handles_prefix_text() -> None:
    parsed = _extract_first_json_object('前面有解释 {"topic":"导数","idea":"先看定义域"}')

    assert parsed == {"topic": "导数", "idea": "先看定义域"}


def test_json_probe_rejects_missing_object_end() -> None:
    assert _extract_first_json_object('{"topic":"空间向量","idea":"缺括号"') is None
