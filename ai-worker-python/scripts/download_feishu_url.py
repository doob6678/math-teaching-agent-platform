import argparse
import html
import hashlib
import json
import mimetypes
import os
import re
import sys
import time
import urllib.parse
from html.parser import HTMLParser
from pathlib import Path
from typing import Any, Dict, List, Optional, Tuple

import requests


DEFAULT_TIMEOUT_SECONDS = 30
EXPORTABLE_TYPES = {"docx"}
DIRECT_DOWNLOAD_TYPES = {"file"}

# Feishu's Markdown export can contain signed ``internal-api-drive-stream`` URLs.  Those URLs are short-lived and
# cannot be sent to Java/AI callers safely, so we materialize every image into the owner-scoped staging directory.
MARKDOWN_IMAGE_PATTERN = re.compile(r"!\[[^\]]*\]\(\s*(?:<([^>]+)>|([^\s)]+))(?:\s+[^)]*)?\s*\)", re.IGNORECASE)
# Feishu's docs/v1/content response has historically emitted both ``src`` and ``href`` image attributes.
# Parse the complete tag first, then inspect either attribute so attribute order and additional metadata do not
# affect extraction.  Keeping this separate from the Markdown pattern also prevents links in surrounding HTML from
# being mistaken for images.
HTML_IMAGE_TAG_PATTERN = re.compile(r"<img\b[^>]*>", re.IGNORECASE)
HTML_IMAGE_ATTRIBUTE_PATTERN = re.compile(
    r"\b(?:src|href)\s*=\s*(?:\"([^\"]*)\"|'([^']*)'|([^\s>\"']+))",
    re.IGNORECASE,
)


SUPPORTED_IMAGE_SIGNATURES: Tuple[Tuple[bytes, str], ...] = (
    (b"\x89PNG\r\n\x1a\n", "image/png"),
    (b"\xff\xd8\xff", "image/jpeg"),
    (b"GIF87a", "image/gif"),
    (b"GIF89a", "image/gif"),
    (b"BM", "image/bmp"),
    (b"II*\x00", "image/tiff"),
    (b"MM\x00*", "image/tiff"),
)


def detected_image_mime(payload: bytes) -> str:
    """Returns the MIME type proven by a supported image file signature."""
    data = bytes(payload or b"")
    for signature, mime_type in SUPPORTED_IMAGE_SIGNATURES:
        if data.startswith(signature):
            return mime_type
    # RIFF is shared by several formats; only the WEBP marker identifies an image here.
    if len(data) >= 12 and data.startswith(b"RIFF") and data[8:12] == b"WEBP":
        return "image/webp"
    return ""


_URL_PATTERN = re.compile(r"https?://[^\s\"'<>]+", re.IGNORECASE)
_SECRET_QUERY_PATTERN = re.compile(
    r"(?i)([?&](?:access_token|app_secret|authorization|cookie|sig|signature|token)=)[^&\s,}]+"
)


def safe_url_reference(value: str) -> str:
    """Keeps error context while removing query strings and URL user information."""
    try:
        parsed = urllib.parse.urlsplit(str(value or ""))
        if parsed.scheme and parsed.hostname:
            return f"{parsed.scheme}://{parsed.hostname}{parsed.path or '/'}"
    except ValueError:
        pass
    return str(value or "")


def safe_error_message(error: Any) -> str:
    """Redacts signed URL queries and credential-like query values from provider errors."""
    message = str(error or "")
    message = _URL_PATTERN.sub(lambda match: safe_url_reference(match.group(0)), message)
    return _SECRET_QUERY_PATTERN.sub(r"\1<redacted>", message)


def image_failure(
    document_token: str,
    image_index: int,
    reference: str,
    token: str,
    error: Any,
) -> Dict[str, Any]:
    """Builds an observable image failure without retaining signed URL credentials."""
    image_name = f"image-{image_index:03d}"
    return {
        "type": "image",
        "token": safe_url_reference(token) if str(token or "").lower().startswith(("http://", "https://")) else token,
        "name": image_name,
        "path": safe_url_reference(reference),
        "message": (
            f"document={document_token} image={image_name} "
            f"source={safe_url_reference(reference)}: {safe_error_message(error)}"
        ),
    }


class _FeishuMarkupNormalizer(HTMLParser):
    """Converts Feishu's XML export into line-oriented Markdown-compatible text.

    The docs_ai endpoint returns rich XML as one long string.  The Java source parser works on logical lines, so
    this small standard-library parser keeps headings and image tags on their own lines while retaining every text
    and formula datum.  Image tags are deliberately emitted unchanged; the authenticated asset pass rewrites only
    their signed URL afterwards.
    """

    _BLOCK_TAGS = {"p", "blockquote", "callout", "title", "ul", "ol", "li", "h1", "h2", "h3", "h4", "h5", "h6"}

    def __init__(self) -> None:
        super().__init__(convert_charrefs=True)
        self.parts: List[str] = []

    def _newline(self) -> None:
        if not self.parts or not self.parts[-1].endswith("\n"):
            self.parts.append("\n")

    def handle_starttag(self, tag: str, attrs: List[Tuple[str, Optional[str]]]) -> None:
        normalized_tag = tag.lower()
        if normalized_tag.startswith("h") and len(normalized_tag) == 2 and normalized_tag[1].isdigit():
            self._newline()
            self.parts.append("#" * int(normalized_tag[1]) + " ")
        elif normalized_tag in self._BLOCK_TAGS:
            self._newline()
        elif normalized_tag == "img":
            self._newline()
            self.parts.append(self.get_starttag_text() or "<img>")
            self._newline()
        elif normalized_tag in {"br", "hr"}:
            self._newline()
            if normalized_tag == "hr":
                self.parts.append("---")
            self._newline()

    def handle_startendtag(self, tag: str, attrs: List[Tuple[str, Optional[str]]]) -> None:
        self.handle_starttag(tag, attrs)

    def handle_endtag(self, tag: str) -> None:
        if tag.lower() in self._BLOCK_TAGS:
            self._newline()

    def handle_data(self, data: str) -> None:
        if data:
            self.parts.append(data)


def normalize_feishu_document_markup(content: str) -> str:
    """Makes docs_ai XML line-oriented without dropping image or formula content."""
    if not content or "<" not in content:
        return content
    parser = _FeishuMarkupNormalizer()
    try:
        parser.feed(content)
        parser.close()
    except Exception:
        # Keep the original payload for the existing fallback parser when a provider adds unknown malformed markup.
        return content
    normalized = "".join(parser.parts).replace("\r\n", "\n").replace("\r", "\n")
    return normalized.strip() + "\n" if normalized.strip() else ""


def normalize_document_image_items(
    markdown: str,
    image_items: List[Dict[str, Any]],
    markdown_dir: Path,
) -> Tuple[str, List[Dict[str, Any]]]:
    """Normalizes image manifests to the document-local IMAJES directory."""
    rewritten = str(markdown or "")
    normalized_items: List[Dict[str, Any]] = []
    for item in image_items:
        relative_path = str(item.get("relativePath", "") or "").replace("\\", "/").strip("/")
        if not relative_path:
            normalized_items.append(item)
            continue
        target = (markdown_dir / relative_path).resolve()
        if not target.is_file() or target.parent.name != "IMAJES":
            normalized_items.append(item)
            continue
        scoped_path = target.relative_to(markdown_dir.resolve()).as_posix()
        rewritten = rewritten.replace(relative_path, scoped_path)
        updated = dict(item)
        updated["path"] = scoped_path
        updated["relativePath"] = scoped_path
        updated["providerAssetId"] = scoped_path
        normalized_items.append(updated)
    return rewritten, normalized_items


def qualify_markdown_image_reference(source_relative_path: str, local_reference: str) -> str:
    """Prefixes a document-local image path with its readable Markdown document identity."""
    source = str(source_relative_path or "").replace("\\", "/").strip().strip("/")
    reference = str(local_reference or "").replace("\\", "/").strip()
    parts = [part for part in reference.split("/") if part]
    if not source or not reference or "::" in source or "::" in reference:
        raise ValueError("invalid qualified Markdown image reference")
    if reference.startswith(("http://", "https://", "data:", "/")) or ".." in parts:
        raise ValueError("image reference must be a local relative path")
    if len(parts) < 2 or parts[-2] != "IMAJES" or not parts[-1]:
        raise ValueError("image reference must point into a document-local IMAJES directory")
    return f"{source}::{reference}"


def qualify_materialized_markdown(markdown: str, source_relative_path: str) -> str:
    """Keeps portable document-local image links unchanged for direct Markdown preview."""
    del source_relative_path
    return str(markdown or "")


class FeishuHttpError(RuntimeError):
    """HTTP-level Feishu failure whose JSON provider payload must survive the process boundary."""


def mask_secret(secret: str) -> str:
    if not secret:
        return ""
    if len(secret) <= 8:
        return "*" * len(secret)
    return f"{secret[:4]}{'*' * (len(secret) - 8)}{secret[-4:]}"


