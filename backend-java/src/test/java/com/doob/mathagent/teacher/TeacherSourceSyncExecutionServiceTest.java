package com.doob.mathagent.teacher;

import static org.assertj.core.api.Assertions.assertThat;

import com.doob.mathagent.teacher.service.InMemoryTeacherDocumentBlockStore;
import com.doob.mathagent.teacher.service.InMemoryTeacherResourceStore;
import com.doob.mathagent.teacher.service.InMemoryTeacherSourceSyncJobStore;
import com.doob.mathagent.teacher.service.ProcessTeacherFeishuDownloadClient;
import com.doob.mathagent.teacher.service.TeacherResourceRegistrationCommand;
import com.doob.mathagent.teacher.service.TeacherResourceService;
import com.doob.mathagent.teacher.service.TeacherSourceSyncExecutionService;
import com.doob.mathagent.teacher.service.TeacherSourceSyncJobService;
import com.doob.mathagent.teacher.service.TeacherSourceSyncProperties;
import com.doob.mathagent.teacher.service.UnconfiguredTeacherFeishuDownloadClient;
import com.doob.mathagent.teacher.vo.TeacherDocumentBlockResponse;
import com.doob.mathagent.teacher.vo.TeacherResourceDocumentResponse;
import com.doob.mathagent.teacher.vo.TeacherSourceSyncJobResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TeacherSourceSyncExecutionServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void localPathSyncJobParsesMarkdownAndTextFilesIntoDocumentBlocks() throws Exception {
        Path bank = tempDir.resolve("teacher-bank");
        Files.createDirectories(bank.resolve("vector"));
        Files.writeString(bank.resolve("vector").resolve("space-vector.md"), """
                # 空间向量

                向量加法可以用平行四边形法则解释。

                ## 数量积
                数量积用于判断垂直和计算夹角。
                """);
        Files.writeString(bank.resolve("exercise.txt"), "例题：已知 a 垂直 b，求 a·b。");
        InMemoryTeacherResourceStore resourceStore = new InMemoryTeacherResourceStore();
        InMemoryTeacherSourceSyncJobStore jobStore = new InMemoryTeacherSourceSyncJobStore();
        InMemoryTeacherDocumentBlockStore blockStore = new InMemoryTeacherDocumentBlockStore();
        TeacherResourceService resourceService = new TeacherResourceService(resourceStore);
        TeacherResourceDocumentResponse resource = resourceService.register(new TeacherResourceRegistrationCommand(
                "school-a",
                "teacher",
                "teacher-1",
                "local_path",
                "Local vector bank",
                null,
                bank.toString(),
                "TEACHER_PRIVATE"));
        TeacherSourceSyncJobService jobService = new TeacherSourceSyncJobService(resourceStore, jobStore);
        TeacherSourceSyncJobResponse queued = jobService.createSyncJob(
                "school-a",
                "teacher",
                "teacher-1",
                resource.documentId());
        TeacherSourceSyncExecutionService executionService =
                new TeacherSourceSyncExecutionService(resourceStore, jobStore, blockStore);

        TeacherSourceSyncJobResponse completed = executionService.execute(
                "school-a",
                "teacher",
                "teacher-1",
                resource.documentId(),
                queued.jobId());

        assertThat(completed.status()).isEqualTo("completed");
        assertThat(completed.phase()).isEqualTo("parse_completed");
        assertThat(completed.message()).contains("3 blocks");
        TeacherResourceDocumentResponse synced = resourceStore.find("school-a", resource.documentId());
        assertThat(synced.syncStatus()).isEqualTo("synced");
        assertThat(synced.parseStatus()).isEqualTo("parsed");
        assertThat(synced.embeddingStatus()).isEqualTo("pending");
        List<TeacherDocumentBlockResponse> blocks = blockStore.listByDocument("school-a", resource.documentId());
        assertThat(blocks).hasSize(3);
        assertThat(blocks).extracting(TeacherDocumentBlockResponse::chapter).contains("空间向量");
        assertThat(blocks).extracting(TeacherDocumentBlockResponse::normalizedText)
                .anySatisfy(text -> assertThat(text).contains("数量积用于判断垂直"));
    }

    @Test
    void feishuSyncJobFailsClearlyWhenDownloaderIsNotConfigured() {
        InMemoryTeacherResourceStore resourceStore = new InMemoryTeacherResourceStore();
        InMemoryTeacherSourceSyncJobStore jobStore = new InMemoryTeacherSourceSyncJobStore();
        InMemoryTeacherDocumentBlockStore blockStore = new InMemoryTeacherDocumentBlockStore();
        TeacherResourceService resourceService = new TeacherResourceService(resourceStore);
        TeacherResourceDocumentResponse resource = resourceService.register(new TeacherResourceRegistrationCommand(
                "school-a",
                "teacher",
                "teacher-1",
                "feishu",
                "Feishu math root",
                "https://my.feishu.cn/drive/folder/XVn7fXppJlQMK5dkuOkc1ePan2f",
                null,
                "TEACHER_PRIVATE"));
        TeacherSourceSyncJobService jobService = new TeacherSourceSyncJobService(resourceStore, jobStore);
        TeacherSourceSyncJobResponse queued = jobService.createSyncJob(
                "school-a",
                "teacher",
                "teacher-1",
                resource.documentId());
        TeacherSourceSyncExecutionService executionService = new TeacherSourceSyncExecutionService(
                resourceStore,
                jobStore,
                blockStore,
                new UnconfiguredTeacherFeishuDownloadClient(),
                TeacherSourceSyncProperties.defaults());

        TeacherSourceSyncJobResponse completed = executionService.execute(
                "school-a",
                "teacher",
                "teacher-1",
                resource.documentId(),
                queued.jobId());

        assertThat(completed.status()).isEqualTo("failed");
        assertThat(completed.phase()).isEqualTo("download_failed");
        assertThat(completed.message()).contains("Feishu downloader is not configured");
        TeacherResourceDocumentResponse unchanged = resourceStore.find("school-a", resource.documentId());
        assertThat(unchanged.syncStatus()).isEqualTo("registered");
        assertThat(unchanged.localPath()).isNull();
        assertThat(blockStore.listByDocument("school-a", resource.documentId())).isEmpty();
    }

    @Test
    void realFeishuSyncJobDownloadsOneFileThroughVerifiedScript() {
        Path script = Path.of(System.getProperty("user.home"), ".codex", "skills", "feishu-cloud-docs", "scripts",
                "download_feishu_url.py");
        Path appkey = Path.of("D:/project2026/feishutest/APPKEY.md");
        Assumptions.assumeTrue(Files.isRegularFile(script), "Feishu downloader script is not available locally");
        Assumptions.assumeTrue(Files.isRegularFile(appkey), "Feishu APPKEY path is not available locally");
        InMemoryTeacherResourceStore resourceStore = new InMemoryTeacherResourceStore();
        InMemoryTeacherSourceSyncJobStore jobStore = new InMemoryTeacherSourceSyncJobStore();
        InMemoryTeacherDocumentBlockStore blockStore = new InMemoryTeacherDocumentBlockStore();
        TeacherResourceService resourceService = new TeacherResourceService(resourceStore);
        TeacherResourceDocumentResponse resource = resourceService.register(new TeacherResourceRegistrationCommand(
                "school-a",
                "teacher",
                "teacher-1",
                "feishu",
                "Feishu math root",
                "https://my.feishu.cn/drive/folder/XVn7fXppJlQMK5dkuOkc1ePan2f",
                null,
                "TEACHER_PRIVATE"));
        TeacherSourceSyncJobService jobService = new TeacherSourceSyncJobService(resourceStore, jobStore);
        TeacherSourceSyncJobResponse queued = jobService.createSyncJob(
                "school-a",
                "teacher",
                "teacher-1",
                resource.documentId());
        TeacherSourceSyncProperties properties = new TeacherSourceSyncProperties(
                "https://my.feishu.cn/drive/folder/XVn7fXppJlQMK5dkuOkc1ePan2f",
                script,
                appkey,
                tempDir.resolve("real-feishu-staging"),
                1);
        TeacherSourceSyncExecutionService executionService = new TeacherSourceSyncExecutionService(
                resourceStore,
                jobStore,
                blockStore,
                new ProcessTeacherFeishuDownloadClient(properties),
                properties);

        TeacherSourceSyncJobResponse completed = executionService.execute(
                "school-a",
                "teacher",
                "teacher-1",
                resource.documentId(),
                queued.jobId());

        assertThat(completed.status()).isEqualTo("completed");
        assertThat(completed.phase()).isEqualTo("download_completed");
        assertThat(completed.message()).contains("Downloaded 1 Feishu files");
        assertThat(completed.stagingPath()).isNotBlank();
        assertThat(Files.exists(Path.of(completed.stagingPath()))).isTrue();
        TeacherResourceDocumentResponse downloaded = resourceStore.find("school-a", resource.documentId());
        assertThat(downloaded.syncStatus()).isEqualTo("downloaded");
        assertThat(downloaded.localPath()).isEqualTo(completed.stagingPath());
    }
}
