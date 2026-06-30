import argparse
import json
import os
import re
import sys
import time
import urllib.parse
from pathlib import Path
from typing import Any, Dict, List, Optional, Tuple

import requests


DEFAULT_TIMEOUT_SECONDS = 30
EXPORTABLE_TYPES = {"docx"}
DIRECT_DOWNLOAD_TYPES = {"file"}


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
        if resolved.exists():
            return resolved
    return None


def load_appkey(path: Path) -> Tuple[str, str]:
    content = path.read_text(encoding="utf-8")
    app_id_match = re.search(r"(?im)^APPID\s*$\s*^([^\r\n]+)\s*$", content)
    app_secret_match = re.search(r"(?im)^APP Secret\s*$\s*^([^\r\n]+)\s*$", content)
    app_id = app_id_match.group(1).strip() if app_id_match else ""
    app_secret = app_secret_match.group(1).strip() if app_secret_match else ""
    return app_id, app_secret


def resolve_credentials(args: argparse.Namespace) -> Tuple[str, str]:
    if args.app_id and args.app_secret:
        return args.app_id.strip(), args.app_secret.strip()

    if not args.no_env:
        env_app_id = os.getenv("APP_ID", "").strip()
        env_app_secret = os.getenv("APP_SECRET", "").strip()
        if env_app_id and env_app_secret:
            return env_app_id, env_app_secret

    appkey_path = find_default_appkey_path(args.appkey_path)
    if appkey_path:
        app_id, app_secret = load_appkey(appkey_path)
        if app_id and app_secret:
            return app_id, app_secret

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
    def __init__(self, app_id: str, app_secret: str, verbose: bool = True, timeout_seconds: int = DEFAULT_TIMEOUT_SECONDS) -> None:
        self.app_id = app_id
        self.app_secret = app_secret
        self.verbose = verbose
        self.timeout_seconds = max(int(timeout_seconds), 1)
        self.access_token = self.get_tenant_access_token()

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
        response = requests.request(
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
        response.raise_for_status()
        response.encoding = "utf-8"
        return response

    def get_tenant_access_token(self) -> str:
        url = "https://open.feishu.cn/open-apis/auth/v3/tenant_access_token/internal"
        payload = {"app_id": self.app_id, "app_secret": self.app_secret}
        self.log(f"POST {url}")
        self.log(json.dumps({"app_id": self.app_id, "app_secret": mask_secret(self.app_secret)}, ensure_ascii=False))
        response = requests.post(url, json=payload, headers={"Content-Type": "application/json; charset=utf-8"}, timeout=self.timeout_seconds)
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
        return self.api_json("GET", url)

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
            raise RuntimeError(f"Feishu markdown download failed: {first_error}; raw_content failed: {exc}") from exc
        raise RuntimeError(f"Feishu markdown download failed: {first_error}; raw_content returned empty content")

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
        return response.content, filename, len(response.content)


def save_bytes(content: bytes, output_dir: Path, filename: str) -> Path:
    output_dir.mkdir(parents=True, exist_ok=True)
    target = output_dir / sanitize_name(filename)
    target.write_bytes(content)
    return target


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
) -> Dict[str, Any]:
    meta = client.get_folder_meta(folder_token)
    resolved_folder_name = folder_name or str(meta.get("name", "") or folder_token)
    root_dir = output_base / sanitize_name(resolved_folder_name)
    counters = stats if stats is not None else {"folders": 0, "files": 0, "skipped": 0, "failed": 0, "limit_reached": 0}
    checkpoint = resume_checkpoint or {}
    visited_folder_tokens = [str(token) for token in list_field(checkpoint.get("visited_folder_tokens")) if str(token)]
    visited_set = set(visited_folder_tokens)
    downloaded_items = [item for item in list_field(checkpoint.get("downloaded_items")) if isinstance(item, dict)]
    downloaded_tokens = {str(item.get("token", "")) for item in downloaded_items if str(item.get("token", ""))}

    def remember_checkpoint(current_token: str, path_text: str, page_token: str) -> None:
        write_resume_checkpoint(
            checkpoint_path,
            current_folder_token=current_token,
            current_path=path_text,
            page_token=page_token,
            visited_folder_tokens=visited_folder_tokens,
            downloaded_items=downloaded_items,
        )

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
                if item_token in downloaded_tokens:
                    counters["skipped"] += 1
                    continue
                try:
                    if item_type in EXPORTABLE_TYPES:
                        if file_extension == "md":
                            content, suggested_name, _ = client.download_docx_markdown(item_token)
                        else:
                            content, suggested_name, _ = client.export_docx(item_token, file_extension)
                        target_name = f"{item_name}.{file_extension}" if not suggested_name.lower().endswith(f".{file_extension}") else suggested_name
                        save_bytes(content, current_dir, target_name)
                        counters["files"] += 1
                    elif item_type in DIRECT_DOWNLOAD_TYPES:
                        content, suggested_name, _ = client.download_file(item_token)
                        save_bytes(content, current_dir, suggested_name or item_name)
                        counters["files"] += 1
                    else:
                        counters["skipped"] += 1
                        continue
                    downloaded_tokens.add(item_token)
                    downloaded_items.append({"type": item_type, "token": item_token, "name": item_name, "path": item_path})
                    remember_checkpoint(current_token, path_text, page_token)
                except Exception as exc:
                    counters["failed"] += 1
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
    return {"saved_path": str(root_dir), "folder_name": resolved_folder_name, "stats": counters, "checkpoint": {
        "current_folder_token": start_token,
        "page_token": start_page_token,
        "current_path": start_path,
        "visited_folder_tokens": visited_folder_tokens,
        "downloaded_items": downloaded_items,
    }}


def download_from_url(
    client: FeishuClient,
    resource_url: str,
    output_dir: Path,
    *,
    max_files: int = 0,
    file_extension: str = "docx",
    resume_checkpoint: Optional[Dict[str, Any]] = None,
    checkpoint_path: str = "",
) -> Dict[str, Any]:
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
        )
        return {**parsed, **folder_result, "max_files": max_files}
    if resource_type == "docx":
        if file_extension == "md":
            content, suggested_name, size = client.download_docx_markdown(token)
        else:
            content, suggested_name, size = client.export_docx(token, file_extension)
        target = save_bytes(content, output_dir, suggested_name or f"{token}.{file_extension}")
        return {**parsed, "saved_path": str(target), "file_size": size, "file_extension": file_extension}
    if resource_type == "file":
        content, suggested_name, size = client.download_file(token)
        target = save_bytes(content, output_dir, suggested_name or token)
        return {**parsed, "saved_path": str(target), "file_size": size}
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
    parser.add_argument("--file-extension", default="md", choices=["md", "docx", "pdf"], help="Feishu native export format.")
    parser.add_argument("--max-files", type=int, default=0, help="Folder download limit. Use 0 for the full folder.")
    parser.add_argument("--timeout-seconds", type=int, default=DEFAULT_TIMEOUT_SECONDS, help="HTTP request timeout in seconds.")
    parser.add_argument("--app-id", default="", help="Feishu app id. Prefer env or APPKEY.md when empty.")
    parser.add_argument("--app-secret", default="", help="Feishu app secret. Prefer env or APPKEY.md when empty.")
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
    app_id, app_secret = resolve_credentials(args)
    client = FeishuClient(app_id, app_secret, verbose=not args.quiet, timeout_seconds=args.timeout_seconds)

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