def sanitize_name(name: str, default_name: str = "downloaded") -> str:
    candidate = str(name or "").strip() or default_name
    invalid_chars = '<>:"/\\|?*'
    sanitized = "".join("_" if ch in invalid_chars else ch for ch in candidate).rstrip(" .")
    return sanitized or default_name


def parse_content_disposition_filename(content_disposition: str, default_name: str) -> str:
    if not content_disposition:
        return default_name
    filename_star_match = re.search(r"filename\*\s*=\s*UTF-8''([^;]+)", content_disposition, re.IGNORECASE)
    if filename_star_match:
        return urllib.parse.unquote(filename_star_match.group(1).strip())
    filename_match = re.search(r'filename\s*=\s*"([^"]+)"', content_disposition, re.IGNORECASE)
    if filename_match:
        return filename_match.group(1).strip()
    filename_match = re.search(r"filename\s*=\s*([^;]+)", content_disposition, re.IGNORECASE)
    if filename_match:
        return filename_match.group(1).strip().strip('"')
    return default_name


def find_default_appkey_path(explicit_path: str = "") -> Optional[Path]:
    candidates: List[Path] = []
    if explicit_path:
        candidates.append(Path(explicit_path))
    candidates.append(Path.cwd() / "APPKEY.md")
    script_path = Path(__file__).resolve()
    candidates.extend(parent / "APPKEY.md" for parent in script_path.parents)
    for candidate in candidates:
        resolved = candidate if candidate.is_absolute() else (Path.cwd() / candidate)
        # An empty Java appkey-path can resolve to the container working directory (for example /app).
        # Only a regular file is a valid credential source; accepting a directory turns a missing
        # environment credential into a misleading IsADirectoryError before the URL sync starts.
        if resolved.is_file():
            return resolved
    return None


def load_appkey(path: Path) -> Tuple[str, str, str]:
    content = path.read_text(encoding="utf-8")
    app_id_match = re.search(r"(?im)^APPID\s*$\s*^([^\r\n]+)\s*$", content)
    app_secret_match = re.search(r"(?im)^APP Secret\s*$\s*^([^\r\n]+)\s*$", content)
    app_id = app_id_match.group(1).strip() if app_id_match else ""
    app_secret = app_secret_match.group(1).strip() if app_secret_match else ""
    access_token_match = re.search(r"(?im)^ACCESS_TOKEN\s*$\s*^([^\r\n]+)\s*$", content)
    access_token = access_token_match.group(1).strip() if access_token_match else ""
    return app_id, app_secret, access_token


def resolve_credentials(args: argparse.Namespace) -> Tuple[str, str, str]:
    # A user OAuth token is already scoped to the authenticated user. It must win over platform app credentials and
    # is never printed or forwarded through RabbitMQ; Java supplies it through a short-lived private credential file.
    if getattr(args, "access_token", ""):
        return "", "", args.access_token.strip()
    if args.app_id and args.app_secret:
        return args.app_id.strip(), args.app_secret.strip(), ""

    if not args.no_env:
        # The backend Compose contract historically exposed FEISHU_APP_ID/FEISHU_APP_SECRET,
        # while the standalone downloader contract uses APP_ID/APP_SECRET.  Accept both aliases
        # so the exact same Feishu URL workflow works in Docker and in local verification without
        # copying credentials into command arguments, manifests, reports, or logs.
        env_app_id = (os.getenv("APP_ID", "") or os.getenv("FEISHU_APP_ID", "")
                      or os.getenv("FEISHU_APPID", "")).strip()
        env_app_secret = (os.getenv("APP_SECRET", "") or os.getenv("FEISHU_APP_SECRET", "")
                          or os.getenv("FEISHU_APPSECRET", "")).strip()
        if env_app_id and env_app_secret:
            return env_app_id, env_app_secret, ""

    appkey_path = find_default_appkey_path(args.appkey_path)
    if appkey_path:
        app_id, app_secret, access_token = load_appkey(appkey_path)
        if access_token:
            return app_id, app_secret, access_token
        if app_id and app_secret:
            return app_id, app_secret, ""

    raise RuntimeError("Missing credentials. Set APP_ID/APP_SECRET or provide --appkey-path pointing to APPKEY.md.")


def default_output_dir() -> Path:
    return Path.home() / "Downloads"


def default_config_path() -> Path:
    return Path(__file__).resolve().parent.parent / "config" / "defaults.json"


def load_default_config(explicit_path: str = "") -> Dict[str, Any]:
    candidates: List[Path] = []
    if explicit_path:
        candidates.append(Path(explicit_path).expanduser())
    candidates.append(default_config_path())
    for candidate in candidates:
        resolved = candidate if candidate.is_absolute() else (Path.cwd() / candidate)
        if resolved.exists():
            return json.loads(resolved.read_text(encoding="utf-8"))
    return {}


def configured_output_dir(config: Dict[str, Any]) -> Path:
    configured = str(config.get("default_download_dir", "") or "").strip()
    return Path(configured).expanduser() if configured else default_output_dir()


def parse_feishu_url(resource_url: str) -> Dict[str, str]:
    value = str(resource_url or "").strip()
    patterns = [
        ("folder", r"/drive/folder/([A-Za-z0-9]+)"),
        ("docx", r"/docx/([A-Za-z0-9]+)"),
        ("file", r"/file/([A-Za-z0-9]+)"),
    ]
    for resource_type, pattern in patterns:
        match = re.search(pattern, value)
        if match:
            return {"resource_type": resource_type, "token": match.group(1), "url": value}
    raise RuntimeError("Unsupported Feishu URL. Expected /drive/folder/{token}, /docx/{token}, or /file/{token}.")


def build_url_from_type_token(resource_type: str, token: str) -> str:
    if resource_type == "folder":
        return f"https://my.feishu.cn/drive/folder/{token}"
    if resource_type == "file":
        return f"https://my.feishu.cn/file/{token}"
    return f"https://my.feishu.cn/{resource_type}/{token}"


def build_url_from_search_item(item: Dict[str, Any]) -> str:
    doc_type = str(item.get("docs_type", "") or item.get("type", "")).strip()
    token = str(item.get("docs_token", "") or item.get("token", "")).strip()
    if not doc_type or not token:
        raise RuntimeError(f"Search result does not contain type/token: {item}")
    return build_url_from_type_token(doc_type, token)


def configured_root(config: Dict[str, Any], root_key: str = "") -> Dict[str, str]:
    roots = config.get("roots", {})
    if not isinstance(roots, dict) or not roots:
        raise RuntimeError("No roots configured. Add config/defaults.json or pass --root-url.")
    selected_key = root_key or str(config.get("default_root", "") or "")
    if not selected_key:
        selected_key = next(iter(roots.keys()))
    root = roots.get(selected_key)
    if not isinstance(root, dict):
        raise RuntimeError(f"Configured root not found: {selected_key}")
    url = str(root.get("url", "") or "").strip()
    token = str(root.get("token", "") or "").strip()
    if not url and token:
        url = build_url_from_type_token("folder", token)
    if not token and url:
        token = parse_feishu_url(url)["token"]
    if not url or not token:
        raise RuntimeError(f"Configured root is missing url/token: {selected_key}")
    return {
        "key": selected_key,
        "name": str(root.get("name", "") or selected_key),
        "url": url,
        "token": token,
    }


def resolve_root(config: Dict[str, Any], root_url: str = "", root_key: str = "") -> Dict[str, str]:
    if root_url:
        parsed = parse_feishu_url(root_url)
        if parsed["resource_type"] != "folder":
            raise RuntimeError("--root-url must be a Feishu folder URL")
        return {"key": "", "name": "", "url": root_url, "token": parsed["token"]}
    return configured_root(config, root_key)


