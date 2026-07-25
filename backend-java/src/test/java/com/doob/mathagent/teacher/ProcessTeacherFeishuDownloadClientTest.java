package com.doob.mathagent.teacher;

import static org.assertj.core.api.Assertions.assertThat;

import com.doob.mathagent.teacher.feishu.ProcessTeacherFeishuDownloadClient;
import com.doob.mathagent.teacher.feishu.TeacherFeishuDownloadClient;
import com.doob.mathagent.teacher.feishu.TeacherFeishuDownloadException;
import com.doob.mathagent.teacher.sync.TeacherSourceSyncProperties;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProcessTeacherFeishuDownloadClientTest {

    @TempDir
    Path tempDir;

    @Test
    void processDownloaderPassesSelectedFileExtensionToPythonScript() throws Exception {
        Path script = tempDir.resolve("format_downloader.py");
        Files.writeString(script, """
                import json
                import pathlib
                import sys

                args = sys.argv[1:]
                if "--file-extension" not in args:
                    print("missing --file-extension")
                    sys.exit(10)
                if args[args.index("--file-extension") + 1] != "md":
                    print("wrong file extension: " + args[args.index("--file-extension") + 1])
                    sys.exit(11)

                output_dir = pathlib.Path(args[args.index("--output-dir") + 1])
                saved_path = output_dir / "downloaded"
                saved_path.mkdir(parents=True, exist_ok=True)
                (saved_path / "result.md").write_text("# downloaded", encoding="utf-8")
                summary_path = pathlib.Path(args[args.index("--summary-path") + 1])
                summary_path.write_text(json.dumps({
                    "saved_path": str(saved_path),
                    "files": 1,
                    "skipped": 0,
                    "failed": 0,
                    "file_extension": "md"
                }, ensure_ascii=False), encoding="utf-8")
                """);
        Path appkey = tempDir.resolve("APPKEY.md");
        Files.writeString(appkey, "APPID\ncli_dummy\nAPP Secret\nsecret_dummy\n");
        TeacherSourceSyncProperties properties = new TeacherSourceSyncProperties(
                "https://my.feishu.cn/docx/docToken",
                script,
                appkey,
                tempDir.resolve("staging"),
                1);
        ProcessTeacherFeishuDownloadClient client = new ProcessTeacherFeishuDownloadClient(properties);

        TeacherFeishuDownloadClient.FeishuDownloadResult result = client.download(
                "https://my.feishu.cn/docx/docToken",
                tempDir.resolve("staging"),
                1,
                "md",
                TeacherFeishuDownloadClient.FeishuDownloadCheckpoint.empty());

        assertThat(result.files()).isEqualTo(1);
        assertThat(Files.exists(result.savedPath().resolve("result.md"))).isTrue();
    }

    @Test
    void processDownloaderParsesNestedSummaryStatsAndCheckpointFromRealScriptShape() throws Exception {
        Path script = tempDir.resolve("nested_summary_downloader.py");
        Files.writeString(script, """
                import json
                import pathlib
                import sys

                args = sys.argv[1:]
                output_dir = pathlib.Path(args[args.index("--output-dir") + 1])
                saved_path = output_dir / "downloaded"
                saved_path.mkdir(parents=True, exist_ok=True)
                (saved_path / "result.md").write_text("# downloaded", encoding="utf-8")
                summary_path = pathlib.Path(args[args.index("--summary-path") + 1])
                summary_path.write_text(json.dumps({
                    "resource_type": "folder",
                    "saved_path": str(saved_path),
                    "stats": {"folders": 3, "files": 1, "skipped": 2, "failed": 0, "limit_reached": 1},
                    "checkpoint": {
                        "current_folder_token": "rootToken",
                        "page_token": "",
                        "current_path": "高中数学",
                        "visited_folder_tokens": ["rootToken", "folderToken-1"],
                        "downloaded_items": [{
                            "type": "docx",
                            "token": "docx-1",
                            "name": "期望和方差的性质",
                            "path": "高中数学/概率统计/期望和方差的性质"
                        }]
                    }
                }, ensure_ascii=False), encoding="utf-8")
                """);
        Path appkey = tempDir.resolve("APPKEY.md");
        Files.writeString(appkey, "APPID\ncli_dummy\nAPP Secret\nsecret_dummy\n");
        TeacherSourceSyncProperties properties = new TeacherSourceSyncProperties(
                "https://my.feishu.cn/drive/folder/rootToken",
                script,
                appkey,
                tempDir.resolve("staging"),
                1);
        ProcessTeacherFeishuDownloadClient client = new ProcessTeacherFeishuDownloadClient(properties);

        TeacherFeishuDownloadClient.FeishuDownloadResult result = client.download(
                "https://my.feishu.cn/drive/folder/rootToken",
                tempDir.resolve("staging"),
                1,
                "md",
                TeacherFeishuDownloadClient.FeishuDownloadCheckpoint.empty());

        assertThat(result.files()).isEqualTo(1);
        assertThat(result.skipped()).isEqualTo(2);
        assertThat(result.checkpoint().currentFolderToken()).isEqualTo("rootToken");
        assertThat(result.checkpoint().currentPath()).isEqualTo("高中数学");
        assertThat(result.downloadedItemsJson()).contains("docx-1").contains("期望和方差的性质");
    }

    @Test
    void processDownloaderTreatsNestedSummaryFailuresAsDownloadFailures() throws Exception {
        Path script = tempDir.resolve("nested_summary_failure.py");
        Files.writeString(script, """
                import json
                import pathlib
                import sys

                args = sys.argv[1:]
                output_dir = pathlib.Path(args[args.index("--output-dir") + 1])
                output_dir.mkdir(parents=True, exist_ok=True)
                summary_path = pathlib.Path(args[args.index("--summary-path") + 1])
                summary_path.write_text(json.dumps({
                    "saved_path": str(output_dir / "downloaded"),
                    "stats": {"folders": 1, "files": 0, "skipped": 0, "failed": 1},
                    "checkpoint": {
                        "current_folder_token": "folderToken-failed",
                        "page_token": "pageToken-failed",
                        "current_path": "高中数学/导数",
                        "visited_folder_tokens": ["rootToken", "folderToken-failed"],
                        "downloaded_items": [{"token": "docx-ok"}]
                    }
                }, ensure_ascii=False), encoding="utf-8")
                """);
        Path appkey = tempDir.resolve("APPKEY.md");
        Files.writeString(appkey, "APPID\ncli_dummy\nAPP Secret\nsecret_dummy\n");
        TeacherSourceSyncProperties properties = new TeacherSourceSyncProperties(
                "https://my.feishu.cn/drive/folder/rootToken",
                script,
                appkey,
                tempDir.resolve("staging"),
                1);
        ProcessTeacherFeishuDownloadClient client = new ProcessTeacherFeishuDownloadClient(properties);

        assertThatThrownBy(() -> client.download(
                "https://my.feishu.cn/drive/folder/rootToken",
                tempDir.resolve("staging"),
                1,
                "md",
                TeacherFeishuDownloadClient.FeishuDownloadCheckpoint.empty()))
                // The process adapter intentionally exposes the structured provider exception so the job can retain
                // retryability and the last durable folder checkpoint instead of discarding that recovery context.
                .isInstanceOf(TeacherFeishuDownloadException.class)
                .hasMessageContaining("reported failed files: 1");
    }

    @Test
    void processDownloaderPassesResumeCheckpointArgumentsToPythonScript() throws Exception {
        Path script = tempDir.resolve("checkpoint_downloader.py");
        Files.writeString(script, """
                import json
                import pathlib
                import sys

                args = sys.argv[1:]
                if "--resume-checkpoint-path" not in args:
                    print("missing --resume-checkpoint-path")
                    sys.exit(10)
                checkpoint_path = pathlib.Path(args[args.index("--resume-checkpoint-path") + 1])
                checkpoint = json.loads(checkpoint_path.read_text(encoding="utf-8"))
                expected = {
                    "current_folder_token": "folderToken-2",
                    "page_token": "pageToken-3",
                    "current_path": "高中数学/空间向量",
                    "visited_folder_tokens": ["rootToken", "folderToken-2"],
                    "downloaded_items": [{"token": "docx-1"}],
                }
                if checkpoint != expected:
                    print(json.dumps(checkpoint, ensure_ascii=False))
                    sys.exit(11)

                output_dir = pathlib.Path(args[args.index("--output-dir") + 1])
                saved_path = output_dir / "downloaded"
                saved_path.mkdir(parents=True, exist_ok=True)
                (saved_path / "result.txt").write_text("downloaded", encoding="utf-8")
                summary_path = pathlib.Path(args[args.index("--summary-path") + 1])
                summary_path.write_text(json.dumps({
                    "saved_path": str(saved_path),
                    "files": 1,
                    "skipped": 0,
                    "failed": 0
                }, ensure_ascii=False), encoding="utf-8")
                """);
        Path appkey = tempDir.resolve("APPKEY.md");
        Files.writeString(appkey, "APPID\ncli_dummy\nAPP Secret\nsecret_dummy\n");
        TeacherSourceSyncProperties properties = new TeacherSourceSyncProperties(
                "https://my.feishu.cn/drive/folder/rootToken",
                script,
                appkey,
                tempDir.resolve("staging"),
                1);
        ProcessTeacherFeishuDownloadClient client = new ProcessTeacherFeishuDownloadClient(properties);

        TeacherFeishuDownloadClient.FeishuDownloadResult result = client.download(
                "https://my.feishu.cn/drive/folder/rootToken",
                tempDir.resolve("staging"),
                1,
                new TeacherFeishuDownloadClient.FeishuDownloadCheckpoint(
                        "folderToken-2",
                        "高中数学/空间向量",
                        "pageToken-3",
                        "[\"rootToken\",\"folderToken-2\"]",
                        "[{\"token\":\"docx-1\"}]"));

        assertThat(result.files()).isEqualTo(1);
        assertThat(Files.exists(result.savedPath().resolve("result.txt"))).isTrue();
    }

    @Test
    void processDownloaderReturnsUpdatedCheckpointOnRetryableFailure() throws Exception {
        Path script = tempDir.resolve("checkpoint_failure.py");
        Files.writeString(script, """
                import json
                import pathlib
                import sys

                args = sys.argv[1:]
                checkpoint_path = pathlib.Path(args[args.index("--resume-checkpoint-path") + 1])
                checkpoint_path.write_text(json.dumps({
                    "current_folder_token": "folderToken-9",
                    "page_token": "pageToken-10",
                    "current_path": "高中数学/导数",
                    "visited_folder_tokens": ["rootToken", "folderToken-9"],
                    "downloaded_items": [{"token": "docx-9"}],
                }, ensure_ascii=False), encoding="utf-8")
                print("ProxyError: tunnel connection reset")
                sys.exit(7)
                """);
        Path appkey = tempDir.resolve("APPKEY.md");
        Files.writeString(appkey, "APPID\ncli_dummy\nAPP Secret\nsecret_dummy\n");
        TeacherSourceSyncProperties properties = new TeacherSourceSyncProperties(
                "https://my.feishu.cn/drive/folder/rootToken",
                script,
                appkey,
                tempDir.resolve("staging"),
                1);
        ProcessTeacherFeishuDownloadClient client = new ProcessTeacherFeishuDownloadClient(properties);

        assertThatThrownBy(() -> client.download(
                "https://my.feishu.cn/drive/folder/rootToken",
                tempDir.resolve("staging"),
                1,
                new TeacherFeishuDownloadClient.FeishuDownloadCheckpoint(
                        "folderToken-2",
                        "高中数学/空间向量",
                        "pageToken-3",
                        "[\"rootToken\",\"folderToken-2\"]",
                        "[{\"token\":\"docx-1\"}]")))
                .isInstanceOf(TeacherFeishuDownloadException.class)
                .satisfies(error -> {
                    TeacherFeishuDownloadException exception = (TeacherFeishuDownloadException) error;
                    assertThat(exception.retryable()).isTrue();
                    assertThat(exception.checkpoint().currentFolderToken()).isEqualTo("folderToken-9");
                    assertThat(exception.checkpoint().pageToken()).isEqualTo("pageToken-10");
                    assertThat(exception.checkpoint().downloadedItemsJson()).contains("docx-9");
                });
    }

    @Test
    void processDownloaderCarriesUpdatedCheckpointBetweenRetryAttempts() throws Exception {
        Path script = tempDir.resolve("checkpoint_retry.py");
        Files.writeString(script, """
                import json
                import pathlib
                import sys

                args = sys.argv[1:]
                output_dir = pathlib.Path(args[args.index("--output-dir") + 1])
                output_dir.mkdir(parents=True, exist_ok=True)
                marker = output_dir / "attempt.txt"
                attempt = int(marker.read_text(encoding="utf-8")) + 1 if marker.exists() else 1
                marker.write_text(str(attempt), encoding="utf-8")
                checkpoint_path = pathlib.Path(args[args.index("--resume-checkpoint-path") + 1])
                checkpoint = json.loads(checkpoint_path.read_text(encoding="utf-8"))
                if attempt == 1:
                    checkpoint_path.write_text(json.dumps({
                        "current_folder_token": "folderToken-retry",
                        "page_token": "pageToken-retry",
                        "current_path": "高中数学/重试",
                        "visited_folder_tokens": ["rootToken", "folderToken-retry"],
                        "downloaded_items": [{"token": "docx-retry"}],
                    }, ensure_ascii=False), encoding="utf-8")
                    print("ProxyError: first attempt failed after checkpoint")
                    sys.exit(8)
                if checkpoint.get("current_folder_token") != "folderToken-retry":
                    print(json.dumps(checkpoint, ensure_ascii=False))
                    sys.exit(12)
                saved_path = output_dir / "downloaded"
                saved_path.mkdir(parents=True, exist_ok=True)
                (saved_path / "result.txt").write_text("downloaded after retry", encoding="utf-8")
                summary_path = pathlib.Path(args[args.index("--summary-path") + 1])
                summary_path.write_text(json.dumps({
                    "saved_path": str(saved_path),
                    "files": 1,
                    "skipped": 0,
                    "failed": 0
                }, ensure_ascii=False), encoding="utf-8")
                """);
        Path appkey = tempDir.resolve("APPKEY.md");
        Files.writeString(appkey, "APPID\ncli_dummy\nAPP Secret\nsecret_dummy\n");
        TeacherSourceSyncProperties properties = new TeacherSourceSyncProperties(
                "https://my.feishu.cn/drive/folder/rootToken",
                script,
                appkey,
                tempDir.resolve("staging"),
                1);
        ProcessTeacherFeishuDownloadClient client = new ProcessTeacherFeishuDownloadClient(properties);

        TeacherFeishuDownloadClient.FeishuDownloadResult result = client.download(
                "https://my.feishu.cn/drive/folder/rootToken",
                tempDir.resolve("staging"),
                1,
                new TeacherFeishuDownloadClient.FeishuDownloadCheckpoint(
                        "rootToken",
                        "高中数学",
                        "",
                        "[\"rootToken\"]",
                        "[]"));

        assertThat(result.files()).isEqualTo(1);
        assertThat(Files.readString(result.savedPath().resolve("result.txt"))).contains("after retry");
    }

    @Test
    void processDownloaderMarksTimedOutProcessAsRetryable() throws Exception {
        Path script = tempDir.resolve("timeout_downloader.py");
        Files.writeString(script, """
                import time
                print("starting slow download", flush=True)
                time.sleep(10)
                """);
        Path appkey = tempDir.resolve("APPKEY.md");
        Files.writeString(appkey, "APPID\ncli_dummy\nAPP Secret\nsecret_dummy\n");
        TeacherSourceSyncProperties properties = new TeacherSourceSyncProperties(
                "https://my.feishu.cn/drive/folder/rootToken",
                script,
                appkey,
                tempDir.resolve("staging"),
                1,
                1);
        ProcessTeacherFeishuDownloadClient client = new ProcessTeacherFeishuDownloadClient(properties);

        assertThatThrownBy(() -> client.download(
                "https://my.feishu.cn/drive/folder/rootToken",
                tempDir.resolve("staging"),
                1,
                TeacherFeishuDownloadClient.FeishuDownloadCheckpoint.empty()))
                .isInstanceOf(TeacherFeishuDownloadException.class)
                .satisfies(error -> assertThat(((TeacherFeishuDownloadException) error).retryable()).isTrue())
                .hasMessageContaining("timed out after 1 seconds");
    }

    @Test
    void processDownloaderMarksFeishuRateLimitAsRetryableAndKeepsCheckpoint() throws Exception {
        Path script = tempDir.resolve("rate_limited_downloader.py");
        Files.writeString(script, """
                import json
                import pathlib
                import sys

                args = sys.argv[1:]
                checkpoint_path = pathlib.Path(args[args.index("--resume-checkpoint-path") + 1])
                checkpoint_path.write_text(json.dumps({
                    "current_folder_token": "folder-rate-limit",
                    "page_token": "page-rate-limit",
                    "current_path": "高中数学/限流恢复",
                    "visited_folder_tokens": ["rootToken", "folder-rate-limit"],
                    "downloaded_items": [{"token": "docx-complete"}]
                }, ensure_ascii=False), encoding="utf-8")
                print("HTTP 429 Too Many Requests: rate limit exceeded")
                sys.exit(29)
                """);
        Path appkey = tempDir.resolve("APPKEY.md");
        Files.writeString(appkey, "APPID\ncli_dummy\nAPP Secret\nsecret_dummy\n");
        TeacherSourceSyncProperties properties = new TeacherSourceSyncProperties(
                "https://my.feishu.cn/drive/folder/rootToken",
                script,
                appkey,
                tempDir.resolve("staging"),
                1);
        ProcessTeacherFeishuDownloadClient client = new ProcessTeacherFeishuDownloadClient(properties);

        assertThatThrownBy(() -> client.download(
                "https://my.feishu.cn/drive/folder/rootToken",
                tempDir.resolve("staging"),
                1,
                new TeacherFeishuDownloadClient.FeishuDownloadCheckpoint(
                        "rootToken", "高中数学", "", "[\"rootToken\"]", "[]")))
                .isInstanceOf(TeacherFeishuDownloadException.class)
                .satisfies(error -> {
                    TeacherFeishuDownloadException exception = (TeacherFeishuDownloadException) error;
                    assertThat(exception.retryable()).isTrue();
                    assertThat(exception.checkpoint().currentFolderToken()).isEqualTo("folder-rate-limit");
                    assertThat(exception.checkpoint().downloadedItemsJson()).contains("docx-complete");
                });
    }
}
