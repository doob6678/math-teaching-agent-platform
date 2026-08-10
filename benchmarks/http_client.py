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
                break
            except (requests_exceptions.ConnectionError, requests_exceptions.Timeout) as error:
                last_error = error
                if attempt >= self.max_retries:
                    raise
                # Benchmark runs can overlap with local backend restarts or short proxy hiccups.
                # Use a bounded exponential backoff so a brief outage does not invalidate the run.
                time.sleep(min(8.0, 0.8 * (2 ** attempt)))
        if response is None:
            raise RuntimeError(f"request failed without response: {method} {path}") from last_error
        elapsed_ms = int(round((time.perf_counter() - start) * 1000))
        parsed: dict[str, Any] | list[Any] | str
        try:
            parsed = response.json()
        except ValueError:
            parsed = response.text
        return HttpAttempt(response.status_code, elapsed_ms, response.ok, parsed)