class FeishuClient:
    def __init__(self, app_id: str, app_secret: str, verbose: bool = True, timeout_seconds: int = DEFAULT_TIMEOUT_SECONDS, access_token: str = "") -> None:
        self.app_id = app_id
        self.app_secret = app_secret
        self.verbose = verbose
        self.timeout_seconds = max(int(timeout_seconds), 1)
        # Reuse one TCP/TLS pool across folder listing, export polling, document fetches, and image downloads.
        # This reduces handshake latency without changing provider concurrency or request ordering.
        self.http = requests.Session()
        self.access_token = access_token.strip() if access_token else self.get_tenant_access_token()

    def log(self, message: str) -> None:
        if self.verbose:
            print(message)

    def request(
        self,
        method: str,
        url: str,
        *,
        params: Optional[Dict[str, Any]] = None,
        json_body: Optional[Dict[str, Any]] = None,
        headers: Optional[Dict[str, str]] = None,
        data: Optional[Dict[str, Any]] = None,
        files: Optional[Dict[str, Any]] = None,
        stream: bool = False,
    ) -> requests.Response:
        request_headers = dict(headers or {})
        if self.access_token and "Authorization" not in request_headers:
            request_headers["Authorization"] = f"Bearer {self.access_token}"
        response = self.http.request(
            method,
            url,
            params=params,
            json=json_body,
            headers=request_headers,
            data=data,
            files=files,
            stream=stream,
            timeout=self.timeout_seconds,
        )
        response.encoding = "utf-8"
        if not response.ok:
            # requests.raise_for_status() discards the structured Feishu code/body that an administrator needs to
            # authorize the app. Keep the provider payload in the exception without exposing credentials.
            try:
                provider_body = response.json()
                detail = json.dumps(provider_body, ensure_ascii=False, separators=(",", ":"))
            except ValueError:
                detail = response.text.strip()
            raise FeishuHttpError(f"Feishu HTTP {response.status_code}: {detail}")
        return response

    def get_tenant_access_token(self) -> str:
        url = "https://open.feishu.cn/open-apis/auth/v3/tenant_access_token/internal"
        payload = {"app_id": self.app_id, "app_secret": self.app_secret}
        self.log(f"POST {url}")
        self.log(json.dumps({"app_id": self.app_id, "app_secret": mask_secret(self.app_secret)}, ensure_ascii=False))
        response = self.http.post(url, json=payload, headers={"Content-Type": "application/json; charset=utf-8"}, timeout=self.timeout_seconds)
        response.raise_for_status()
        response.encoding = "utf-8"
        body = response.json()
        if body.get("code", 0) != 0:
            raise RuntimeError(f"get tenant_access_token failed: {body}")
        return body["tenant_access_token"]

    def api_json(
        self,
        method: str,
        url: str,
        *,
        params: Optional[Dict[str, Any]] = None,
        json_body: Optional[Dict[str, Any]] = None,
    ) -> Dict[str, Any]:
        self.log(f"{method} {url}")
        if params:
            self.log("Params: " + json.dumps(params, ensure_ascii=False))
        if json_body:
            self.log("Body: " + json.dumps(json_body, ensure_ascii=False))
        response = self.request(
            method,
            url,
            params=params,
            json_body=json_body,
            headers={"Content-Type": "application/json; charset=utf-8"},
        )
        body = response.json()
        if body.get("code", 0) != 0:
            raise RuntimeError(f"Feishu API failed: {body}")
        return body.get("data", {})

    def get_folder_meta(self, folder_token: str) -> Dict[str, Any]:
        url = f"https://open.feishu.cn/open-apis/drive/explorer/v2/folder/{urllib.parse.quote(folder_token)}/meta"
        try:
            return self.api_json("GET", url)
        except Exception as metadata_error:
            # Some tenant bots can enumerate a shared folder through the official Drive files API while the older
            # explorer metadata endpoint still demands user-only Drive privileges.  The folder listing is the
            # authoritative source for recursive URL sync, so retain the provider token and continue through that
            # API instead of failing a production sync before the first document is inspected.  A provider title is
            # optional here; the checkpoint or backend-owned staging root preserves the stable local folder name.
            try:
                data = self.api_json(
                    "GET",
                    "https://open.feishu.cn/open-apis/drive/v1/files",
                    params={"folder_token": folder_token, "page_size": 1},
                )
                if isinstance(data, dict) and ("files" in data or "items" in data):
                    return {"name": folder_token, "metadata_endpoint": "drive/v1/files", "metadata_fallback": True}
            except Exception:
                pass
            raise metadata_error

    def list_folder_items(self, folder_token: str) -> List[Dict[str, Any]]:
        items: List[Dict[str, Any]] = []
        page_token = ""
        while True:
            page_items, next_page_token, has_more = self.list_folder_page(folder_token, page_token)
            items.extend(page_items)
            if not has_more:
                break
            page_token = next_page_token
            if not page_token:
                break
        return items

    def list_folder_page(self, folder_token: str, page_token: str = "") -> Tuple[List[Dict[str, Any]], str, bool]:
        params = {"folder_token": folder_token, "page_size": 200}
        if page_token:
            params["page_token"] = page_token
        data = self.api_json("GET", "https://open.feishu.cn/open-apis/drive/v1/files", params=params)
        page_items = data.get("items", data.get("files", []))
        items = [item for item in page_items if isinstance(item, dict)] if isinstance(page_items, list) else []
        next_page_token = str(data.get("next_page_token", "") or "")
        return items, next_page_token, bool(data.get("has_more"))

    def search_docs(self, keyword: str, count: int, offset: int, docs_types: Optional[List[str]]) -> Dict[str, Any]:
        payload: Dict[str, Any] = {"search_key": keyword, "count": count, "offset": offset}
        if docs_types:
            payload["docs_types"] = docs_types
        data = self.api_json("POST", "https://open.feishu.cn/open-apis/suite/docs-api/search/object", json_body=payload)
        docs_entities = data.get("docs_entities", [])
        if isinstance(docs_entities, dict):
            docs_entities = docs_entities.get("docs", [])
        data["items"] = docs_entities if isinstance(docs_entities, list) else []
        return data

    def export_docx(self, document_token: str, file_extension: str) -> Tuple[bytes, str, int]:
        create_data = self.api_json(
            "POST",
            "https://open.feishu.cn/open-apis/drive/v1/export_tasks",
            json_body={"token": document_token, "type": "docx", "file_extension": file_extension},
        )
        ticket = str(create_data.get("ticket", "") or "")
        if not ticket:
            raise RuntimeError("create export task succeeded but ticket is empty")

        final_result: Dict[str, Any] = {}
        for _ in range(30):
            task_data = self.api_json(
                "GET",
                f"https://open.feishu.cn/open-apis/drive/v1/export_tasks/{urllib.parse.quote(ticket)}",
                params={"token": document_token},
            )
            result = task_data.get("result", {})
            job_status = result.get("job_status")
            if job_status == 0:
                final_result = result
                break
            if job_status in {1, 2}:
                time.sleep(2)
                continue
            raise RuntimeError(f"export task failed with status {job_status}: {result.get('job_error_msg', '')}")
        else:
            raise RuntimeError("export task timed out")

        file_token = str(final_result.get("file_token", "") or "")
        if not file_token:
            raise RuntimeError("export task succeeded but file_token is empty")

        download_url = f"https://open.feishu.cn/open-apis/drive/v1/export_tasks/file/{urllib.parse.quote(file_token)}/download"
        response = self.request("GET", download_url)
        filename = parse_content_disposition_filename(
            response.headers.get("Content-Disposition", ""),
            f"{document_token}.{file_extension}",
        )
        return response.content, filename, len(response.content)

    def download_docx_markdown(self, document_token: str) -> Tuple[bytes, str, int]:
        # The v2 document fetch API is the only Feishu content endpoint that preserves image blocks as signed
        # `<img href="...">` nodes. Prefer it for Markdown materialization so the subsequent asset pass can
        # download every real image; older docs/v1 endpoints silently flatten those nodes to empty paragraphs.
        try:
            data = self.api_json(
                "POST",
                f"https://open.feishu.cn/open-apis/docs_ai/v1/documents/{urllib.parse.quote(document_token)}/fetch",
                json_body={
                    "export_option": {
                        "export_block_id": False,
                        "export_cite_extra_data": False,
                        "export_style_attrs": False,
                    },
                    "format": "xml",
                },
            )
            content = self.extract_content_text(data)
            if content:
                encoded = content.encode("utf-8")
                return encoded, f"{document_token}.md", len(encoded)
        except Exception as exc:
            ai_fetch_error = exc
        else:
            ai_fetch_error = RuntimeError("docs_ai/v1/fetch returned empty content")

        try:
            data = self.api_json(
                "GET",
                "https://open.feishu.cn/open-apis/docs/v1/content",
                params={"doc_token": document_token, "doc_type": "docx"},
            )
            content = self.extract_content_text(data)
            if content:
                encoded = content.encode("utf-8")
                return encoded, f"{document_token}.md", len(encoded)
        except Exception as exc:
            first_error = exc
        else:
            first_error = RuntimeError("docs/v1/content returned empty content")

        try:
            data = self.api_json(
                "GET",
                f"https://open.feishu.cn/open-apis/docx/v1/documents/{urllib.parse.quote(document_token)}/raw_content",
            )
            content = self.extract_content_text(data)
            if content:
                encoded = content.encode("utf-8")
                return encoded, f"{document_token}.md", len(encoded)
        except Exception as exc:
            raise RuntimeError(
                f"Feishu markdown download failed: docs_ai={ai_fetch_error}; docs_v1={first_error}; raw_content={exc}"
            ) from exc
        raise RuntimeError(f"Feishu markdown download failed: {first_error}; raw_content returned empty content")

    def download_docx_markdown_with_assets(
        self,
        document_token: str,
        output_dir: Path,
    ) -> Tuple[bytes, str, int, List[Dict[str, Any]], List[Dict[str, Any]]]:
        """Downloads one document and replaces signed Markdown image URLs with local assets.

        The document body and image binaries are fetched with the same tenant credential.  A failed image is
        returned as an item-level failure instead of being silently left as an expired provider URL; Java then
        keeps the source out of the ``ready`` state and exposes the exact failed item in the sync checkpoint.
        """
        content, suggested_name, _ = self.download_docx_markdown(document_token)
        # docs_ai returns XML in one long line; normalize its block structure before the Java parser scans local
        # files, while preserving image tags for the authenticated materialization pass below.
        markdown = normalize_feishu_document_markup(content.decode("utf-8", errors="replace"))
        normalized, manifests, failures = self.materialize_markdown_images(
            document_token,
            markdown,
            output_dir,
        )
        encoded = normalized.encode("utf-8")
        return encoded, suggested_name, len(encoded), manifests, failures

    def materialize_markdown_images(
        self,
        document_token: str,
        markdown: str,
        output_dir: Path,
    ) -> Tuple[str, List[Dict[str, Any]], List[Dict[str, Any]]]:
        """Materializes Markdown/HTML images below the current document's IMAJES directory."""
        references = markdown_image_references(markdown)
        if not references:
            return markdown, [], []
        image_tokens = self.list_document_image_tokens(document_token)
        asset_dir = output_dir / "IMAJES"
        manifests: List[Dict[str, Any]] = []
        failures: List[Dict[str, Any]] = []
        rewritten = markdown
        token_index = 0
        replacements: Dict[str, str] = {}
        for index, reference in enumerate(references, start=1):
            source_url = reference["url"]
            if source_url in replacements:
                continue
            media_token = image_tokens[index - 1] if index <= len(image_tokens) else ""
            try:
                payload, name, mime_type = self.download_embedded_image(source_url)
                provider_token = source_url
            except Exception as direct_error:
                # A signed stream URL may be expired.  The block API gives us the durable media token for the
                # same document, which still honours the tenant app's real Feishu permissions.
                if not media_token:
                    failures.append(image_failure(document_token, index, source_url, source_url, direct_error))
                    continue
                try:
                    payload, name, mime_type = self.download_media(media_token)
                    provider_token = media_token
                except Exception as media_error:
                    failures.append(image_failure(
                        document_token,
                        index,
                        source_url,
                        media_token,
                        f"signed URL failed: {safe_error_message(direct_error)}; "
                        f"media token failed: {safe_error_message(media_error)}",
                    ))
                    continue
            target_name = document_image_filename(index, mime_type)
            target = asset_dir / target_name
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(payload)
            relative_path = target.relative_to(output_dir).as_posix()
            manifest = file_manifest(
                target,
                output_dir,
                item_type="image",
                token=provider_token,
                name=target.name,
                item_path=relative_path,
                asset_kind="image",
                provider_asset_id=relative_path,
            )
            manifests.append(manifest)
            replacements[source_url] = relative_path
        for source_url, relative_path in replacements.items():
            rewritten = rewritten.replace(source_url, relative_path)
            # HTML attributes may encode query separators (for example ``&amp;``).  The downloader uses the
            # decoded URL for the authenticated request, so replace its encoded spelling as well to guarantee no
            # provider URL survives in the normalized document.
            rewritten = rewritten.replace(html.escape(source_url, quote=False), relative_path)
        # The Feishu export uses HTML image blocks.  Several Markdown previewers intentionally escape raw HTML,
        # which made a successfully downloaded image appear as literal `<img ...>` text.  Convert only the local
        # paths written by this worker; unresolved provider URLs remain untouched so their item failure is visible.
        rewritten = normalize_materialized_html_images(rewritten)
        return rewritten, manifests, failures

    def download_embedded_image(self, image_url: str) -> Tuple[bytes, str, str]:
        """Downloads and validates a provider image URL with tenant authorization."""
        normalized = urllib.parse.urljoin("https://open.feishu.cn", str(image_url or "").strip())
        response = self.request("GET", normalized)
        mime_type = str(response.headers.get("Content-Type", "") or "").split(";", 1)[0].strip()
        name = parse_content_disposition_filename(response.headers.get("Content-Disposition", ""), "image")
        return validate_image_payload(response.content, name, mime_type)

    def list_document_image_tokens(self, document_token: str) -> List[str]:
        """Reads durable image block tokens for a single document, preserving provider order."""
        tokens: List[str] = []
        page_token = ""
        while True:
            try:
                params: Dict[str, Any] = {"document_revision_id": "-1", "page_size": 500}
                if page_token:
                    params["page_token"] = page_token
                data = self.api_json(
                    "GET",
                    f"https://open.feishu.cn/open-apis/docx/v1/documents/{urllib.parse.quote(document_token)}/blocks",
                    params=params,
                )
            except Exception:
                return tokens
            raw_items = data.get("items", [])
            if isinstance(raw_items, list):
                for item in raw_items:
                    if not isinstance(item, dict):
                        continue
                    try:
                        block_type = int(item.get("block_type", 0) or 0)
                    except (TypeError, ValueError):
                        block_type = 0
                    if block_type != 27:
                        continue
                    image = item.get("image", {})
                    if not isinstance(image, dict):
                        continue
                    token = str(image.get("token", "") or image.get("file_token", "") or "").strip()
                    if token and token not in tokens:
                        tokens.append(token)
            next_page_token = str(data.get("page_token", "") or data.get("next_page_token", "") or "").strip()
            if not bool(data.get("has_more")) or not next_page_token or next_page_token == page_token:
                break
            page_token = next_page_token
        return tokens

    def download_media(self, media_token: str) -> Tuple[bytes, str, str]:
        """Downloads and validates a durable Feishu media token."""
        url = f"https://open.feishu.cn/open-apis/drive/v1/medias/{urllib.parse.quote(media_token)}/download"
        response = self.request("GET", url)
        mime_type = str(response.headers.get("Content-Type", "") or "").split(";", 1)[0].strip()
        name = parse_content_disposition_filename(response.headers.get("Content-Disposition", ""), media_token)
        return validate_image_payload(response.content, name, mime_type)

    def get_docx_sync_metadata(self, document_token: str) -> Dict[str, str]:
        """Returns provider-owned title/revision values without deriving them from filenames or local timestamps."""
        data = self.api_json(
            "GET",
            f"https://open.feishu.cn/open-apis/docx/v1/documents/{urllib.parse.quote(document_token)}",
        )
        document = data.get("document", data)
        if not isinstance(document, dict):
            return {}
        title = str(document.get("title", "") or data.get("title", "") or "").strip()
        revision = str(
            document.get("revision_id", "")
            or document.get("revision", "")
            or document.get("version", "")
            or data.get("revision_id", "")
            or data.get("revision", "")
            or ""
        ).strip()
        return {"title": title, "revision": revision}

    @staticmethod
    def extract_content_text(data: Dict[str, Any]) -> str:
        candidates = [
            data.get("content"),
            data.get("doc_content"),
            data.get("markdown"),
            data.get("raw_content"),
        ]
        document = data.get("document")
        if isinstance(document, dict):
            candidates.extend([
                document.get("content"),
                document.get("doc_content"),
                document.get("markdown"),
                document.get("raw_content"),
            ])
        for candidate in candidates:
            if isinstance(candidate, str) and candidate:
                return candidate
        return ""

    def download_file(self, file_token: str) -> Tuple[bytes, str, int]:
        url = f"https://open.feishu.cn/open-apis/drive/v1/files/{urllib.parse.quote(file_token)}/download"
        response = self.request("GET", url)
        filename = parse_content_disposition_filename(response.headers.get("Content-Disposition", ""), file_token)
        header_mime = str(response.headers.get("Content-Type", "") or "").split(";", 1)[0].strip().lower()
        filename_mime, _ = mimetypes.guess_type(filename)
        if header_mime.startswith("image/") or str(filename_mime or "").startswith("image/"):
            payload, _, _ = validate_image_payload(response.content, filename, header_mime or str(filename_mime or ""))
            return payload, filename, len(payload)
        return response.content, filename, len(response.content)


