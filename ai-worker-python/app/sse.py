"""Small SSE framing helpers for OpenAI-compatible streaming relays."""
from __future__ import annotations

from collections.abc import Iterator

import json

import requests


MAX_BUFFERED_SSE_DATA_BYTES = 64 * 1024


def _is_incomplete_json(value: str, error: json.JSONDecodeError) -> bool:
    if error.pos >= len(value):
        return True
    return error.msg.startswith("Unterminated string")


def iter_sse_data_events(response: requests.Response) -> Iterator[str]:
    """Yield JSON SSE events, including relays that split one payload across data lines."""
    data_lines: list[str] = []

    def flush() -> str | None:
        if not data_lines:
            return None
        standard_value = "\n".join(data_lines)
        raw_value = "".join(data_lines)
        data_lines.clear()
        try:
            json.loads(standard_value)
            return standard_value
        except json.JSONDecodeError:
            return raw_value

    for raw_line in response.iter_lines(decode_unicode=True):
        line = raw_line.decode("utf-8", "replace") if isinstance(raw_line, bytes) else raw_line
        if line == "":
            if data_lines:
                standard_value = "\n".join(data_lines)
                raw_value = "".join(data_lines)
                try:
                    json.loads(standard_value)
                except json.JSONDecodeError:
                    try:
                        json.loads(raw_value)
                    except json.JSONDecodeError:
                        continue
                    data_lines.clear()
                    yield raw_value
                else:
                    data_lines.clear()
                    yield standard_value
            continue
        if line.startswith(":"):
            continue
        if not line.startswith("data:"):
            if data_lines:
                data_lines.append(line)
                raw_value = "".join(data_lines)
                if len(raw_value.encode("utf-8")) > MAX_BUFFERED_SSE_DATA_BYTES:
                    raise ValueError("provider SSE event exceeds buffered data limit")
                try:
                    json.loads(raw_value)
                except json.JSONDecodeError as error:
                    if not _is_incomplete_json(raw_value, error):
                        raise
                else:
                    data_lines.clear()
                    yield raw_value
            continue
        value = line[5:]
        if value.startswith(" "):
            value = value[1:]
        if value == "[DONE]" or value.lower() in {"ping", "keep-alive"}:
            buffered = flush()
            if buffered is not None:
                yield buffered
            yield value
            continue
        if not data_lines:
            try:
                json.loads(value)
            except json.JSONDecodeError as error:
                if not value.startswith(("{", "[")):
                    yield value
                elif _is_incomplete_json(value, error):
                    data_lines.append(value)
                else:
                    raise
            else:
                yield value
            continue

        data_lines.append(value)
        raw_value = "".join(data_lines)
        if len(raw_value.encode("utf-8")) > MAX_BUFFERED_SSE_DATA_BYTES:
            raise ValueError("provider SSE event exceeds buffered data limit")
        try:
            json.loads(raw_value)
        except json.JSONDecodeError as error:
            if not _is_incomplete_json(raw_value, error):
                raise
            continue
        data_lines.clear()
        yield raw_value

    value = flush()
    if value is not None:
        yield value
