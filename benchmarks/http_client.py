from __future__ import annotations

import json
import time
from dataclasses import dataclass
from typing import Any

import requests
from requests import exceptions as requests_exceptions


@dataclass(frozen=True)
class HttpAttempt:
    """One real HTTP attempt captured for benchmark statistics."""

    status: int
    elapsed_ms: int
    ok: bool
    body: dict[str, Any] | list[Any] | str
    retry_count: int = 0
    rate_limit_429_count: int = 0
    total_backoff_ms: int = 0


class MathAgentClient:
    """Small HTTP client for benchmark scripts; it only calls public backend APIs."""

    def __init__(self, base_url: str, timeout: float = 60.0, max_retries: int = 5) -> None:
        self.base_url = base_url.rstrip("/")
        self.timeout = timeout
        self.max_retries = max_retries
        self.session = requests.Session()

    def login(self, username: str, password: str) -> dict[str, Any]:
        response = self.post("/api/auth/login", {"username": username, "password": password})
        if response.status != 200 or not isinstance(response.body, dict):
            raise RuntimeError(f"login failed for {username}: HTTP {response.status} {response.body}")
        # requests.Session stores the backend's HttpOnly Set-Cookie automatically. The response body contains
        # identity metadata only; no raw session token is copied into benchmark state or request headers.
        return response.body

    def get(
            self,
            path: str,
            params: dict[str, Any] | None = None,
            headers: dict[str, str] | None = None) -> HttpAttempt:
        return self._request("GET", path, params=params, headers=headers)

    def post(
            self,
            path: str,
            body: dict[str, Any] | list[Any] | None = None,
            headers: dict[str, str] | None = None) -> HttpAttempt:
        return self._request("POST", path, body=body, headers=headers)

    def delete(
            self,
            path: str,
            headers: dict[str, str] | None = None) -> HttpAttempt:
        return self._request("DELETE", path, headers=headers)

    def _request(
            self,
            method: str,
            path: str,
            params: dict[str, Any] | None = None,
            body: dict[str, Any] | list[Any] | None = None,
            headers: dict[str, str] | None = None) -> HttpAttempt:
        request_headers = dict(headers or {})
        last_error: Exception | None = None
        response = None
        start = time.perf_counter()
        retry_count = 0
        rate_limit_429_count = 0
        total_backoff_ms = 0
        for attempt in range(self.max_retries + 1):
            try:
                # `requests` may choose a locale-dependent JSON serialization path on Windows.  Send UTF-8 bytes
                # explicitly so Chinese titles, paths, and queries reach the Java API unchanged.
                request_body = None
                if body is not None:
                    request_body = json.dumps(body, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
                    request_headers.setdefault("Content-Type", "application/json; charset=utf-8")
                response = self.session.request(
                    method,
                    self.base_url + path,
                    params=params,
                    data=request_body,
                    headers=request_headers,
                    timeout=self.timeout,
                )
                if response.status_code != 429 or attempt >= self.max_retries:
                    break
                retry_count += 1
                rate_limit_429_count += 1
                retry_after = _retry_after_seconds(response.headers.get("Retry-After"))
                # 429 recovery is deliberately fixed to 1s/2s/4s and never exceeds four seconds.
                delay = retry_after if retry_after is not None else min(4.0, float(2 ** min(attempt, 2)))
                delay = min(4.0, max(1.0, delay))
                total_backoff_ms += int(round(delay * 1000))
                time.sleep(delay)
            except (requests_exceptions.ConnectionError, requests_exceptions.Timeout) as error:
                last_error = error
                if attempt >= self.max_retries:
                    raise
                retry_count += 1
                delay = min(4.0, float(2 ** min(attempt, 2)))
                total_backoff_ms += int(round(delay * 1000))
                time.sleep(delay)
        if response is None:
            raise RuntimeError(f"request failed without response: {method} {path}") from last_error
        elapsed_ms = int(round((time.perf_counter() - start) * 1000))
        parsed: dict[str, Any] | list[Any] | str
        try:
            parsed = response.json()
        except ValueError:
            parsed = response.text
        return HttpAttempt(
            response.status_code,
            elapsed_ms,
            response.ok,
            parsed,
            retry_count,
            rate_limit_429_count,
            total_backoff_ms,
        )


def _retry_after_seconds(value: str | None) -> float | None:
    """Parse the server's delta-seconds hint without allowing an unbounded sleep."""
    if not value:
        return None
    try:
        seconds = float(value.strip())
    except ValueError:
        return None
    return seconds if seconds >= 0 else None