def supported_image_mime_for_path(path: Path) -> str:
    """Returns the image MIME inferred from a supported binary signature, or empty for invalid bytes."""
    try:
        return detected_image_mime(path.read_bytes())
    except OSError:
        return ""


def existing_document_assets_are_valid(root_dir: Path, relative_path: str, asset_kind: str) -> bool:
    """Prevents stale invalid embedded images from being accepted by incremental manifest reuse."""
    if str(asset_kind or "").lower() != "document":
        return True
    root = root_dir.resolve()
    document = (root / str(relative_path or "").replace("\\", "/")).resolve()
    if not document.is_file() or not document.is_relative_to(root):
        return False
    image_dir = document.parent / "IMAJES"
    if not image_dir.is_dir() or not image_dir.is_relative_to(root):
        return True
    return all(
        detected_image_mime(asset.read_bytes())
        for asset in image_dir.iterdir()
        if asset.is_file()
    )


def validate_image_payload(payload: bytes, name: str, mime_type: str) -> Tuple[bytes, str, str]:
    """Rejects successful provider responses whose bytes do not have a supported image signature."""
    normalized_mime = str(mime_type or "").split(";", 1)[0].strip().lower()
    data = bytes(payload or b"")
    detected_mime = detected_image_mime(data)
    if not detected_mime:
        raise ValueError(
            "Feishu image response is not a supported image payload: "
            f"name={safe_error_message(name)}, mimeType={normalized_mime or 'unknown'}"
        )
    # The signature is authoritative. A misleading image/* header must not select the wrong local extension.
    return data, name, detected_mime


