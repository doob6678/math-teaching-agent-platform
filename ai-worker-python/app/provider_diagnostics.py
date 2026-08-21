"""Small, secret-free provider diagnosis primitives used by operator checks.

The worker must never turn Codex session/OAuth material into a bearer header.  These
helpers deliberately inspect only credential shape and bounded probe classifications;
HTTP response bodies and credential values stay outside the diagnostic contract.
"""

from __future__ import annotations

from enum import Enum
import hashlib
import re
from typing import Any, Iterable


class CredentialKind(str, Enum):
    API_KEY = "api_key"
    OAUTH_OR_SESSION = "oauth_or_session"
    EMPTY = "empty"
    UNKNOWN = "unknown"


class RouteDisposition(str, Enum):
    FAILED = "failed"
    TRANSIENT_MIXED = "transient_mixed"
    INCONCLUSIVE = "inconclusive"


_API_KEY_PATTERN = re.compile(r"(?:sk-[A-Za-z0-9_-]{20,}|[A-Za-z0-9_-]{32,})\Z")


def classify_credential_kind(value: str | None) -> CredentialKind:
    """Classify only the bearer shape; never return or log the value itself."""
    if not value:
        return CredentialKind.EMPTY
    candidate = value.strip()
    if candidate.count(".") == 2:
        return CredentialKind.OAUTH_OR_SESSION
    if _API_KEY_PATTERN.fullmatch(candidate):
        return CredentialKind.API_KEY
    return CredentialKind.UNKNOWN


def ensure_api_key_compatible(value: str | None) -> None:
    """Fail closed before an unrecognised credential can become an Authorization header."""
    if classify_credential_kind(value) is not CredentialKind.API_KEY:
        raise ValueError("credential is not an OpenAI-compatible API key")


def classify_three_attempts(categories: Iterable[str]) -> RouteDisposition:
    """Require exactly three matching categorized failures before declaring a route failed."""
    values = tuple(categories)
    if len(values) != 3:
        return RouteDisposition.INCONCLUSIVE
    if values[0] and values[0] == values[1] == values[2]:
        return RouteDisposition.FAILED
    return RouteDisposition.TRANSIENT_MIXED


def safe_http_failure(response: Any | None) -> dict[str, str | int]:
    """Summarize an upstream failure without persisting body text, headers, or credentials.

    A provider's JSON error code is sufficient to distinguish authentication, model and
    overloaded-gateway cases. The raw response can contain prompts or provider details,
    so this contract retains only its schema, bounded code/type fields and a body hash.
    """
    if response is None:
        return {"status": 0, "category": "network", "contentType": "", "requestId": "", "bodySha256": ""}
    headers = getattr(response, "headers", {}) or {}
    content_type = str(headers.get("Content-Type", ""))[:120]
    request_id = str(headers.get("X-Request-ID", headers.get("Request-ID", "")))[:120]
    status = int(getattr(response, "status_code", 0) or 0)
    body = getattr(response, "content", b"") or b""
    if isinstance(body, str):
        body = body.encode("utf-8", "replace")
    body_hash = hashlib.sha256(body).hexdigest()
    category = "http_error"
    error_type = ""
    error_code = ""
    try:
        decoded = response.json()
        if isinstance(decoded, dict):
            error = decoded.get("error")
            if isinstance(error, dict):
                error_type = str(error.get("type", ""))[:80]
                error_code = str(error.get("code", ""))[:80]
                category = "provider_error_json"
            else:
                category = "json_without_error"
        else:
            category = "json_non_object"
    except (ValueError, TypeError):
        category = "non_json"
    return {
        "status": status,
        "category": category,
        "contentType": content_type,
        "requestId": request_id,
        "errorType": error_type,
        "errorCode": error_code,
        "bodyBytes": len(body),
        "bodySha256": body_hash,
    }
