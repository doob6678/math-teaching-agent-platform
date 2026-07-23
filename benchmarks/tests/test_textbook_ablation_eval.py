from benchmarks.textbook_ablation_eval import distinct_document_rank


def test_document_rank_counts_unique_books_not_repeated_section_blocks() -> None:
    """Doc@K must measure textbook coverage while block@K keeps the raw order."""
    hits = [
        {"docId": "wrong-book", "chunkId": "wrong-1"},
        {"docId": "wrong-book", "chunkId": "wrong-2"},
        {"docId": "right-book", "chunkId": "right-1"},
    ]

    assert distinct_document_rank(hits, "right-book") == 2
    assert distinct_document_rank(hits, "wrong-book") == 1
    assert distinct_document_rank(hits, "missing-book") is None