def save_bytes(content: bytes, output_dir: Path, filename: str) -> Path:
    output_dir.mkdir(parents=True, exist_ok=True)
    target = output_dir / sanitize_name(filename)
    target.write_bytes(content)
    return target


def file_manifest(
    target: Path,
    base_dir: Path,
    *,
    item_type: str,
    token: str,
    name: str,
    item_path: str,
    asset_kind: str,
    provider_title: str = "",
    provider_revision: str = "",
    provider_asset_id: str = "",
) -> Dict[str, Any]:
    try:
        relative_path = target.relative_to(base_dir).as_posix()
    except ValueError:
        relative_path = target.name
    content = target.read_bytes()
    mime_type, _ = mimetypes.guess_type(target.name)
    return {
        "type": item_type,
        "token": token,
        "name": name,
        "path": item_path,
        "relativePath": relative_path,
        "checksum": hashlib.sha256(content).hexdigest(),
        "mimeType": mime_type or "application/octet-stream",
        "sizeBytes": len(content),
        "assetKind": asset_kind,
        "providerTitle": provider_title,
        "providerRevision": provider_revision,
        "providerAssetId": provider_asset_id,
    }


def markdown_image_references(markdown: str) -> List[Dict[str, str]]:
    """Returns unique Markdown/HTML image references in source order.

    Feishu may return image blocks as ``<img href="...">`` instead of Markdown or ``src``.  The parser therefore
    scans complete image tags and accepts both provider spellings, while unescaping HTML entities before download.
    """
    references: List[Dict[str, str]] = []
    seen: set[str] = set()
    located: List[Tuple[int, str]] = []
    for match in MARKDOWN_IMAGE_PATTERN.finditer(markdown or ""):
        url = str(match.group(1) or match.group(2) or "").strip()
        if url:
            located.append((match.start(), url))
    for tag in HTML_IMAGE_TAG_PATTERN.finditer(markdown or ""):
        for attribute in HTML_IMAGE_ATTRIBUTE_PATTERN.finditer(tag.group(0)):
            url = str(attribute.group(1) or attribute.group(2) or attribute.group(3) or "").strip()
            if url:
                located.append((tag.start(), url))
                break
    for _, raw_url in sorted(located, key=lambda item: item[0]):
        url = html.unescape(raw_url).strip()
        if url and url not in seen and not url.lower().startswith("data:"):
            seen.add(url)
            references.append({"url": url})
    return references


def normalize_materialized_html_images(markdown: str) -> str:
    """Converts worker-owned local HTML image tags into portable Markdown image syntax.

    A rewritten `href` can coexist with Feishu's opaque `src` token, therefore inspect all image attributes and
    select only the `IMAJES/` relative path.  This keeps remote URLs and non-image HTML unchanged.
    """
    def replace_tag(match: re.Match[str]) -> str:
        tag = match.group(0)
        for attribute in HTML_IMAGE_ATTRIBUTE_PATTERN.finditer(tag):
            value = html.unescape(str(attribute.group(1) or attribute.group(2) or attribute.group(3) or "")).strip()
            if value.startswith("IMAJES/"):
                return f"![]({value})"
        return tag

    return HTML_IMAGE_TAG_PATTERN.sub(replace_tag, markdown or "")


def document_asset_directory(markdown_dir: Path, document_token: str) -> Path:
    """Keeps every Feishu document image directly below its document directory."""
    del document_token
    return markdown_dir / "IMAJES"


def document_image_filename(index: int, mime_type: str) -> str:
    """Names images by their appearance order within one Feishu document."""
    guessed = mimetypes.guess_extension(str(mime_type or "").split(";", 1)[0].strip()) or ".bin"
    return f"image-{index:03d}{guessed.lower()}"


def safe_image_filename(name: str, index: int, mime_type: str) -> str:
    """Retained for compatibility with callers that need a sanitized provider filename."""
    candidate = sanitize_name(name, f"image-{index}")
    suffix = Path(candidate).suffix.lower()
    if not suffix or len(suffix) > 8:
        guessed = mimetypes.guess_extension(str(mime_type or "").split(";", 1)[0].strip()) or ".bin"
        candidate = f"{Path(candidate).stem}{guessed}"
    return candidate


def load_resume_checkpoint(checkpoint_path: str = "") -> Dict[str, Any]:
    if not checkpoint_path:
        return {}
    path = Path(checkpoint_path).expanduser()
    if not path.exists():
        return {}
    data = json.loads(path.read_text(encoding="utf-8-sig"))
    return data if isinstance(data, dict) else {}


def list_field(value: Any) -> List[Any]:
    return value if isinstance(value, list) else []


def load_incremental_manifest(manifest_path: str) -> Dict[str, Dict[str, Any]]:
    """Loads the last remote metadata snapshot used to avoid downloading unchanged items."""
    if not manifest_path:
        return {}
    path = Path(manifest_path).expanduser()
    if not path.is_file():
        return {}
    try:
        payload = json.loads(path.read_text(encoding="utf-8-sig"))
    except (OSError, ValueError):
        return {}
    items = payload.get("items", {}) if isinstance(payload, dict) else {}
    return {str(token): value for token, value in items.items() if str(token) and isinstance(value, dict)}


def save_incremental_manifest(manifest_path: str, items: Dict[str, Dict[str, Any]]) -> None:
    """Atomically persists provider metadata so a process crash never leaves a half-written index."""
    if not manifest_path:
        return
    path = Path(manifest_path).expanduser()
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary_path = path.with_suffix(path.suffix + ".tmp")
    temporary_path.write_text(
        json.dumps({"version": 1, "items": items}, ensure_ascii=False, indent=2),
        encoding="utf-8",
    )
    temporary_path.replace(path)


def remove_stale_materialized_file(manifest: Dict[str, Any], root_dir: Path) -> None:
    relative_path = str(manifest.get("relativePath", "") or "").replace("\\", "/").strip("/")
    if not relative_path:
        return
    target = (root_dir / relative_path).resolve()
    root = root_dir.resolve()
    if not target.is_file() or not target.is_relative_to(root):
        return
    target.unlink()
    asset_dir = target.parent / "IMAJES"
    if asset_dir.is_dir() and not any(asset_dir.iterdir()):
        asset_dir.rmdir()
    parent = target.parent
    while parent != root and parent.is_dir():
        if any(parent.iterdir()):
            break
        parent.rmdir()
        parent = parent.parent


def cleanup_stale_manifest_items(
    previous_manifest: Dict[str, Dict[str, Any]],
    active_tokens: set[str],
    root_dir: Path,
) -> None:
    """Deletes only durable files absent from a complete, successful provider discovery."""
    for token, manifest in previous_manifest.items():
        if token not in active_tokens:
            remove_stale_materialized_file(manifest, root_dir)


def provider_item_signature(item: Dict[str, Any]) -> str:
    """Builds a stable metadata signature without fetching document bodies or embedded assets."""
    fields = {
        "type": item.get("type", item.get("docs_type", "")),
        "token": item.get("token", item.get("docs_token", "")),
        "name": item.get("name", item.get("title", "")),
        "modified_time": item.get(
            "modified_time",
            item.get("modifiedTime", ""),
        ),
        "created_time": item.get(
            "created_time",
            item.get("createdTime", ""),
        ),
        "size": item.get(
            "size",
            item.get("size_bytes", item.get("sizeBytes", "")),
        ),
        "revision": item.get(
            "revision",
            item.get("revision_id", item.get("version", "")),
        ),
    }
    return json.dumps(
        fields,
        ensure_ascii=False,
        sort_keys=True,
        separators=(",", ":"),
    )


def write_resume_checkpoint(
    checkpoint_path: str,
    *,
    current_folder_token: str,
    current_path: str,
    page_token: str,
    visited_folder_tokens: List[str],
    downloaded_items: List[Dict[str, Any]],
) -> None:
    if not checkpoint_path:
        return
    path = Path(checkpoint_path).expanduser()
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps({
        "current_folder_token": current_folder_token,
        "page_token": page_token,
        "current_path": current_path,
        "visited_folder_tokens": visited_folder_tokens,
        "downloaded_items": downloaded_items,
    }, ensure_ascii=False, indent=2), encoding="utf-8")


def path_to_dir(output_base: Path, path_text: str, fallback: Path) -> Path:
    cleaned = [sanitize_name(part) for part in str(path_text or "").replace("\\", "/").split("/") if part.strip()]
    return output_base.joinpath(*cleaned) if cleaned else fallback


