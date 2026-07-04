package com.doob.mathagent.teacher;

import static org.assertj.core.api.Assertions.assertThat;

import com.doob.mathagent.knowledge.service.InMemoryKnowledgeQuestionBankStore;
import com.doob.mathagent.knowledge.service.KnowledgeQuestionBankService;
import com.doob.mathagent.knowledge.service.TeacherBlockQuestionImportService;
import com.doob.mathagent.knowledge.vo.QuestionBankItemResponse;
import com.doob.mathagent.knowledge.vo.TeacherBlockQuestionImportResponse;
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
import com.doob.mathagent.vector.service.VectorHttpResponse;
import com.doob.mathagent.vector.service.VectorHttpTransport;
import com.doob.mathagent.vector.service.VectorIndexProperties;
import com.doob.mathagent.vector.service.VectorIndexService;
import com.doob.mathagent.vector.service.TestVectorIndexService;
import java.io.OutputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
                # Space Vector

                Space vectors describe magnitude and direction.
                ## Dot Product
                Dot product supports angle and projection problems.
                """);
        Files.writeString(bank.resolve("exercise.txt"), "Exercise: given vectors a and b, compute a*b.");
        InMemoryTeacherResourceStore resourceStore = new InMemoryTeacherResourceStore();
        InMemoryTeacherSourceSyncJobStore jobStore = new InMemoryTeacherSourceSyncJobStore();
        InMemoryTeacherDocumentBlockStore blockStore = new InMemoryTeacherDocumentBlockStore();
        InMemoryTeacherSourceSyncCheckpointStore checkpointStore = new InMemoryTeacherSourceSyncCheckpointStore();
        TeacherResourceService resourceService = TeacherResourceServiceFixture.service(resourceStore);
        TeacherResourceDocumentResponse resource = resourceService.register(new TeacherResourceRegistrationCommand(
                "school-a",
                "teacher",
                "teacher-1",
                "local_path",
                "Local vector bank",
                null,
                bank.toString(),
                "TEACHER_PRIVATE",
                null));
        TeacherSourceSyncJobService jobService = new TeacherSourceSyncJobService(resourceStore, jobStore);
        TeacherSourceSyncJobResponse queued = jobService.createSyncJob(
                "school-a",
                "teacher",
                "teacher-1",
                resource.documentId());
        TeacherSourceSyncExecutionService executionService =
                new TeacherSourceSyncExecutionService(
                        resourceStore,
                        jobStore,
                        blockStore,
                        emptyDownloadClient(),
                        testSyncProperties(),
                        checkpointStore,
                        TestVectorIndexService.successful(resourceStore, blockStore));

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
        assertThat(synced.embeddingStatus()).isEqualTo("ready");
        assertThat(synced.indexStatus()).isEqualTo("ready");
        List<TeacherDocumentBlockResponse> blocks = blockStore.listByDocument("school-a", resource.documentId());
        assertThat(blocks).hasSize(3);
        assertThat(blocks).extracting(TeacherDocumentBlockResponse::chapter).contains("Space Vector");
        assertThat(blocks).extracting(TeacherDocumentBlockResponse::normalizedText)
                .anySatisfy(text -> assertThat(text).contains("Dot product supports angle"));
    }

    @Test
    void localPathSyncAutomaticallyRebuildsVectorIndexWhenConfigured() throws Exception {
        Path bank = tempDir.resolve("teacher-bank-vector-index");
        Files.createDirectories(bank);
        Files.writeString(bank.resolve("space-vector.md"), """
                # Space vector

                Dot product calculates angles and perpendicularity.
                """);
        InMemoryTeacherResourceStore resourceStore = new InMemoryTeacherResourceStore();
        InMemoryTeacherSourceSyncJobStore jobStore = new InMemoryTeacherSourceSyncJobStore();
        InMemoryTeacherDocumentBlockStore blockStore = new InMemoryTeacherDocumentBlockStore();
        TeacherResourceService resourceService = TeacherResourceServiceFixture.service(resourceStore);
        TeacherResourceDocumentResponse resource = resourceService.register(new TeacherResourceRegistrationCommand(
                "school-a",
                "teacher",
                "teacher-1",
                "local_path",
                "Local vector auto index bank",
                null,
                bank.toString(),
                "TEACHER_PRIVATE",
                null));
        TeacherSourceSyncJobService jobService = new TeacherSourceSyncJobService(resourceStore, jobStore);
        TeacherSourceSyncJobResponse queued = jobService.createSyncJob(
                "school-a",
                "teacher",
                "teacher-1",
                resource.documentId());
        CapturingVectorTransport transport = new CapturingVectorTransport(false);
        VectorIndexService vectorIndexService = new VectorIndexService(
                configuredVectorProperties(),
                transport,
                resourceStore,
                blockStore);
        TeacherSourceSyncExecutionService executionService =
                new TeacherSourceSyncExecutionService(
                        resourceStore,
                        jobStore,
                        blockStore,
                        emptyDownloadClient(),
                        testSyncProperties(),
                        new InMemoryTeacherSourceSyncCheckpointStore(),
                        vectorIndexService);

        TeacherSourceSyncJobResponse completed = executionService.execute(
                "school-a",
                "teacher",
                "teacher-1",
                resource.documentId(),
                queued.jobId());

        assertThat(completed.status()).isEqualTo("completed");
        assertThat(completed.message()).contains("Vector index indexed");
        TeacherResourceDocumentResponse synced = resourceStore.find("school-a", resource.documentId());
        assertThat(synced.embeddingStatus()).isEqualTo("ready");
        assertThat(synced.indexStatus()).isEqualTo("ready");
        assertThat(transport.requests).extracting(VectorRequest::uri)
                .containsExactly(
                        URI.create("https://embedding.local/v1/embeddings"),
                        URI.create("http://milvus.local:19530/v2/vectordb/collections/create"),
                        URI.create("http://milvus.local:19530/v2/vectordb/indexes/create"),
                        URI.create("http://milvus.local:19530/v2/vectordb/entities/delete"),
                        URI.create("http://milvus.local:19530/v2/vectordb/entities/upsert"),
                        URI.create("http://milvus.local:19530/v2/vectordb/collections/flush"),
                        URI.create("http://milvus.local:19530/v2/vectordb/collections/load"));
    }

    @Test
    void localPathSyncFailsWhenAutomaticVectorIndexingFails() throws Exception {
        Path bank = tempDir.resolve("teacher-bank-vector-failure");
        Files.createDirectories(bank);
        Files.writeString(bank.resolve("space-vector.md"), """
                # Space vector

                Dot product calculates angles and perpendicularity.
                """);
        InMemoryTeacherResourceStore resourceStore = new InMemoryTeacherResourceStore();
        InMemoryTeacherSourceSyncJobStore jobStore = new InMemoryTeacherSourceSyncJobStore();
        InMemoryTeacherDocumentBlockStore blockStore = new InMemoryTeacherDocumentBlockStore();
        TeacherResourceService resourceService = TeacherResourceServiceFixture.service(resourceStore);
        TeacherResourceDocumentResponse resource = resourceService.register(new TeacherResourceRegistrationCommand(
                "school-a",
                "teacher",
                "teacher-1",
                "local_path",
                "Local vector failed index bank",
                null,
                bank.toString(),
                "TEACHER_PRIVATE",
                null));
        TeacherSourceSyncJobService jobService = new TeacherSourceSyncJobService(resourceStore, jobStore);
        TeacherSourceSyncJobResponse queued = jobService.createSyncJob(
                "school-a",
                "teacher",
                "teacher-1",
                resource.documentId());
        VectorIndexService vectorIndexService = new VectorIndexService(
                configuredVectorProperties(),
                new CapturingVectorTransport(true),
                resourceStore,
                blockStore);
        TeacherSourceSyncExecutionService executionService =
                new TeacherSourceSyncExecutionService(
                        resourceStore,
                        jobStore,
                        blockStore,
                        emptyDownloadClient(),
                        testSyncProperties(),
                        new InMemoryTeacherSourceSyncCheckpointStore(),
                        vectorIndexService);

        TeacherSourceSyncJobResponse completed = executionService.execute(
                "school-a",
                "teacher",
                "teacher-1",
                resource.documentId(),
                queued.jobId());

        assertThat(completed.status()).isEqualTo("failed");
        assertThat(completed.phase()).isEqualTo("vector_index_failed");
        assertThat(completed.message()).contains("Vector index rebuild failed");
        TeacherResourceDocumentResponse synced = resourceStore.find("school-a", resource.documentId());
        assertThat(synced.syncStatus()).isEqualTo("synced");
        assertThat(synced.parseStatus()).isEqualTo("parsed");
        assertThat(synced.embeddingStatus()).isEqualTo("failed");
        assertThat(synced.indexStatus()).isEqualTo("failed");
    }

    @Test
    void parsedMarkdownQuestionBlocksImportIntoQuestionBankWithKnowledgeLink() throws Exception {
        Path bank = tempDir.resolve("teacher-question-bank");
        Files.createDirectories(bank);
        Files.writeString(bank.resolve("space-vector-question.md"), """
                # Space Vector

                ## Dot Product
                Find a*b for vectors a=(1,2,2), b=(2,0,1)?
                """);
        InMemoryTeacherResourceStore resourceStore = new InMemoryTeacherResourceStore();
        InMemoryTeacherSourceSyncJobStore jobStore = new InMemoryTeacherSourceSyncJobStore();
        InMemoryTeacherDocumentBlockStore blockStore = new InMemoryTeacherDocumentBlockStore();
        InMemoryKnowledgeQuestionBankStore questionStore = new InMemoryKnowledgeQuestionBankStore();
        TeacherResourceService resourceService = TeacherResourceServiceFixture.service(resourceStore);
        TeacherResourceDocumentResponse resource = resourceService.register(new TeacherResourceRegistrationCommand(
                "school-a",
                "teacher",
                "teacher-1",
                "local_path",
                "Local vector question bank",
                null,
                bank.toString(),
                "TEACHER_PRIVATE",
                null));
        TeacherSourceSyncJobService jobService = new TeacherSourceSyncJobService(resourceStore, jobStore);
        TeacherSourceSyncJobResponse queued = jobService.createSyncJob(
                "school-a",
                "teacher",
                "teacher-1",
                resource.documentId());
        TeacherSourceSyncExecutionService executionService =
                new TeacherSourceSyncExecutionService(
                        resourceStore,
                        jobStore,
                        blockStore,
                        emptyDownloadClient(),
                        testSyncProperties(),
                        new InMemoryTeacherSourceSyncCheckpointStore(),
                        TestVectorIndexService.successful(resourceStore, blockStore));

        TeacherSourceSyncJobResponse completed = executionService.execute(
                "school-a",
                "teacher",
                "teacher-1",
                resource.documentId(),
                queued.jobId());
        List<TeacherDocumentBlockResponse> parsedBlocks =
                blockStore.listByDocument("school-a", resource.documentId());
        TeacherBlockQuestionImportService importService = new TeacherBlockQuestionImportService(
                resourceStore,
                blockStore,
                new KnowledgeQuestionBankService(questionStore),
                questionStore);
        TeacherBlockQuestionImportResponse imported = importService.importFromTeacherResource(
                "school-a",
                "teacher",
                "teacher-1",
                resource.documentId());

        assertThat(completed.status()).isEqualTo("completed");
        assertThat(parsedBlocks)
                .hasSize(1)
                .first()
                .satisfies(block -> {
                    assertThat(block.chapter()).isEqualTo("Space Vector");
                    assertThat(block.section()).isEqualTo("Dot Product");
                    assertThat(block.normalizedText()).contains("a*b");
                });
        assertThat(imported.importedQuestionCount()).isEqualTo(1);
        assertThat(imported.skippedBlockCount()).isZero();
        assertThat(imported.linkedKnowledgePointCount()).isEqualTo(1);
        QuestionBankItemResponse question = imported.importedQuestions().getFirst();
        assertThat(question.sourceResourceDocumentId()).isEqualTo(resource.documentId());
        assertThat(question.sourceBlockId()).isEqualTo(parsedBlocks.getFirst().blockId());
        assertThat(question.sourceChecksum()).isEqualTo(parsedBlocks.getFirst().checksum());
        assertThat(question.knowledgePointIds()).hasSize(1);
        assertThat(importService.searchQuestions("school-a", "teacher", "teacher-1", "a*b", 10))
                .extracting(QuestionBankItemResponse::questionId)
                .containsExactly(question.questionId());
    }

    @Test
    void localPathSyncJobParsesPdfFilesIntoDocumentBlocks() throws Exception {
        Path bank = tempDir.resolve("teacher-pdf-bank");
        Files.createDirectories(bank);
        writePdf(bank.resolve("vector-method.pdf"), "vector projection method uses dot product");
        InMemoryTeacherResourceStore resourceStore = new InMemoryTeacherResourceStore();
        InMemoryTeacherSourceSyncJobStore jobStore = new InMemoryTeacherSourceSyncJobStore();
        InMemoryTeacherDocumentBlockStore blockStore = new InMemoryTeacherDocumentBlockStore();
        TeacherResourceService resourceService = TeacherResourceServiceFixture.service(resourceStore);
        TeacherResourceDocumentResponse resource = resourceService.register(new TeacherResourceRegistrationCommand(
                "school-a",
                "teacher",
                "teacher-1",
                "local_path",
                "Local PDF vector bank",
                null,
                bank.toString(),
                "TEACHER_PRIVATE",
                null));
        TeacherSourceSyncJobService jobService = new TeacherSourceSyncJobService(resourceStore, jobStore);
        TeacherSourceSyncJobResponse queued = jobService.createSyncJob(
                "school-a",
                "teacher",
                "teacher-1",
                resource.documentId());
        TeacherSourceSyncExecutionService executionService =
                new TeacherSourceSyncExecutionService(
                        resourceStore,
                        jobStore,
                        blockStore,
                        emptyDownloadClient(),
                        testSyncProperties(),
                        new InMemoryTeacherSourceSyncCheckpointStore(),
                        TestVectorIndexService.successful(resourceStore, blockStore));

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
        TeacherResourceService resourceService = TeacherResourceServiceFixture.service(resourceStore);
        TeacherResourceDocumentResponse resource = resourceService.register(new TeacherResourceRegistrationCommand(
                "school-a",
                "teacher",
                "teacher-1",
                "feishu",
                "Feishu math root",
                "https://my.feishu.cn/drive/folder/XVn7fXppJlQMK5dkuOkc1ePan2f",
                null,
                "TEACHER_PRIVATE",
                "md"));
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
                testSyncProperties(),
                checkpointStore,
                TestVectorIndexService.successful(resourceStore, blockStore));

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
        TeacherResourceService resourceService = TeacherResourceServiceFixture.service(resourceStore);
        TeacherResourceDocumentResponse resource = resourceService.register(new TeacherResourceRegistrationCommand(
                "school-a",
                "teacher",
                "teacher-1",
                "feishu",
                "Feishu math root",
                "https://my.feishu.cn/drive/folder/XVn7fXppJlQMK5dkuOkc1ePan2f",
                null,
                "TEACHER_PRIVATE",
                "md"));
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
                testSyncProperties(),
                checkpointStore,
                TestVectorIndexService.successful(resourceStore, blockStore));

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
        TeacherResourceService resourceService = TeacherResourceServiceFixture.service(resourceStore);
        TeacherResourceDocumentResponse resource = resourceService.register(new TeacherResourceRegistrationCommand(
                "school-a",
                "teacher",
                "teacher-1",
                "feishu",
                "Feishu math root",
                "https://my.feishu.cn/drive/folder/XVn7fXppJlQMK5dkuOkc1ePan2f",
                null,
                "TEACHER_PRIVATE",
                "md"));
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
                testSyncProperties(),
                checkpointStore,
                TestVectorIndexService.successful(resourceStore, blockStore));

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
    void resumeFeishuSyncJobPassesDurableCheckpointToDownloader() throws Exception {
        InMemoryTeacherResourceStore resourceStore = new InMemoryTeacherResourceStore();
        InMemoryTeacherSourceSyncJobStore jobStore = new InMemoryTeacherSourceSyncJobStore();
        InMemoryTeacherDocumentBlockStore blockStore = new InMemoryTeacherDocumentBlockStore();
        InMemoryTeacherSourceSyncCheckpointStore checkpointStore = new InMemoryTeacherSourceSyncCheckpointStore();
        TeacherResourceService resourceService = TeacherResourceServiceFixture.service(resourceStore);
        TeacherResourceDocumentResponse resource = resourceService.register(new TeacherResourceRegistrationCommand(
                "school-a",
                "teacher",
                "teacher-1",
                "feishu",
                "Feishu vector root",
                "https://my.feishu.cn/drive/folder/rootToken",
                null,
                "TEACHER_PRIVATE",
                "md"));
        TeacherSourceSyncJobService jobService = new TeacherSourceSyncJobService(resourceStore, jobStore);
        TeacherSourceSyncJobResponse queued = jobService.createSyncJob(
                "school-a",
                "teacher",
                "teacher-1",
                resource.documentId());
        jobStore.save(new TeacherSourceSyncJobResponse(
                queued.jobId(),
                queued.documentId(),
                queued.tenantId(),
                queued.sourceType(),
                queued.operation(),
                "paused",
                "download_paused",
                queued.attempt(),
                queued.createdBy(),
                queued.stagingPath(),
                "ProxyError: paused after page token",
                queued.createdAt(),
                queued.updatedAt()));
        checkpointStore.save(new TeacherSourceSyncCheckpointResponse(
                queued.jobId(),
                "school-a",
                resource.documentId(),
                "rootToken",
                "folderToken-2",
                "Root/Chapter/Section",
                "pageToken-3",
                "[\"rootToken\",\"folderToken-1\",\"folderToken-2\"]",
                "[{\"token\":\"docx-1\"}]",
                "[{\"message\":\"ProxyError\",\"retryable\":true}]",
                2,
                java.time.Instant.now().toString()));
        Path savedPath = tempDir.resolve("checkpoint-resume");
        Files.createDirectories(savedPath);
        Files.writeString(savedPath.resolve("resume.txt"), "resumed from checkpoint");
        CapturingCheckpointFeishuDownloadClient feishuClient =
                new CapturingCheckpointFeishuDownloadClient(savedPath);
        TeacherSourceSyncExecutionService executionService = new TeacherSourceSyncExecutionService(
                resourceStore,
                jobStore,
                blockStore,
                feishuClient,
                testSyncProperties(),
                checkpointStore,
                TestVectorIndexService.successful(resourceStore, blockStore));

        TeacherSourceSyncJobResponse completed = executionService.resume(
                "school-a",
                "teacher",
                "teacher-1",
                resource.documentId(),
                queued.jobId());

        assertThat(completed.status()).isEqualTo("completed");
        assertThat(feishuClient.checkpoint).isNotNull();
        assertThat(feishuClient.checkpoint.currentFolderToken()).isEqualTo("folderToken-2");
        assertThat(feishuClient.checkpoint.pageToken()).isEqualTo("pageToken-3");
        assertThat(feishuClient.checkpoint.currentPath()).isEqualTo("Root/Chapter/Section");
        assertThat(feishuClient.checkpoint.visitedFolderTokensJson()).contains("folderToken-1");
        assertThat(feishuClient.checkpoint.downloadedItemsJson()).contains("docx-1");
    }

    @Test
    void retryableFeishuFailureStoresLatestDownloaderCheckpoint() {
        InMemoryTeacherResourceStore resourceStore = new InMemoryTeacherResourceStore();
        InMemoryTeacherSourceSyncJobStore jobStore = new InMemoryTeacherSourceSyncJobStore();
        InMemoryTeacherDocumentBlockStore blockStore = new InMemoryTeacherDocumentBlockStore();
        InMemoryTeacherSourceSyncCheckpointStore checkpointStore = new InMemoryTeacherSourceSyncCheckpointStore();
        TeacherResourceService resourceService = TeacherResourceServiceFixture.service(resourceStore);
        TeacherResourceDocumentResponse resource = resourceService.register(new TeacherResourceRegistrationCommand(
                "school-a",
                "teacher",
                "teacher-1",
                "feishu",
                "Feishu vector root",
                "https://my.feishu.cn/drive/folder/rootToken",
                null,
                "TEACHER_PRIVATE",
                "md"));
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
                new FailingWithCheckpointFeishuDownloadClient(),
                testSyncProperties(),
                checkpointStore,
                TestVectorIndexService.successful(resourceStore, blockStore));

        TeacherSourceSyncJobResponse paused = executionService.execute(
                "school-a",
                "teacher",
                "teacher-1",
                resource.documentId(),
                queued.jobId());

        assertThat(paused.status()).isEqualTo("paused");
        TeacherSourceSyncCheckpointResponse checkpoint =
                checkpointStore.findByJobId("school-a", queued.jobId()).orElseThrow();
        assertThat(checkpoint.currentFolderToken()).isEqualTo("folderToken-9");
        assertThat(checkpoint.pageToken()).isEqualTo("pageToken-10");
        assertThat(checkpoint.currentPath()).isEqualTo("Root/Interrupted Folder");
        assertThat(checkpoint.downloadedItemsJson()).contains("docx-9");
        assertThat(checkpoint.failedItemsJson()).contains("ProxyError").contains("retryable");
    }

    @Test
    void feishuSyncJobParsesDownloadedDocxAndTextFilesIntoDocumentBlocks() throws Exception {
        Path savedPath = tempDir.resolve("downloaded-feishu-content");
        Files.createDirectories(savedPath);
        writeDocx(savedPath.resolve("probability-mistakes.docx"), List.of(
                "Probability mistakes",
                "Use a table to compare events and outcomes."));
        Files.writeString(savedPath.resolve("histogram.txt"), "Histogram data shows frequency and variance.");
        InMemoryTeacherResourceStore resourceStore = new InMemoryTeacherResourceStore();
        InMemoryTeacherSourceSyncJobStore jobStore = new InMemoryTeacherSourceSyncJobStore();
        InMemoryTeacherDocumentBlockStore blockStore = new InMemoryTeacherDocumentBlockStore();
        InMemoryTeacherSourceSyncCheckpointStore checkpointStore = new InMemoryTeacherSourceSyncCheckpointStore();
        TeacherResourceService resourceService = TeacherResourceServiceFixture.service(resourceStore);
        TeacherResourceDocumentResponse resource = resourceService.register(new TeacherResourceRegistrationCommand(
                "school-a",
                "teacher",
                "teacher-1",
                "feishu",
                "Feishu probability bank",
                "https://my.feishu.cn/drive/folder/XVn7fXppJlQMK5dkuOkc1ePan2f",
                null,
                "TEACHER_PRIVATE",
                "md"));
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
                testSyncProperties(),
                checkpointStore,
                TestVectorIndexService.successful(resourceStore, blockStore));

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
                .anySatisfy(text -> assertThat(text).contains("Use a table"))
                .anySatisfy(text -> assertThat(text).contains("Histogram data"));
    }

    @Test
    void realFeishuSyncJobDownloadsOneFileThroughVerifiedScript() {
        Path script = Path.of("..", "ai-worker-python", "scripts", "download_feishu_url.py")
                .toAbsolutePath()
                .normalize();
        Path appkey = Path.of("D:/project2026/feishutest/APPKEY.md");
        Assumptions.assumeTrue(Files.isRegularFile(script), "Feishu downloader script is not available locally");
        Assumptions.assumeTrue(Files.isRegularFile(appkey), "Feishu APPKEY path is not available locally");
        InMemoryTeacherResourceStore resourceStore = new InMemoryTeacherResourceStore();
        InMemoryTeacherSourceSyncJobStore jobStore = new InMemoryTeacherSourceSyncJobStore();
        InMemoryTeacherDocumentBlockStore blockStore = new InMemoryTeacherDocumentBlockStore();
        TeacherResourceService resourceService = TeacherResourceServiceFixture.service(resourceStore);
        TeacherResourceDocumentResponse resource = resourceService.register(new TeacherResourceRegistrationCommand(
                "school-a",
                "teacher",
                "teacher-1",
                "feishu",
                "Feishu math root",
                "https://my.feishu.cn/drive/folder/XVn7fXppJlQMK5dkuOkc1ePan2f",
                null,
                "TEACHER_PRIVATE",
                "md"));
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
                properties,
                new InMemoryTeacherSourceSyncCheckpointStore(),
                TestVectorIndexService.successful(resourceStore, blockStore));

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
     * Explicit Feishu sync fixture config. Production code must not expose a default Feishu URL.
     */
    private TeacherSourceSyncProperties testSyncProperties() {
        return new TeacherSourceSyncProperties(
                "",
                Path.of("ai-worker-python/scripts/download_feishu_url.py"),
                tempDir.resolve("APPKEY.md"),
                tempDir.resolve("feishu-staging"),
                1,
                30);
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

    private static VectorIndexProperties configuredVectorProperties() {
        return new VectorIndexProperties(
                true,
                "http://milvus.local:19530",
                "token",
                "math_agent_resource_blocks",
                3,
                "https://embedding.local/v1",
                "embedding-key",
                "text-embedding-3-small",
                10000);
    }

    private static TeacherFeishuDownloadClient emptyDownloadClient() {
        return new TeacherFeishuDownloadClient() {
            @Override
            public FeishuDownloadResult download(
                    String url,
                    Path stagingRoot,
                    int maxFiles,
                    String fileExtension,
                    FeishuDownloadCheckpoint checkpoint) {
                try {
                    Files.createDirectories(stagingRoot);
                } catch (java.io.IOException exception) {
                    throw new IllegalStateException("Failed to create empty Feishu fixture", exception);
                }
                return new FeishuDownloadResult(
                        stagingRoot,
                        0,
                        0,
                        0,
                        "No Feishu files in this test fixture",
                        FeishuDownloadCheckpoint.empty(),
                        "[]",
                        "[]");
            }
        };
    }

    private static final class CapturingVectorTransport implements VectorHttpTransport {

        private final boolean failUpsert;
        private final List<VectorRequest> requests = new ArrayList<>();

        private CapturingVectorTransport(boolean failUpsert) {
            this.failUpsert = failUpsert;
        }

        @Override
        public VectorHttpResponse postJson(
                URI uri,
                Map<String, String> headers,
                String body,
                Duration timeout) {
            requests.add(new VectorRequest(uri, headers, body, timeout));
            if (uri.toString().endsWith("/embeddings")) {
                return new VectorHttpResponse(200, """
                        {"data":[{"embedding":[0.1,0.2,0.3]}],"usage":{"prompt_tokens":7}}
                        """);
            }
            if (uri.toString().endsWith("/collections/create")) {
                return new VectorHttpResponse(200, "{\"code\":0}");
            }
            if (uri.toString().endsWith("/indexes/create")) {
                return new VectorHttpResponse(200, "{\"code\":0}");
            }
            if (uri.toString().endsWith("/entities/delete")) {
                return new VectorHttpResponse(200, "{\"code\":0,\"data\":{\"deleteCount\":1}}");
            }
            if (uri.toString().endsWith("/entities/upsert")) {
                return failUpsert
                        ? new VectorHttpResponse(500, "{\"code\":500,\"message\":\"temporary milvus failure\"}")
                        : new VectorHttpResponse(200, "{\"code\":0,\"data\":{\"upsertCount\":1}}");
            }
            if (uri.toString().endsWith("/collections/flush")) {
                return new VectorHttpResponse(200, "{\"code\":0}");
            }
            if (uri.toString().endsWith("/collections/load")) {
                return new VectorHttpResponse(200, "{\"code\":0}");
            }
            return new VectorHttpResponse(404, "{}");
        }
    }

    private record VectorRequest(URI uri, Map<String, String> headers, String body, Duration timeout) {
    }

    private static final class RetryableFailingFeishuDownloadClient implements TeacherFeishuDownloadClient {

        @Override
        public FeishuDownloadResult download(
                String url,
                Path stagingRoot,
                int maxFiles,
                String fileExtension,
                FeishuDownloadCheckpoint checkpoint) {
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
        public FeishuDownloadResult download(
                String url,
                Path stagingRoot,
                int maxFiles,
                String fileExtension,
                FeishuDownloadCheckpoint checkpoint) {
            return new FeishuDownloadResult(
                    savedPath,
                    1,
                    0,
                    0,
                    "Downloaded 1 Feishu files; skipped 0",
                    FeishuDownloadCheckpoint.empty(),
                    "[]",
                    "[]");
        }
    }

    private static final class FailingWithCheckpointFeishuDownloadClient implements TeacherFeishuDownloadClient {

        @Override
        public FeishuDownloadResult download(
                String url,
                Path stagingRoot,
                int maxFiles,
                String fileExtension,
                TeacherFeishuDownloadClient.FeishuDownloadCheckpoint checkpoint) {
            throw new TeacherFeishuDownloadException(
                    "ProxyError: tunnel reset after page",
                    true,
                    null,
                    new TeacherFeishuDownloadClient.FeishuDownloadCheckpoint(
                            "folderToken-9",
                            "Root/Interrupted Folder",
                            "pageToken-10",
                            "[\"rootToken\",\"folderToken-9\"]",
                            "[{\"token\":\"docx-9\"}]"));
        }
    }

    private static final class CapturingCheckpointFeishuDownloadClient implements TeacherFeishuDownloadClient {

        private final Path savedPath;
        private TeacherFeishuDownloadClient.FeishuDownloadCheckpoint checkpoint;

        private CapturingCheckpointFeishuDownloadClient(Path savedPath) {
            this.savedPath = savedPath;
        }

        @Override
        public FeishuDownloadResult download(
                String url,
                Path stagingRoot,
                int maxFiles,
                String fileExtension,
                TeacherFeishuDownloadClient.FeishuDownloadCheckpoint checkpoint) {
            this.checkpoint = checkpoint;
            return new FeishuDownloadResult(
                    savedPath,
                    1,
                    0,
                    0,
                    "Downloaded 1 Feishu files from checkpoint",
                    checkpoint,
                    checkpoint == null ? "[]" : checkpoint.downloadedItemsJson(),
                    "[]");
        }
    }
}
