"""Provider-compatible text token counting for context admission decisions."""
from __future__ import annotations

from functools import lru_cache


@lru_cache(maxsize=32)
def _encoding(model: str):
    import tiktoken

    normalized = (model or "").strip()
    try:
        return tiktoken.encoding_for_model(normalized or "gpt-4o")
    except KeyError:
        # OpenAI-compatible relays often expose a private model alias. Their current
        # tokenizer family is not discoverable from the alias, so use the public
        # o200k vocabulary and return its name for audit rather than pretending it is provider usage.
        return tiktoken.get_encoding("o200k_base")


def count_texts(texts: list[str], model: str) -> tuple[list[int], str]:
    """Count exact ids produced by the selected local tokenizer, never len(text)/4."""
    encoder = _encoding(model)
    counts = [len(encoder.encode(str(value or ""), disallowed_special=())) for value in texts]
    return counts, encoder.name