def download_markdown_document(
    client: Any,
    document_token: str,
    output_dir: Path,
) -> Tuple[bytes, str, int, List[Dict[str, Any]], List[Dict[str, Any]]]:
    """Uses image-aware download when the real client supports it while preserving old client test doubles."""
    enhanced = getattr(client, "download_docx_markdown_with_assets", None)
    if callable(enhanced):
        return enhanced(document_token, output_dir)
    content, suggested_name, size = client.download_docx_markdown(document_token)
    return content, suggested_name, size, [], []


def download_folder(
    client: FeishuClient,
    folder_token: str,
    output_base: Path,
    *,
    max_files: int = 0,
    stats: Optional[Dict[str, int]] = None,
    folder_name: str = "",
    file_extension: str = "md",
    resume_checkpoint: Optional[Dict[str, Any]] = None,
    checkpoint_path: str = "",
    manifest_path: str = "",
) -> Dict[str, Any]:
    started_at = time.perf_counter()
    meta = client.get_folder_meta(folder_token)
    resolved_folder_name = folder_name or str(meta.get("name", "") or folder_token)
    checkpoint = resume_checkpoint or {}
    checkpoint_path_parts = [part for part in re.split(r"[\\/]+", str(checkpoint.get("current_path", "") or "")) if part]
    # A backend-owned resource title may intentionally differ from the provider folder title. Once a checkpoint has
    # selected that local root, resume must keep using it; changing back to provider metadata makes saved_path point
    # at a directory that does not contain the already downloaded files.
    local_root_name = checkpoint_path_parts[0] if checkpoint_path_parts else resolved_folder_name
    root_dir = output_base / sanitize_name(local_root_name)
    counters = stats if stats is not None else {
        "folders": 0,
        "files": 0,
        "assets": 0,
        "skipped": 0,
        "failed": 0,
        "limit_reached": 0,
        "bytes": 0,
        "changed_files": 0,
        "unchanged_files": 0,
    }
    counters.setdefault("assets", 0)
    counters.setdefault("bytes", 0)
    visited_folder_tokens = [str(token) for token in list_field(checkpoint.get("visited_folder_tokens")) if str(token)]
    visited_set = set(visited_folder_tokens)
    downloaded_items = [item for item in list_field(checkpoint.get("downloaded_items")) if isinstance(item, dict)]
    downloaded_tokens = {str(item.get("token", "")) for item in downloaded_items if str(item.get("token", ""))}
    incremental_manifest = load_incremental_manifest(manifest_path)
    previous_manifest = {token: dict(value) for token, value in incremental_manifest.items()}
    discovered_tokens: set[str] = set()
    discovered_items: List[Dict[str, Any]] = []
    changed_items: List[Dict[str, Any]] = []
    unchanged_items: List[Dict[str, Any]] = []
    failed_items: List[Dict[str, Any]] = []
    item_timings: List[Dict[str, Any]] = []
    latest_checkpoint: Dict[str, Any] = {
        "current_folder_token": str(checkpoint.get("current_folder_token", "") or folder_token),
        "page_token": str(checkpoint.get("page_token", "") or ""),
        "current_path": str(checkpoint.get("current_path", "") or resolved_folder_name),
        "visited_folder_tokens": visited_folder_tokens,
        "downloaded_items": downloaded_items,
    }

    def remember_checkpoint(current_token: str, path_text: str, page_token: str) -> None:
        latest_checkpoint.update({
            "current_folder_token": current_token,
            "page_token": page_token,
            "current_path": path_text,
            "visited_folder_tokens": visited_folder_tokens,
            "downloaded_items": downloaded_items,
        })
        write_resume_checkpoint(
            checkpoint_path,
            current_folder_token=current_token,
            current_path=path_text,
            page_token=page_token,
            visited_folder_tokens=visited_folder_tokens,
            downloaded_items=downloaded_items,
        )

    def remember_item_timing(item_metadata: Dict[str, Any], status: str, started_at: float) -> None:
        """Persist one real provider item timing so global sync cost can be separated from per-document cost."""
        item_timings.append({
            "type": item_metadata.get("type", ""),
            "token": item_metadata.get("token", ""),
            "path": item_metadata.get("path", ""),
            "status": status,
            "elapsed_ms": round((time.perf_counter() - started_at) * 1000, 3),
        })

    def walk(current_token: str, current_dir: Path, path_text: str, start_page_token: str = "") -> None:
        if current_token not in visited_set:
            visited_set.add(current_token)
            visited_folder_tokens.append(current_token)
        current_dir.mkdir(parents=True, exist_ok=True)
        page_token = start_page_token
        while True:
            remember_checkpoint(current_token, path_text, page_token)
            page_items, next_page_token, has_more = client.list_folder_page(current_token, page_token)
            for item in page_items:
                if max_files > 0 and counters["files"] >= max_files:
                    counters["limit_reached"] = 1
                    remember_checkpoint(current_token, path_text, page_token)
                    return
                item_type = str(item.get("type", "") or "")
                item_name = str(item.get("name", "") or item.get("token", "") or "unnamed")
                item_token = str(item.get("token", "") or "")
                if not item_type or not item_token:
                    counters["skipped"] += 1
                    continue
                item_path = f"{path_text}/{item_name}" if path_text else item_name
                if item_type == "folder":
                    if item_token in visited_set:
                        continue
                    counters["folders"] += 1
                    walk(item_token, current_dir / sanitize_name(item_name), item_path)
                    if max_files > 0 and counters["files"] >= max_files:
                        counters["limit_reached"] = 1
                        remember_checkpoint(current_token, path_text, page_token)
                        return
                    continue
                item_metadata = {
                    "type": item_type,
                    "token": item_token,
                    "name": item_name,
                    "path": item_path,
                    "parentToken": current_token,
                    "modifiedTime": str(item.get("modified_time", item.get("modifiedTime", "")) or ""),
                    "createdTime": str(item.get("created_time", item.get("createdTime", "")) or ""),
                    "revision": str(item.get("revision", item.get("revision_id", item.get("version", ""))) or ""),
                    "sizeBytes": int(item.get("size", item.get("size_bytes", item.get("sizeBytes", 0))) or 0),
                    "signature": provider_item_signature(item),
                }
                item_started_at = time.perf_counter()
                discovered_items.append(item_metadata)
                discovered_tokens.add(item_token)
                existing_manifest = incremental_manifest.get(item_token, {})
                expected_relative_path = str(existing_manifest.get("relativePath", "") or "")
                existing_target = root_dir / expected_relative_path if expected_relative_path else None
                unchanged = (
                    bool(existing_manifest)
                    and existing_manifest.get("signature") == provider_item_signature(item)
                    and existing_target is not None
                    and existing_target.is_file()
                    and existing_document_assets_are_valid(root_dir, expected_relative_path, str(existing_manifest.get("assetKind", "")))
                )
                if unchanged:
                    counters["skipped"] += 1
                    counters["unchanged_files"] += 1
                    unchanged_items.append({**item_metadata, **previous_manifest})
                    remember_item_timing(item_metadata, "unchanged", item_started_at)
                    continue
                if item_token in downloaded_tokens and not previous_manifest:
                    counters["skipped"] += 1
                    remember_item_timing(item_metadata, "skipped", item_started_at)
                    continue
                try:
                    if item_type in EXPORTABLE_TYPES:
                        if file_extension == "md":
                            content, suggested_name, _, image_items, image_failures = download_markdown_document(
                                client, item_token, current_dir)
                            normalized_markdown, image_items = normalize_document_image_items(
                                content.decode("utf-8", errors="replace"), image_items, current_dir)
                            content = normalized_markdown.encode("utf-8")
                            downloaded_items.extend(image_items)
                            counters["assets"] += len(image_items)
                            counters["bytes"] += sum(int(item.get("sizeBytes", 0) or 0) for item in image_items)
                            failed_items.extend(image_failures)
                            counters["failed"] += len(image_failures)
                        else:
                            content, suggested_name, _ = client.export_docx(item_token, file_extension)
                        # Folder listing names are the authoritative Feishu document titles.  Keep Unicode intact;
                        # only Windows-forbidden path characters are escaped by save_bytes for filesystem safety.
                        extension = f".{file_extension.lower()}"
                        target_name = item_name if item_name.lower().endswith(extension) else f"{item_name}{extension}"
                        relative_markdown_path = (current_dir / target_name).relative_to(root_dir).as_posix()
                        if item_type in EXPORTABLE_TYPES and file_extension == "md":
                            content = qualify_materialized_markdown(content.decode("utf-8", errors="replace"), relative_markdown_path).encode("utf-8")
                        target = save_bytes(content, current_dir, target_name)
                        counters["bytes"] += target.stat().st_size
                        counters["files"] += 1
                        manifest = file_manifest(
                            target,
                            root_dir,
                            item_type=item_type,
                            token=item_token,
                            name=item_name,
                            item_path=item_path,
                            asset_kind="document")
                    elif item_type in DIRECT_DOWNLOAD_TYPES:
                        content, suggested_name, _ = client.download_file(item_token)
                        target = save_bytes(content, current_dir, suggested_name or item_name)
                        counters["bytes"] += target.stat().st_size
                        counters["files"] += 1
                        mime_type, _ = mimetypes.guess_type(target.name)
                        asset_kind = "image" if str(mime_type or "").startswith("image/") else "attachment"
                        manifest = file_manifest(
                            target,
                            root_dir,
                            item_type=item_type,
                            token=item_token,
                            name=item_name,
                            item_path=item_path,
                            asset_kind=asset_kind)
                    else:
                        counters["skipped"] += 1
                        remember_item_timing(item_metadata, "skipped", item_started_at)
                        continue
                    downloaded_tokens.add(item_token)
                    downloaded_items.append(manifest)
                    manifest["signature"] = provider_item_signature(item)
                    incremental_manifest[item_token] = manifest
                    if expected_relative_path and expected_relative_path != manifest.get("relativePath", ""):
                        remove_stale_materialized_file(existing_manifest, root_dir)
                    changed_items.append({**item_metadata, **manifest})
                    counters["changed_files"] += 1
                    remember_item_timing(item_metadata, "changed", item_started_at)
                    save_incremental_manifest(manifest_path, incremental_manifest)
                    remember_checkpoint(current_token, path_text, page_token)
                except Exception as exc:
                    counters["failed"] += 1
                    failed_items.append({
                        "type": item_type,
                        "token": item_token,
                        "name": item_name,
                        "path": item_path,
                        "message": str(exc),
                    })
                    remember_item_timing(item_metadata, "failed", item_started_at)
                    remember_checkpoint(current_token, path_text, page_token)
                    client.log(f"FAILED {item_type}: {item_name}, token={item_token}, error={exc}")
            if not has_more or not next_page_token:
                remember_checkpoint(current_token, path_text, "")
                break
            page_token = next_page_token
            remember_checkpoint(current_token, path_text, page_token)

    start_token = str(checkpoint.get("current_folder_token", "") or folder_token)
    start_path = str(checkpoint.get("current_path", "") or resolved_folder_name)
    start_page_token = str(checkpoint.get("page_token", "") or "")
    start_dir = path_to_dir(output_base, start_path, root_dir)
    walk(start_token, start_dir, start_path, start_page_token)
    complete_discovery = (
        counters.get("limit_reached", 0) == 0
        and not failed_items
        and not str(latest_checkpoint.get("page_token", "") or "")
        and latest_checkpoint.get("current_folder_token") == folder_token
    )
    if complete_discovery:
        cleanup_stale_manifest_items(previous_manifest, discovered_tokens, root_dir)
        for token in set(previous_manifest) - discovered_tokens:
            incremental_manifest.pop(token, None)
    save_incremental_manifest(manifest_path, incremental_manifest)
    return {
        "saved_path": str(root_dir),
        "folder_name": resolved_folder_name,
        "stats": counters,
            "checkpoint": latest_checkpoint,
            "failed_items": failed_items,
            "discovered_items": discovered_items,
            "changed_items": changed_items,
            "unchanged_items": unchanged_items,
            "item_timings": item_timings,
            "incremental": bool(manifest_path),
            "elapsed_ms": round((time.perf_counter() - started_at) * 1000),
        }


