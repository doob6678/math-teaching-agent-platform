import importlib.util
import tempfile
import unittest
from pathlib import Path
from types import MethodType


SCRIPT_PATH = Path(__file__).resolve().parents[1] / "scripts" / "download_feishu_url.py"
SPEC = importlib.util.spec_from_file_location("download_feishu_url", SCRIPT_PATH)
download_feishu_url = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(download_feishu_url)


MINIMAL_PNG = b"\x89PNG\r\n\x1a\n" + b"png-payload"
MINIMAL_JPEG = b"\xff\xd8\xff" + b"jpeg-payload"
MINIMAL_GIF = b"GIF89a" + b"gif-payload"


class FakeFeishuClient:
    def __init__(self) -> None:
        self.markdown_tokens = []
        self.docx_exports = []

    def get_folder_meta(self, folder_token):
        return {"name": "教材资料"}

    def list_folder_page(self, folder_token, page_token=""):
        if page_token:
            return [], "", False
        return [
            {
                "type": "docx",
                "name": "空间向量",
                "token": "doc_token_1",
            }
        ], "", False

    def download_docx_markdown(self, document_token):
        self.markdown_tokens.append(document_token)
        content = f"# {document_token}\n\n数量积用于判断垂直。".encode("utf-8")
        return content, f"{document_token}.md", len(content)

    def get_docx_sync_metadata(self, _document_token):
        # The Feishu title is the visible source filename; the token remains only the provider identity.
        return {"title": "向量和角度", "revision": "revision-1"}

    def export_docx(self, document_token, file_extension):
        self.docx_exports.append((document_token, file_extension))
        content = b"docx-or-pdf-bytes"
        return content, f"{document_token}.{file_extension}", len(content)

    def download_file(self, file_token):
        content = b"file-bytes"
        return content, f"{file_token}.bin", len(content)

    def log(self, message):
        return None


class FailingFeishuClient(FakeFeishuClient):
    def download_docx_markdown(self, document_token):
        raise RuntimeError("ProxyError: connection reset")


class DownloadedHtmlImageFeishuClient(download_feishu_url.FeishuClient):
    def __init__(self) -> None:
        self.downloaded_urls = []

    def list_document_image_tokens(self, _document_token):
        return []

    def request(self, method, url, **_kwargs):
        class Response:
            content = "TODO 这不是图片，而是文档正文".encode("utf-8")
            headers = {"Content-Type": "image/png"}

        return Response()

    def download_embedded_image(self, image_url):
        self.downloaded_urls.append(image_url)
        return MINIMAL_JPEG, "map.jpg", "image/jpeg"


class MultipleNamedImageFeishuClient(download_feishu_url.FeishuClient):
    def list_document_image_tokens(self, _document_token):
        return []

    def download_embedded_image(self, image_url):
        return MINIMAL_PNG, "image.png", "image/png"


class EmbeddedImageFeishuClient(FakeFeishuClient):
    def download_docx_markdown_with_assets(self, document_token, output_dir):
        content = b"# doc\n\n![image](IMAJES/image.png)"
        asset = {
            "type": "image",
            "token": "media-token",
            "name": "image.png",
            "path": "IMAJES/image.png",
            "relativePath": "IMAJES/image.png",
            "providerAssetId": "IMAJES/image.png",
            "mimeType": "image/png",
            "assetKind": "image",
        }
        target = output_dir / "IMAJES" / "image.png"
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(b"png")
        return content, f"{document_token}.md", len(content), [asset], []


class FailedEmbeddedImageFeishuClient(EmbeddedImageFeishuClient):
    def download_docx_markdown_with_assets(self, document_token, output_dir):
        content, name, size, assets, _ = super().download_docx_markdown_with_assets(document_token, output_dir)
        return content, name, size, assets, [{"type": "image", "token": "media-token", "message": "expired"}]


