import importlib.util
import tempfile
import unittest
from pathlib import Path


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


class DownloadFeishuUrlTest(unittest.TestCase):
    def test_parser_accepts_markdown_file_extension(self):
        args = download_feishu_url.build_parser().parse_args([
            "--url",
            "https://my.feishu.cn/docx/docToken",
            "--file-extension",
            "md",
        ])

        self.assertEqual(args.file_extension, "md")

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
            self.assertEqual(saved_path.suffix, ".md")
            self.assertIn("数量积用于判断垂直", saved_path.read_text(encoding="utf-8"))
            self.assertEqual(result["file_extension"], "md")
            self.assertEqual(result["stats"]["files"], 1)
            self.assertEqual(result["stats"]["failed"], 0)
            self.assertEqual(result["failed_items"], [])
            self.assertEqual(result["checkpoint"]["downloaded_items"][0]["token"], "docToken")

    def test_folder_download_uses_selected_export_format_for_docx_items(self):
        client = FakeFeishuClient()
        with tempfile.TemporaryDirectory() as temp_dir:
            result = download_feishu_url.download_from_url(
                client,
                "https://my.feishu.cn/drive/folder/folderToken",
                Path(temp_dir),
                file_extension="md",
            )

            saved_file = Path(result["saved_path"]) / "doc_token_1.md"
            self.assertEqual(client.markdown_tokens, ["doc_token_1"])
            self.assertEqual(client.docx_exports, [])
            self.assertTrue(saved_file.exists())
            self.assertEqual(result["stats"]["files"], 1)
            self.assertEqual(result["checkpoint"]["downloaded_items"][0]["token"], "doc_token_1")
            self.assertEqual(result["failed_items"], [])

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


if __name__ == "__main__":
    unittest.main()