def download_from_url(
    client: FeishuClient,
    resource_url: str,
    output_dir: Path,
    *,
    max_files: int = 0,
    file_extension: str = "md",
    resume_checkpoint: Optional[Dict[str, Any]] = None,
    checkpoint_path: str = "",
    manifest_path: str = "",
) -> Dict[str, Any]:
    started_at = time.perf_counter()
    parsed = parse_feishu_url(resource_url)
    resource_type = parsed["resource_type"]
    token = parsed["token"]
    if resource_type == "folder":
        folder_result = download_folder(
            client,
            token,
            output_dir,
            max_files=max_files,
            file_extension=file_extension,
            resume_checkpoint=resume_checkpoint,
            checkpoint_path=checkpoint_path,
            manifest_path=manifest_path,
        )
        return {**parsed, **folder_result, "max_files": max_files}
    if resource_type == "docx":
        # Metadata is read from the same real cloud document. A metadata endpoint failure must not be hidden: it is
        # reported in the summary while the independently successful export remains usable for body fingerprints.
        provider_metadata: Dict[str, str] = {}
        provider_metadata_error = ""
        try:
            provider_metadata = client.get_docx_sync_metadata(token)
        except Exception as exc:
            provider_metadata_error = str(exc)
        if file_extension == "md":
            content, suggested_name, size, image_items, image_failures = download_markdown_document(
                client, token, output_dir)
            normalized_markdown, image_items = normalize_document_image_items(
                content.decode("utf-8", errors="replace"), image_items, output_dir)
            content = normalized_markdown.encode("utf-8")
        else:
            content, suggested_name, size = client.export_docx(token, file_extension)
        canonical_title = str(provider_metadata.get("title", "") or "").strip()
        # The document title is the stable visible filename.  Python writes UTF-8 and Windows receives the Unicode
        # path unchanged; the provider token remains only in the manifest for idempotent identity.
        extension = f".{file_extension.lower()}"
        target_name = canonical_title if canonical_title.lower().endswith(extension) else f"{canonical_title}{extension}"
        if not canonical_title:
            target_name = suggested_name or f"{token}{extension}"
        if file_extension == "md":
            relative_markdown_path = Path(target_name).as_posix()
            content = qualify_materialized_markdown(content.decode("utf-8", errors="replace"), relative_markdown_path).encode("utf-8")
        target = save_bytes(content, output_dir, target_name)
        downloaded_item = file_manifest(
            target,
            output_dir,
            item_type="docx",
            token=token,
            name=target.name,
            item_path=target.name,
            asset_kind="document",
            provider_title=provider_metadata.get("title", ""),
            provider_revision=provider_metadata.get("revision", ""))
        return {
            **parsed,
            "saved_path": str(target),
            "file_size": size,
            "file_extension": file_extension,
            "stats": {
                "folders": 0,
                "files": 1,
                "assets": len(image_items),
                "skipped": 0,
                "failed": len(image_failures),
                "limit_reached": 0,
                "bytes": target.stat().st_size,
            },
            "elapsed_ms": round((time.perf_counter() - started_at) * 1000),
            "checkpoint": {
                "current_folder_token": "",
                "page_token": "",
                "current_path": target.name,
                "visited_folder_tokens": [],
                "downloaded_items": [downloaded_item, *image_items],
            },
            "failed_items": image_failures,
            "provider": {
                "title": provider_metadata.get("title", ""),
                "revision": provider_metadata.get("revision", ""),
                "metadata_error": provider_metadata_error,
            },
        }
    if resource_type == "file":
        content, suggested_name, size = client.download_file(token)
        target = save_bytes(content, output_dir, suggested_name or token)
        mime_type, _ = mimetypes.guess_type(target.name)
        downloaded_item = file_manifest(
            target,
            output_dir,
            item_type="file",
            token=token,
            name=target.name,
            item_path=target.name,
            asset_kind="image" if str(mime_type or "").startswith("image/") else "attachment")
        return {
            **parsed,
            "saved_path": str(target),
            "file_size": size,
            "stats": {"folders": 0, "files": 1, "skipped": 0, "failed": 0, "limit_reached": 0},
            "checkpoint": {
                "current_folder_token": "",
                "page_token": "",
                "current_path": target.name,
                "visited_folder_tokens": [],
                "downloaded_items": [downloaded_item],
            },
            "failed_items": [],
        }
    raise RuntimeError(f"Unsupported resource type: {resource_type}")


def item_to_candidate(item: Dict[str, Any], path_text: str, depth: int) -> Dict[str, Any]:
    item_type = str(item.get("type", "") or item.get("docs_type", "") or "")
    item_name = str(item.get("name", "") or item.get("title", "") or item.get("token", "") or "unnamed")
    item_token = str(item.get("token", "") or item.get("docs_token", "") or "")
    item_url = str(item.get("url", "") or "")
    if item_type and item_token and not item_url:
        item_url = build_url_from_type_token(item_type, item_token)
    return {
        "resource_type": item_type,
        "type": item_type,
        "token": item_token,
        "name": item_name,
        "path": path_text,
        "url": item_url,
        "depth": depth,
        "parent_token": str(item.get("parent_token", "") or ""),
        "downloadable": item_type in EXPORTABLE_TYPES or item_type in DIRECT_DOWNLOAD_TYPES or item_type == "folder",
    }


