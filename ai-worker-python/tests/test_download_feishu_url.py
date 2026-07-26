import importlib.util
import tempfile
import unittest
from pathlib import Path
from types import MethodType


SCRIPT_PATH = Path(__file__).resolve().parents[1] / "scripts" / "download_feishu_url.py"
SPEC = importlib.util.spec_from_file_location("download_feishu_url", SCRIPT_PATH)
download_feishu_url = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(download_feishu_url)


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

    def download_embedded_image(self, image_url):
        self.downloaded_urls.append(image_url)
        return b"html-image-bytes", "map.jpg", "image/jpeg"


class MultipleNamedImageFeishuClient(download_feishu_url.FeishuClient):
    def list_document_image_tokens(self, _document_token):
        return []

    def download_embedded_image(self, image_url):
        return str(image_url).encode("utf-8"), "image.png", "image/png"


class EmbeddedImageFeishuClient(FakeFeishuClient):
    def download_docx_markdown_with_assets(self, document_token, output_dir):
        content = b"# doc\n\n![image](_feishu_images/image.png)"
        asset = {
            "type": "image",
            "token": "media-token",
            "name": "image.png",
            "path": "_feishu_images/image.png",
            "relativePath": "_feishu_images/image.png",
            "providerAssetId": "_feishu_images/image.png",
            "mimeType": "image/png",
            "assetKind": "image",
        }
        target = output_dir / "_feishu_images" / "image.png"
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(b"png")
        return content, f"{document_token}.md", len(content), [asset], []


class FailedEmbeddedImageFeishuClient(EmbeddedImageFeishuClient):
    def download_docx_markdown_with_assets(self, document_token, output_dir):
        content, name, size, assets, _ = super().download_docx_markdown_with_assets(document_token, output_dir)
        return content, name, size, assets, [{"type": "image", "token": "media-token", "message": "expired"}]


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
            self.assertIn("数量积用于判断垂直", saved_path.read_text(encoding="utf-8"))
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
            self.assertEqual(result["checkpoint"]["downloaded_items"][1]["providerAssetId"], "_feishu_images/image.png")

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

    def test_folder_download_records_failed_items(self):
        client = FailingFeishuClient()
        with tempfile.TemporaryDirectory() as temp_dir:
            result = download_feishu_url.download_from_url(
                client,
                "https://my.feishu.cn/drive/folder/folderToken",
                Path(temp_dir),
                file_extension="md",
            )

            self.assertEqual(result["stats"]["files"], 0)
            self.assertEqual(result["stats"]["failed"], 1)
            self.assertEqual(result["failed_items"][0]["token"], "doc_token_1")
            self.assertIn("ProxyError", result["failed_items"][0]["message"])

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
            self.assertIn("_feishu_images/diagram.png", rewritten)
            self.assertNotIn("internal-api-drive-stream", rewritten)
            self.assertEqual(len(manifests), 1)
            self.assertEqual(manifests[0]["assetKind"], "image")
            self.assertTrue((output_dir / manifests[0]["relativePath"]).exists())

    def test_multiple_images_do_not_overwrite_same_provider_name(self):
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
                ["_feishu_images/image.png", "_feishu_images/image-2.png"],
            )
            self.assertIn("_feishu_images/image.png", rewritten)
            self.assertIn("_feishu_images/image-2.png", rewritten)

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
            self.assertIn('_feishu_images/map.jpg', rewritten)
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
            self.assertEqual(rewritten, '![](_feishu_images/map.jpg)')

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
            self.assertIn("_feishu_images/media.png", rewritten)
            self.assertEqual(manifests[0]["token"], "media-token-1")

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
