import importlib.util
from pathlib import Path


SCRIPT_PATH = Path(__file__).resolve().parents[1] / "scripts" / "download_feishu_url.py"
SPEC = importlib.util.spec_from_file_location("download_feishu_url_user_token", SCRIPT_PATH)
download_feishu_url = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(download_feishu_url)


def test_user_access_token_is_preferred_without_app_secret():
    args = download_feishu_url.build_parser().parse_args(["--access-token", "user-token"])
    assert download_feishu_url.resolve_credentials(args) == ("", "", "user-token")