def list_root_candidates(client: FeishuClient, root_url: str, max_depth: int = 1) -> List[Dict[str, Any]]:
    parsed = parse_feishu_url(root_url)
    if parsed["resource_type"] != "folder":
        raise RuntimeError("Root URL must be a Feishu folder URL")
    candidates: List[Dict[str, Any]] = []
    queue: List[Tuple[str, int, str]] = [(parsed["token"], 0, "")]
    while queue:
        folder_token, depth, base_path = queue.pop(0)
        if depth >= max_depth:
            continue
        for item in client.list_folder_items(folder_token):
            item_type = str(item.get("type", "") or "")
            item_name = str(item.get("name", "") or item.get("token", "") or "unnamed")
            item_token = str(item.get("token", "") or "")
            item_path = f"{base_path}/{item_name}" if base_path else item_name
            candidate = item_to_candidate(item, item_path, depth + 1)
            candidates.append(candidate)
            if item_type == "folder" and item_token:
                queue.append((item_token, depth + 1, item_path))
    return candidates


def search_root_candidates(client: FeishuClient, root_url: str, keyword: str, max_depth: int = 8) -> List[Dict[str, Any]]:
    needle = keyword.strip().casefold()
    if not needle:
        raise RuntimeError("--search-root requires a non-empty keyword")
    return [
        item
        for item in list_root_candidates(client, root_url, max_depth=max_depth)
        if needle in str(item.get("name", "")).casefold() or needle in str(item.get("path", "")).casefold()
    ]


def find_folder_by_name(client: FeishuClient, root_url: str, folder_name: str, max_depth: int = 8) -> Dict[str, Any]:
    parsed = parse_feishu_url(root_url)
    if parsed["resource_type"] != "folder":
        raise RuntimeError("--root-url must be a Feishu folder URL")
    target = folder_name.strip().lower()
    queue: List[Tuple[str, int, str]] = [(parsed["token"], 0, "")]
    while queue:
        folder_token, depth, path_text = queue.pop(0)
        if depth > max_depth:
            continue
        for item in client.list_folder_items(folder_token):
            if str(item.get("type", "") or "") != "folder":
                continue
            item_name = str(item.get("name", "") or "")
            item_token = str(item.get("token", "") or "")
            next_path = f"{path_text}/{item_name}" if path_text else item_name
            if target in item_name.lower():
                return {
                    "resource_type": "folder",
                    "token": item_token,
                    "name": item_name,
                    "path": next_path,
                    "url": f"https://my.feishu.cn/drive/folder/{item_token}",
                }
            queue.append((item_token, depth + 1, next_path))
    raise RuntimeError(f"Folder name not found under root: {folder_name}")


def resolve_target_url(client: FeishuClient, args: argparse.Namespace) -> str:
    if args.url:
        return args.url
    if args.find_folder_name:
        config = load_default_config(args.config_path)
        root = resolve_root(config, args.root_url, args.root_key)
        match = find_folder_by_name(client, root["url"], args.find_folder_name, max_depth=args.max_depth)
        print("FEISHU_FOUND_FOLDER=" + json.dumps(match, ensure_ascii=False))
        return match["url"]
    if args.search:
        docs_types = [item.strip() for item in args.docs_types.split(",") if item.strip()] if args.docs_types else None
        search_data = client.search_docs(args.search, args.search_count, 0, docs_types)
        items = search_data.get("items", [])
        print("FEISHU_SEARCH_RESULTS=" + json.dumps(items, ensure_ascii=False))
        if not items:
            raise RuntimeError(f"No search results for: {args.search}")
        index = max(args.search_index, 0)
        if index >= len(items):
            raise RuntimeError(f"--search-index {index} is out of range; got {len(items)} results")
        return build_url_from_search_item(items[index])
    raise RuntimeError("Provide --url, --search, or --find-folder-name with --root-url.")


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="Download Feishu/Lark cloud resources by URL or search.")
    parser.add_argument("--url", default="", help="Feishu URL: /drive/folder/{token}, /docx/{token}, or /file/{token}.")
    parser.add_argument("--search", default="", help="Search keyword; downloads the selected search result.")
    parser.add_argument("--search-index", type=int, default=0, help="Zero-based search result index to download.")
    parser.add_argument("--search-count", type=int, default=10, help="Search result count.")
    parser.add_argument("--docs-types", default="", help="Optional comma-separated search types, e.g. docx,file,folder.")
    parser.add_argument("--find-folder-name", default="", help="Find a nested folder by name under --root-url and download it.")
    parser.add_argument("--root-url", default="", help="Root Feishu folder URL for --find-folder-name.")
    parser.add_argument("--root-key", default="", help="Configured root key from config/defaults.json. Defaults to default_root.")
    parser.add_argument("--config-path", default="", help="Optional defaults.json path. Defaults to ../config/defaults.json next to this skill.")
    parser.add_argument("--list-root", action="store_true", help="List configured or explicit root folder candidates without downloading.")
    parser.add_argument("--list-depth", type=int, default=1, help="Folder depth for --list-root. Use 1 for immediate children.")
    parser.add_argument("--search-root", default="", help="Recursively search under configured or explicit root folder without downloading.")
    parser.add_argument("--dry-run", action="store_true", help="Resolve/list candidates but do not download.")
    parser.add_argument("--max-depth", type=int, default=8, help="Maximum folder search depth for --find-folder-name.")
    parser.add_argument("--output-dir", default="", help="Output directory. Defaults to the current user's Downloads folder.")
    parser.add_argument("--summary-path", default="", help="Optional JSON summary path.")
    parser.add_argument("--resume-checkpoint-path", default="", help="Optional UTF-8 JSON checkpoint path for resumable folder download.")
    parser.add_argument("--manifest-path", default="", help="Optional UTF-8 JSON provider metadata manifest for incremental folder sync.")
    parser.add_argument("--file-extension", default="md", choices=["md", "docx", "pdf"], help="Feishu export format.")
    parser.add_argument("--max-files", type=int, default=0, help="Folder download limit. Use 0 for the full folder.")
    parser.add_argument("--timeout-seconds", type=int, default=DEFAULT_TIMEOUT_SECONDS, help="HTTP request timeout in seconds.")
    parser.add_argument("--app-id", default="", help="Feishu app id. Prefer env or APPKEY.md when empty.")
    parser.add_argument("--app-secret", default="", help="Feishu app secret. Prefer env or APPKEY.md when empty.")
    parser.add_argument("--access-token", default="", help="User OAuth access token supplied through a private temporary file; never log this value.")
    parser.add_argument("--appkey-path", default="", help="APPKEY.md path. Defaults to searching cwd and parent dirs.")
    parser.add_argument("--no-env", action="store_true", help="Do not read APP_ID/APP_SECRET from environment.")
    parser.add_argument("--quiet", action="store_true", help="Reduce logs.")
    return parser


def write_summary(args: argparse.Namespace, result: Dict[str, Any], prefix: str) -> None:
    summary_text = json.dumps(result, ensure_ascii=False, indent=2)
    if args.summary_path:
        summary_path = Path(args.summary_path).expanduser()
        summary_path.parent.mkdir(parents=True, exist_ok=True)
        summary_path.write_text(summary_text, encoding="utf-8")
    print(prefix + json.dumps(result, ensure_ascii=False))


def run(args: argparse.Namespace) -> Dict[str, Any]:
    config = load_default_config(args.config_path)
    app_id, app_secret, access_token = resolve_credentials(args)
    client = FeishuClient(app_id, app_secret, verbose=not args.quiet, timeout_seconds=args.timeout_seconds, access_token=access_token)

    if args.search_root:
        root = resolve_root(config, args.root_url, args.root_key)
        candidates = search_root_candidates(client, root["url"], args.search_root, max_depth=max(args.max_depth, 1))
        result = {"mode": "search_root", "keyword": args.search_root, "root": root, "count": len(candidates), "candidates": candidates}
        write_summary(args, result, "FEISHU_INSPECT_SUMMARY=")
        return result

    if args.list_root:
        root = resolve_root(config, args.root_url, args.root_key)
        candidates = list_root_candidates(client, root["url"], max_depth=max(args.list_depth, 1))
        result = {"mode": "list_root", "root": root, "count": len(candidates), "candidates": candidates}
        write_summary(args, result, "FEISHU_INSPECT_SUMMARY=")
        return result

    resource_url = resolve_target_url(client, args)
    if args.dry_run:
        parsed = parse_feishu_url(resource_url)
        result = {"mode": "dry_run", "url": resource_url, "parsed": parsed}
        write_summary(args, result, "FEISHU_INSPECT_SUMMARY=")
        return result

    output_dir = Path(args.output_dir).expanduser() if args.output_dir else configured_output_dir(config)
    resume_checkpoint = load_resume_checkpoint(args.resume_checkpoint_path)
    result = download_from_url(
        client,
        resource_url,
        output_dir,
        max_files=max(args.max_files, 0),
        file_extension=args.file_extension,
        resume_checkpoint=resume_checkpoint,
        checkpoint_path=args.resume_checkpoint_path,
        manifest_path=args.manifest_path,
    )
    write_summary(args, result, "FEISHU_DOWNLOAD_SUMMARY=")
    return result


def main() -> int:
    args = build_parser().parse_args()
    run(args)
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as exc:
        print(f"ERROR: {exc}", file=sys.stderr)
        raise