class ManifestImageFeishuClient(FakeFeishuClient):
    def __init__(self) -> None:
        super().__init__()
        self.downloaded = []

    def download_docx_markdown_with_assets(self, document_token, output_dir):
        self.downloaded.append(document_token)
        content = b"# doc\n\n![image](IMAJES/image-001.png)"
        image_dir = output_dir / "IMAJES"
        image_dir.mkdir(parents=True, exist_ok=True)
        image_path = image_dir / "image-001.png"
        image_path.write_bytes(MINIMAL_PNG)
        asset = {
            "type": "image",
            "token": "media-token",
            "name": image_path.name,
            "path": "IMAJES/image-001.png",
            "relativePath": "IMAJES/image-001.png",
            "providerAssetId": "IMAJES/image-001.png",
            "mimeType": "image/png",
            "assetKind": "image",
        }
        return content, f"{document_token}.md", len(content), [asset], []


class DownloadFeishuUrlTest(unittest.TestCase):
    def test_parser_accepts_markdown_file_extension(self):
        args = download_feishu_url.build_parser().parse_args([
            "--url",
            "https://my.feishu.cn/docx/docToken",
            "--file-extension",
            "md",
        ])

        self.assertEqual(args.file_extension, "md")

    def test_docx_markdown_prefers_docs_ai_fetch_that_preserves_image_href(self):
        client = download_feishu_url.FeishuClient.__new__(download_feishu_url.FeishuClient)
        calls = []

        def api_json(_self, method, url, *, params=None, json_body=None):
            calls.append((method, url, json_body))
            if method == "POST":
                return {"document": {"content": '<p>图示</p><img href="https://stream.test/map.jpg"/>'}}
            raise AssertionError("legacy endpoint should not run after docs_ai content succeeds")

        client.api_json = MethodType(api_json, client)
        content, name, size = client.download_docx_markdown("doc-token")

        self.assertEqual(name, "doc-token.md")
        self.assertEqual(content.decode("utf-8"), '<p>图示</p><img href="https://stream.test/map.jpg"/>')
        self.assertEqual(size, len(content))
        self.assertEqual(calls[0][0], "POST")
        self.assertIn("/docs_ai/v1/documents/doc-token/fetch", calls[0][1])
        self.assertEqual(calls[0][2]["format"], "xml")

    def test_folder_metadata_falls_back_to_drive_files_for_bot_scope(self):
        client = download_feishu_url.FeishuClient.__new__(download_feishu_url.FeishuClient)
        calls = []

        def api_json(_self, method, url, *, params=None, json_body=None):
            calls.append((method, url, params))
            if "/drive/explorer/v2/folder/" in url:
                raise RuntimeError("Feishu HTTP 400: required drive:drive.metadata:readonly")
            self.assertIn("/drive/v1/files", url)
            self.assertEqual(params["folder_token"], "folder-token")
            return {"files": [{"type": "folder", "token": "child-token"}]}

        client.api_json = MethodType(api_json, client)
        metadata = client.get_folder_meta("folder-token")

        self.assertEqual(metadata["name"], "folder-token")
        self.assertTrue(metadata["metadata_fallback"])
        self.assertEqual(len(calls), 2)

    def test_docs_ai_xml_is_normalized_into_parser_lines_without_dropping_images(self):
        normalized = download_feishu_url.normalize_feishu_document_markup(
            '<h2>涂色问题</h2><p>如图：</p><img href="https://stream.test/map.jpg"/>\n'
            '<p><b>先找中心块</b></p><latex>C_4^3A_3^3</latex>'
        )

        self.assertIn("## 涂色问题", normalized)
        self.assertIn("如图：", normalized)
        self.assertIn('<img href="https://stream.test/map.jpg"/>', normalized)
        self.assertIn("先找中心块", normalized)
        self.assertIn("C_4^3A_3^3", normalized)
        self.assertGreaterEqual(normalized.count("\n"), 4)

    def test_docx_url_downloads_markdown_when_requested(self):
        client = FakeFeishuClient()
        with tempfile.TemporaryDirectory() as temp_dir:
            result = download_feishu_url.download_from_url(
                client,
                "https://my.feishu.cn/docx/docToken",
                Path(temp_dir),
                file_extension="md",
            )

            saved_path = Path(result["saved_path"])
            self.assertEqual(client.markdown_tokens, ["docToken"])
            self.assertEqual(client.docx_exports, [])
            self.assertEqual(saved_path.name, "向量和角度.md")
            self.assertEqual(saved_path.suffix, ".md")
            saved_text = (Path(temp_dir) / "向量和角度.md").read_text(encoding="utf-8")
            self.assertIn("数量积用于判断垂直", saved_text)
            self.assertEqual(result["file_extension"], "md")
            self.assertEqual(result["stats"]["files"], 1)
            self.assertEqual(result["stats"]["failed"], 0)
            self.assertEqual(result["failed_items"], [])
            self.assertEqual(result["checkpoint"]["downloaded_items"][0]["token"], "docToken")

    def test_single_document_summary_keeps_materialized_image_manifest(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            result = download_feishu_url.download_from_url(
                EmbeddedImageFeishuClient(),
                "https://my.feishu.cn/docx/docToken",
                Path(temp_dir),
                file_extension="md",
            )

            self.assertEqual(result["stats"]["assets"], 1)
            self.assertEqual(result["stats"]["failed"], 0)
            saved_text = (Path(temp_dir) / "向量和角度.md").read_text(encoding="utf-8")
            self.assertIn("IMAJES/image.png", saved_text)
            self.assertNotIn("向量", saved_text)
            self.assertNotIn("::", saved_text)
            self.assertEqual(
                result["checkpoint"]["downloaded_items"][1]["providerAssetId"],
                "IMAJES/image.png",
            )

    def test_single_document_image_failure_is_reported_and_not_ready(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            result = download_feishu_url.download_from_url(
                FailedEmbeddedImageFeishuClient(),
                "https://my.feishu.cn/docx/docToken",
                Path(temp_dir),
                file_extension="md",
            )

            self.assertEqual(result["stats"]["failed"], 1)
            self.assertEqual(result["failed_items"][0]["token"], "media-token")

    def test_folder_download_uses_selected_export_format_for_docx_items(self):
        client = FakeFeishuClient()
        with tempfile.TemporaryDirectory() as temp_dir:
            result = download_feishu_url.download_from_url(
                client,
                "https://my.feishu.cn/drive/folder/folderToken",
                Path(temp_dir),
                file_extension="md",
            )

            # Folder listing names are the Feishu titles and must remain the persisted Markdown names.
            saved_file = Path(result["saved_path"]) / "空间向量.md"
            self.assertEqual(client.markdown_tokens, ["doc_token_1"])
            self.assertEqual(client.docx_exports, [])
            self.assertTrue(saved_file.exists())
            self.assertEqual(result["stats"]["files"], 1)
            self.assertEqual(result["checkpoint"]["downloaded_items"][0]["token"], "doc_token_1")
            self.assertEqual(result["checkpoint"]["downloaded_items"][0]["relativePath"], "空间向量.md")
            self.assertEqual(result["failed_items"], [])

    def test_folder_download_removes_old_path_when_provider_token_is_renamed(self):
        class RenamedFeishuClient(FakeFeishuClient):
            def list_folder_page(self, folder_token, page_token=""):
                if page_token:
                    return [], "", False
                return [
                    {
                        "type": "docx",
                        "name": "新空间向量",
                        "token": "doc_token_1",
                    }
                ], "", False

        with tempfile.TemporaryDirectory() as temp_dir:
            output_dir = Path(temp_dir)
            root_dir = output_dir / "教材资料"
            old_file = root_dir / "旧目录" / "旧空间向量.md"
            old_file.parent.mkdir(parents=True)
            old_file.write_text("# 旧正文\n", encoding="utf-8")
            old_item = {
                "type": "docx",
                "name": "旧空间向量",
                "token": "doc_token_1",
            }
            manifest_path = output_dir / "manifest.json"
            manifest_path.write_text(
                download_feishu_url.json.dumps({
                    "version": 1,
                    "items": {
                        "doc_token_1": {
                            **old_item,
                            "relativePath": "旧目录/旧空间向量.md",
                            "assetKind": "document",
                            "signature": download_feishu_url.provider_item_signature(old_item),
                        }
                    },
                }, ensure_ascii=False),
                encoding="utf-8",
            )

            result = download_feishu_url.download_from_url(
                RenamedFeishuClient(),
                "https://my.feishu.cn/drive/folder/folderToken",
                output_dir,
                file_extension="md",
                manifest_path=str(manifest_path),
            )

            new_file = root_dir / "新空间向量.md"
            self.assertTrue(new_file.is_file())
            self.assertFalse(old_file.exists())
            self.assertFalse(old_file.parent.exists())
            self.assertEqual(result["changed_items"][0]["relativePath"], "新空间向量.md")
            manifest = download_feishu_url.load_incremental_manifest(str(manifest_path))
            self.assertEqual(manifest["doc_token_1"]["relativePath"], "新空间向量.md")

    def test_folder_resume_keeps_checkpoint_local_root_when_provider_title_differs(self):
        client = FakeFeishuClient()
        with tempfile.TemporaryDirectory() as temp_dir:
            result = download_feishu_url.download_from_url(
                client,
                "https://my.feishu.cn/drive/folder/folderToken",
                Path(temp_dir),
                file_extension="md",
                resume_checkpoint={
                    "current_folder_token": "folderToken",
                    "current_path": "高中数学全局共享资料",
                    "page_token": "",
                    "visited_folder_tokens": [],
                    "downloaded_items": [],
                },
            )

            saved_path = Path(result["saved_path"])
            self.assertEqual(saved_path.name, "高中数学全局共享资料")
            self.assertTrue((saved_path / "空间向量.md").is_file())

    def test_file_url_summary_reports_one_file(self):
        client = FakeFeishuClient()
        with tempfile.TemporaryDirectory() as temp_dir:
            result = download_feishu_url.download_from_url(
                client,
                "https://my.feishu.cn/file/fileToken",
                Path(temp_dir),
            )

            saved_path = Path(result["saved_path"])
            self.assertEqual(saved_path.name, "fileToken.bin")
            self.assertEqual(result["stats"]["files"], 1)
            self.assertEqual(result["checkpoint"]["downloaded_items"][0]["type"], "file")

    def test_incremental_manifest_redownloads_document_with_invalid_local_image(self):
        client = ManifestImageFeishuClient()
        item = {
            "type": "docx",
            "name": "空间向量",
            "token": "doc_token_1",
        }
        with tempfile.TemporaryDirectory() as temp_dir:
            output_dir = Path(temp_dir)
            root_dir = output_dir / "教材资料"
            document = root_dir / "空间向量.md"
            image_dir = root_dir / "IMAJES"
            image_dir.mkdir(parents=True)
            document.write_text("# 旧正文\n", encoding="utf-8")
            (image_dir / "image-001.png").write_text("TODO 这不是图片\n", encoding="utf-8")
            manifest_path = output_dir / "manifest.json"
            manifest_path.write_text(
                download_feishu_url.json.dumps({
                    "version": 1,
                    "items": {
                        "doc_token_1": {
                            "type": "docx",
                            "name": "空间向量",
                            "token": "doc_token_1",
                            "relativePath": "空间向量.md",
                            "assetKind": "document",
                            "signature": download_feishu_url.provider_item_signature(item),
                        }
                    },
                }, ensure_ascii=False),
                encoding="utf-8",
            )

            result = download_feishu_url.download_from_url(
                client,
                "https://my.feishu.cn/drive/folder/folderToken",
                output_dir,
                file_extension="md",
                manifest_path=str(manifest_path),
            )

            self.assertEqual(client.downloaded, ["doc_token_1"])
            self.assertEqual(result["stats"]["changed_files"], 1)
            self.assertEqual(result["unchanged_items"], [])
            self.assertTrue((root_dir / "IMAJES" / "image-001.png").read_bytes().startswith(MINIMAL_PNG[:8]))

    def test_incremental_manifest_keeps_document_with_valid_local_image_unchanged(self):
        client = ManifestImageFeishuClient()
        item = {
            "type": "docx",
            "name": "空间向量",
            "token": "doc_token_1",
        }
        with tempfile.TemporaryDirectory() as temp_dir:
            output_dir = Path(temp_dir)
            root_dir = output_dir / "教材资料"
            document = root_dir / "空间向量.md"
            image_dir = root_dir / "IMAJES"
            image_dir.mkdir(parents=True)
            document.write_text("# 正文\n", encoding="utf-8")
            (image_dir / "image-001.png").write_bytes(MINIMAL_PNG)
            manifest_path = output_dir / "manifest.json"
            manifest_path.write_text(
                download_feishu_url.json.dumps({
                    "version": 1,
                    "items": {
                        "doc_token_1": {
                            "type": "docx",
                            "name": "空间向量",
                            "token": "doc_token_1",
                            "relativePath": "空间向量.md",
                            "assetKind": "document",
                            "signature": download_feishu_url.provider_item_signature(item),
                        }
                    },
                }, ensure_ascii=False),
                encoding="utf-8",
            )

            result = download_feishu_url.download_from_url(
                client,
                "https://my.feishu.cn/drive/folder/folderToken",
                output_dir,
                file_extension="md",
                manifest_path=str(manifest_path),
            )

            self.assertEqual(client.downloaded, [])
            self.assertEqual(result["stats"]["changed_files"], 0)
            self.assertEqual(result["stats"]["unchanged_files"], 1)

    def test_markdown_images_are_materialized_and_manifest_is_provider_neutral(self):
        client = download_feishu_url.FeishuClient.__new__(download_feishu_url.FeishuClient)
        client.list_document_image_tokens = MethodType(lambda _self, _token: [], client)
        client.download_embedded_image = MethodType(
            lambda _self, _url: (b"png-bytes", "diagram.png", "image/png"),
            client,
        )
        with tempfile.TemporaryDirectory() as temp_dir:
            output_dir = Path(temp_dir)
            rewritten, manifests, failures = client.materialize_markdown_images(
                "doc-token",
                "# 涂色问题\n\n![地图](https://internal-api-drive-stream.feishu.cn/expired)",
                output_dir,
            )

            self.assertEqual(failures, [])
            self.assertIn("IMAJES/image-001.png", rewritten)
            self.assertNotIn("internal-api-drive-stream", rewritten)
            self.assertEqual(len(manifests), 1)
            self.assertEqual(manifests[0]["assetKind"], "image")
            self.assertTrue((output_dir / manifests[0]["relativePath"]).exists())

    def test_multiple_images_use_document_order_names(self):
        client = MultipleNamedImageFeishuClient.__new__(MultipleNamedImageFeishuClient)
        with tempfile.TemporaryDirectory() as temp_dir:
            output_dir = Path(temp_dir)
            rewritten, manifests, failures = client.materialize_markdown_images(
                "doc-token",
                "![一](https://img.test/1)\n![二](https://img.test/2)",
                output_dir,
            )
            self.assertEqual(failures, [])
            self.assertEqual(
                [item["relativePath"] for item in manifests],
                [
                    "IMAJES/image-001.png",
                    "IMAJES/image-002.png",
                ],
            )
            self.assertIn("IMAJES/image-001.png", rewritten)
            self.assertIn("IMAJES/image-002.png", rewritten)

    def test_image_names_never_use_provider_or_hash_values(self):
        client = MultipleNamedImageFeishuClient.__new__(MultipleNamedImageFeishuClient)
        with tempfile.TemporaryDirectory() as temp_dir:
            _, manifests, _ = client.materialize_markdown_images(
                "doc-token", "![图](https://img.test/provider-token)", Path(temp_dir)
            )
            self.assertEqual(manifests[0]["name"], "image-001.png")
            self.assertNotRegex(manifests[0]["name"], r"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")

    def test_feishu_html_href_images_are_extracted_in_document_order(self):
        markdown = (
            '<p>前文</p><img data-block="27" href="https://example.test/map?a=1&amp;b=2">'
            '\n![后文](https://example.test/after.png)'
            '\n<img class="duplicate" src="https://example.test/map?a=1&amp;b=2">'
            '\n<img href="" src="https://example.test/fallback.png">'
        )

        references = download_feishu_url.markdown_image_references(markdown)

        self.assertEqual(
            references,
            [
                {"url": "https://example.test/map?a=1&b=2"},
                {"url": "https://example.test/after.png"},
                {"url": "https://example.test/fallback.png"},
            ],
        )

    def test_feishu_html_href_image_is_materialized_without_leaving_provider_url(self):
        client = DownloadedHtmlImageFeishuClient()
        with tempfile.TemporaryDirectory() as temp_dir:
            rewritten, manifests, failures = client.materialize_markdown_images(
                "doc-token",
                '<h2>2013年涂色问题</h2>\n<img href="https://internal-api-drive-stream.feishu.cn/stream?x=1&amp;sig=2">',
                Path(temp_dir),
            )

            self.assertEqual(failures, [])
            self.assertEqual(client.downloaded_urls, ["https://internal-api-drive-stream.feishu.cn/stream?x=1&sig=2"])
            self.assertIn('IMAJES/image-001.jpg', rewritten)
            self.assertNotIn('internal-api-drive-stream', rewritten)
            self.assertEqual(len(manifests), 1)
            self.assertTrue((Path(temp_dir) / manifests[0]["relativePath"]).is_file())

    def test_feishu_html_image_is_rewritten_to_standard_markdown_image(self):
        """HTML image nodes must not become literal text in Markdown-only previewers."""
        client = DownloadedHtmlImageFeishuClient()
        with tempfile.TemporaryDirectory() as temp_dir:
            rewritten, manifests, failures = client.materialize_markdown_images(
                "doc-token",
                '<img name="image.png" href="https://internal-api-drive-stream.feishu.cn/stream?x=1&amp;sig=2"/>',
                Path(temp_dir),
            )

            self.assertEqual(failures, [])
            self.assertEqual(len(manifests), 1)
            self.assertEqual(rewritten, '![](IMAJES/image-001.jpg)')

    def test_expired_markdown_image_falls_back_to_durable_media_token(self):
        client = download_feishu_url.FeishuClient.__new__(download_feishu_url.FeishuClient)
        client.list_document_image_tokens = MethodType(lambda _self, _token: ["media-token-1"], client)
        client.download_embedded_image = MethodType(
            lambda _self, _url: (_ for _ in ()).throw(RuntimeError("signed URL expired")),
            client,
        )
        client.download_media = MethodType(
            lambda _self, _token: (b"media-bytes", "media.png", "image/png"),
            client,
        )
        with tempfile.TemporaryDirectory() as temp_dir:
            rewritten, manifests, failures = client.materialize_markdown_images(
                "doc-token",
                "![地图](https://internal-api-drive-stream.feishu.cn/expired)",
                Path(temp_dir),
            )

            self.assertEqual(failures, [])
            self.assertIn("IMAJES/image-001.png", rewritten)
            self.assertEqual(manifests[0]["token"], "media-token-1")

    def test_invalid_successful_image_payload_is_rejected(self):
        with self.assertRaises(ValueError):
            download_feishu_url.validate_image_payload(
                "TODO 这不是图片，而是文档正文".encode("utf-8"),
                "image-001.png",
                "image/png",
            )

    def test_failed_markdown_image_is_not_hidden_from_sync_summary(self):
        client = download_feishu_url.FeishuClient.__new__(download_feishu_url.FeishuClient)
        client.list_document_image_tokens = MethodType(lambda _self, _token: [], client)
        client.download_embedded_image = MethodType(
            lambda _self, _url: (_ for _ in ()).throw(RuntimeError("provider returned 404")),
            client,
        )
        with tempfile.TemporaryDirectory() as temp_dir:
            rewritten, manifests, failures = client.materialize_markdown_images(
                "doc-token",
                "![地图](https://internal-api-drive-stream.feishu.cn/expired)",
                Path(temp_dir),
            )

            self.assertEqual(manifests, [])
            self.assertEqual(len(failures), 1)
            self.assertIn("provider returned 404", failures[0]["message"])
            self.assertIn("internal-api-drive-stream", rewritten)


if __name__ == "__main__":
    unittest.main()
