package com.doob.mathagent.teacher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.doob.mathagent.teacher.service.ProcessTeacherFeishuDiscoveryClient;
import com.doob.mathagent.teacher.service.TeacherFeishuDiscoveryException;
import com.doob.mathagent.teacher.service.TeacherFeishuDiscoveryQuery;
import com.doob.mathagent.teacher.service.TeacherSourceSyncProperties;
import com.doob.mathagent.teacher.vo.TeacherFeishuDiscoveryResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.io.TempDir;

class ProcessTeacherFeishuDiscoveryClientTest {

    @TempDir
    Path tempDir;

    @Test
    void parsesDiscoveryCandidatesFromProcessSummary() throws Exception {
        Path script = tempDir.resolve("feishu_discovery_fixture.py");
        Path appkey = tempDir.resolve("APPKEY.md");
        Files.writeString(appkey, "APP_ID=dummy\nAPP_SECRET=dummy\n");
        Files.writeString(script, """
                import json
                import sys
                from pathlib import Path

                summary_path = Path(sys.argv[sys.argv.index("--summary-path") + 1])
                result = {
                    "mode": "search_root",
                    "keyword": "空间向量",
                    "root": {"url": "https://my.feishu.cn/drive/folder/root-token"},
                    "count": 1,
                    "candidates": [{
                        "resource_type": "docx",
                        "token": "doc-token",
                        "name": "空间向量数量积",
                        "path": "必修二/空间向量数量积",
                        "url": "https://my.feishu.cn/docx/doc-token",
                        "depth": 2,
                        "downloadable": True
                    }]
                }
                summary_path.parent.mkdir(parents=True, exist_ok=True)
                summary_path.write_text(json.dumps(result, ensure_ascii=False), encoding="utf-8")
                print("FEISHU_INSPECT_SUMMARY=" + json.dumps(result, ensure_ascii=False))
                """);
        TeacherSourceSyncProperties properties = new TeacherSourceSyncProperties(
                "https://my.feishu.cn/drive/folder/root-token",
                script,
                appkey,
                tempDir.resolve("staging"),
                1);
        ProcessTeacherFeishuDiscoveryClient client = new ProcessTeacherFeishuDiscoveryClient(properties);

        TeacherFeishuDiscoveryResponse response = client.discover(new TeacherFeishuDiscoveryQuery(
                "search",
                "空间向量",
                "https://my.feishu.cn/drive/folder/root-token",
                1,
                5));

        assertThat(response.mode()).isEqualTo("search_root");
        assertThat(response.keyword()).isEqualTo("空间向量");
        assertThat(response.candidateCount()).isEqualTo(1);
        assertThat(response.candidates().getFirst().name()).isEqualTo("空间向量数量积");
        assertThat(response.candidates().getFirst().downloadable()).isTrue();
    }

    @Test
    void marksProxyFailureAsRetryableAfterProcessRetries() throws Exception {
        Path script = tempDir.resolve("feishu_discovery_proxy_failure.py");
        Path appkey = tempDir.resolve("APPKEY.md");
        Files.writeString(appkey, "APP_ID=dummy\nAPP_SECRET=dummy\n");
        Files.writeString(script, """
                import sys
                print("ProxyError: proxy connection reset")
                sys.exit(2)
                """);
        TeacherSourceSyncProperties properties = new TeacherSourceSyncProperties(
                "https://my.feishu.cn/drive/folder/root-token",
                script,
                appkey,
                tempDir.resolve("staging"),
                1);
        ProcessTeacherFeishuDiscoveryClient client = new ProcessTeacherFeishuDiscoveryClient(properties);

        assertThatThrownBy(() -> client.discover(new TeacherFeishuDiscoveryQuery(
                        "list",
                        "",
                        "https://my.feishu.cn/drive/folder/root-token",
                        1,
                        1)))
                .isInstanceOf(TeacherFeishuDiscoveryException.class)
                .satisfies(exception -> assertThat(((TeacherFeishuDiscoveryException) exception).retryable()).isTrue())
                .hasMessageContaining("ProxyError");
    }

    @Test
    void realFeishuDiscoveryListsAndSearchesThroughVerifiedScript() {
        Path script = Path.of(System.getProperty("user.home"), ".codex", "skills", "feishu-cloud-docs", "scripts",
                "download_feishu_url.py");
        Path appkey = Path.of("D:/project2026/feishutest/APPKEY.md");
        Assumptions.assumeTrue(Files.isRegularFile(script), "Feishu discovery script is not available locally");
        Assumptions.assumeTrue(Files.isRegularFile(appkey), "Feishu APPKEY path is not available locally");
        TeacherSourceSyncProperties properties = new TeacherSourceSyncProperties(
                "https://my.feishu.cn/drive/folder/XVn7fXppJlQMK5dkuOkc1ePan2f",
                script,
                appkey,
                tempDir.resolve("real-feishu-discovery"),
                1);
        ProcessTeacherFeishuDiscoveryClient client = new ProcessTeacherFeishuDiscoveryClient(properties);

        TeacherFeishuDiscoveryResponse listed = client.discover(new TeacherFeishuDiscoveryQuery(
                "list",
                "",
                "https://my.feishu.cn/drive/folder/XVn7fXppJlQMK5dkuOkc1ePan2f",
                1,
                5));
        TeacherFeishuDiscoveryResponse searched = client.discover(new TeacherFeishuDiscoveryQuery(
                "search",
                "空间向量",
                "https://my.feishu.cn/drive/folder/XVn7fXppJlQMK5dkuOkc1ePan2f",
                1,
                5));

        assertThat(listed.candidateCount()).isGreaterThan(0);
        assertThat(searched.candidateCount()).isGreaterThan(0);
        assertThat(searched.candidates()).anySatisfy(candidate ->
                assertThat(candidate.url()).startsWith("https://my.feishu.cn/"));
    }
}
