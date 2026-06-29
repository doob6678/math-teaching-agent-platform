package com.doob.mathagent.teacher;

import static org.assertj.core.api.Assertions.assertThat;

import com.doob.mathagent.teacher.service.InMemoryTeacherDocumentBlockStore;
import com.doob.mathagent.teacher.service.InMemoryTeacherResourceStore;
import com.doob.mathagent.teacher.service.InMemoryTeacherSourceSyncCheckpointStore;
import com.doob.mathagent.teacher.service.InMemoryTeacherSourceSyncJobStore;
import com.doob.mathagent.teacher.service.ProcessTeacherFeishuDownloadClient;
import com.doob.mathagent.teacher.service.TeacherFeishuDownloadClient;
import com.doob.mathagent.teacher.service.TeacherFeishuDownloadException;
import com.doob.mathagent.teacher.service.TeacherResourceRegistrationCommand;
import com.doob.mathagent.teacher.service.TeacherResourceService;
import com.doob.mathagent.teacher.service.TeacherSourceSyncExecutionService;
import com.doob.mathagent.teacher.service.TeacherSourceSyncJobService;
import com.doob.mathagent.teacher.service.TeacherSourceSyncProperties;
import com.doob.mathagent.teacher.service.UnconfiguredTeacherFeishuDownloadClient;
import com.doob.mathagent.teacher.vo.TeacherDocumentBlockResponse;
import com.doob.mathagent.teacher.vo.TeacherResourceDocumentResponse;
import com.doob.mathagent.teacher.vo.TeacherSourceSyncCheckpointResponse;
import com.doob.mathagent.teacher.vo.TeacherSourceSyncJobResponse;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
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
        InMemoryTeacherSourceSyncCheckpointStore checkpointStore = new InMemoryTeacherSourceSyncCheckpointStore();
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
    void localPathSyncJobParsesPdfFilesIntoDocumentBlocks() throws Exception {
        Path bank = tempDir.resolve("teacher-pdf-bank");
        Files.createDirectories(bank);
        writePdf(bank.resolve("vector-method.pdf"), "vector projection method uses dot product");
        InMemoryTeacherResourceStore resourceStore = new InMemoryTeacherResourceStore();
        InMemoryTeacherSourceSyncJobStore jobStore = new InMemoryTeacherSourceSyncJobStore();
        InMemoryTeacherDocumentBlockStore blockStore = new InMemoryTeacherDocumentBlockStore();
        TeacherResourceService resourceService = new TeacherResourceService(resourceStore);
        TeacherResourceDocumentResponse resource = resourceService.register(new TeacherResourceRegistrationCommand(
                "school-a",
                "teacher",
                "teacher-1",
                "local_path",
                "Local PDF vector bank",
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
        assertThat(completed.message()).contains("Parsed 1 blocks");
        assertThat(blockStore.listByDocument("school-a", resource.documentId()))
                .hasSize(1)
                .first()
                .satisfies(block -> {
                    assertThat(block.pageNo()).isEqualTo(1);
                    assertThat(block.normalizedText()).contains("vector projection method");
                });
    }

    @Test
    void feishuSyncJobFailsClearlyWhenDownloaderIsNotConfigured() {
        InMemoryTeacherResourceStore resourceStore = new InMemoryTeacherResourceStore();
        InMemoryTeacherSourceSyncJobStore jobStore = new InMemoryTeacherSourceSyncJobStore();
        InMemoryTeacherDocumentBlockStore blockStore = new InMemoryTeacherDocumentBlockStore();
        InMemoryTeacherSourceSyncCheckpointStore checkpointStore = new InMemoryTeacherSourceSyncCheckpointStore();
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
                TeacherSourceSyncProperties.defaults(),
                checkpointStore);

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
        TeacherSourceSyncCheckpointResponse checkpoint =
                checkpointStore.findByJobId("school-a", queued.jobId()).orElseThrow();
        assertThat(checkpoint.rootToken()).isEqualTo("XVn7fXppJlQMK5dkuOkc1ePan2f");
        assertThat(checkpoint.failedItemsJson()).contains("Feishu downloader is not configured");
    }

    @Test
    void feishuSyncJobPausesAndKeepsCheckpointOnRetryableNetworkFailure() {
        InMemoryTeacherResourceStore resourceStore = new InMemoryTeacherResourceStore();
        InMemoryTeacherSourceSyncJobStore jobStore = new InMemoryTeacherSourceSyncJobStore();
        InMemoryTeacherDocumentBlockStore blockStore = new InMemoryTeacherDocumentBlockStore();
        InMemoryTeacherSourceSyncCheckpointStore checkpointStore = new InMemoryTeacherSourceSyncCheckpointStore();
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
                new RetryableFailingFeishuDownloadClient(),
                TeacherSourceSyncProperties.defaults(),
                checkpointStore);

        TeacherSourceSyncJobResponse paused = executionService.execute(
                "school-a",
                "teacher",
                "teacher-1",
                resource.documentId(),
                queued.jobId());

        assertThat(paused.status()).isEqualTo("paused");
        assertThat(paused.phase()).isEqualTo("download_paused");
        assertThat(paused.message()).contains("ProxyError");
        TeacherResourceDocumentResponse unchanged = resourceStore.find("school-a", resource.documentId());
        assertThat(unchanged.syncStatus()).isEqualTo("registered");
        TeacherSourceSyncCheckpointResponse checkpoint =
                checkpointStore.findByJobId("school-a", queued.jobId()).orElseThrow();
        assertThat(checkpoint.rootToken()).isEqualTo("XVn7fXppJlQMK5dkuOkc1ePan2f");
        assertThat(checkpoint.currentPath()).isEqualTo("Feishu math root");
        assertThat(checkpoint.failedItemsJson()).contains("ProxyError").contains("retryable");
    }

    @Test
    void feishuSyncJobWritesCheckpointWhenDownloadCompletes() throws Exception {
        InMemoryTeacherResourceStore resourceStore = new InMemoryTeacherResourceStore();
        InMemoryTeacherSourceSyncJobStore jobStore = new InMemoryTeacherSourceSyncJobStore();
        InMemoryTeacherDocumentBlockStore blockStore = new InMemoryTeacherDocumentBlockStore();
        InMemoryTeacherSourceSyncCheckpointStore checkpointStore = new InMemoryTeacherSourceSyncCheckpointStore();
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
        Path savedPath = tempDir.resolve("downloaded-feishu");
        Files.createDirectories(savedPath);
        Files.writeString(savedPath.resolve("summary.txt"), "Feishu downloaded summary");
        TeacherSourceSyncExecutionService executionService = new TeacherSourceSyncExecutionService(
                resourceStore,
                jobStore,
                blockStore,
                new SuccessfulFeishuDownloadClient(savedPath),
                TeacherSourceSyncProperties.defaults(),
                checkpointStore);

        TeacherSourceSyncJobResponse completed = executionService.execute(
                "school-a",
                "teacher",
                "teacher-1",
                resource.documentId(),
                queued.jobId());

        assertThat(completed.status()).isEqualTo("completed");
        assertThat(completed.phase()).isEqualTo("download_completed");
        assertThat(blockStore.listByDocument("school-a", resource.documentId())).hasSize(1);
        TeacherSourceSyncCheckpointResponse checkpoint =
                checkpointStore.findByJobId("school-a", queued.jobId()).orElseThrow();
        assertThat(checkpoint.downloadedItemsJson()).contains("downloaded-feishu").contains("\"files\":1");
        assertThat(checkpoint.failedItemsJson()).isEqualTo("[]");
        assertThat(checkpoint.cursorVersion()).isEqualTo(2);
    }

    @Test
    void feishuSyncJobParsesDownloadedDocxAndTextFilesIntoDocumentBlocks() throws Exception {
        Path savedPath = tempDir.resolve("downloaded-feishu-content");
        Files.createDirectories(savedPath);
        writeDocx(savedPath.resolve("probability-mistakes.docx"), List.of(
                "概率统计易错题",
                "条件概率要先确定样本空间，再判断事件包含关系。"));
        Files.writeString(savedPath.resolve("histogram.txt"), "频率分布直方图需要先统一组距，再计算频率除以组距。");
        InMemoryTeacherResourceStore resourceStore = new InMemoryTeacherResourceStore();
        InMemoryTeacherSourceSyncJobStore jobStore = new InMemoryTeacherSourceSyncJobStore();
        InMemoryTeacherDocumentBlockStore blockStore = new InMemoryTeacherDocumentBlockStore();
        InMemoryTeacherSourceSyncCheckpointStore checkpointStore = new InMemoryTeacherSourceSyncCheckpointStore();
        TeacherResourceService resourceService = new TeacherResourceService(resourceStore);
        TeacherResourceDocumentResponse resource = resourceService.register(new TeacherResourceRegistrationCommand(
                "school-a",
                "teacher",
                "teacher-1",
                "feishu",
                "Feishu probability bank",
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
                new SuccessfulFeishuDownloadClient(savedPath),
                TeacherSourceSyncProperties.defaults(),
                checkpointStore);

        TeacherSourceSyncJobResponse completed = executionService.execute(
                "school-a",
                "teacher",
                "teacher-1",
                resource.documentId(),
                queued.jobId());

        assertThat(completed.status()).isEqualTo("completed");
        assertThat(completed.phase()).isEqualTo("download_completed");
        assertThat(completed.message()).contains("Parsed 3 blocks");
        TeacherResourceDocumentResponse synced = resourceStore.find("school-a", resource.documentId());
        assertThat(synced.syncStatus()).isEqualTo("synced");
        assertThat(synced.parseStatus()).isEqualTo("parsed");
        assertThat(synced.localPath()).isEqualTo(savedPath.toString());
        assertThat(blockStore.listByDocument("school-a", resource.documentId()))
                .hasSize(3)
                .extracting(TeacherDocumentBlockResponse::normalizedText)
                .anySatisfy(text -> assertThat(text).contains("条件概率"))
                .anySatisfy(text -> assertThat(text).contains("频率分布直方图"));
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
        assertThat(downloaded.syncStatus()).isEqualTo("synced");
        assertThat(downloaded.parseStatus()).isEqualTo("parsed");
        assertThat(downloaded.localPath()).isEqualTo(completed.stagingPath());
        assertThat(blockStore.listByDocument("school-a", resource.documentId())).isNotEmpty();
    }

    /**
     * Writes a real DOCX file so parser tests cover Office package handling.
     */
    private static void writeDocx(Path path, List<String> paragraphs) throws Exception {
        try (XWPFDocument document = new XWPFDocument();
                OutputStream output = Files.newOutputStream(path)) {
            for (String paragraph : paragraphs) {
                document.createParagraph().createRun().setText(paragraph);
            }
            document.write(output);
        }
    }

    /**
     * Writes a simple searchable PDF page for parser coverage.
     */
    private static void writePdf(Path path, String text) throws Exception {
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage();
            document.addPage(page);
            try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                content.beginText();
                content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                content.newLineAtOffset(72, 720);
                content.showText(text);
                content.endText();
            }
            document.save(path.toFile());
        }
    }

    private static final class RetryableFailingFeishuDownloadClient implements TeacherFeishuDownloadClient {

        @Override
        public FeishuDownloadResult download(String url, Path stagingRoot, int maxFiles) {
            throw new TeacherFeishuDownloadException(
                    "ProxyError: Remote end closed connection without response",
                    true);
        }
    }

    private static final class SuccessfulFeishuDownloadClient implements TeacherFeishuDownloadClient {

        private final Path savedPath;

        private SuccessfulFeishuDownloadClient(Path savedPath) {
            this.savedPath = savedPath;
        }

        @Override
        public FeishuDownloadResult download(String url, Path stagingRoot, int maxFiles) {
            return new FeishuDownloadResult(savedPath, 1, 0, 0, "Downloaded 1 Feishu files; skipped 0");
        }
    }
}
