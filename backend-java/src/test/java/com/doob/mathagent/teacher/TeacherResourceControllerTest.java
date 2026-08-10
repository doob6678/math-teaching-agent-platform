package com.doob.mathagent.teacher;

import static org.assertj.core.api.Assertions.assertThat;

import com.doob.mathagent.infrastructure.security.RequestSubject;
import com.doob.mathagent.infrastructure.security.RequestSubjectResolver;
import com.doob.mathagent.teacher.controller.TeacherResourceController;
import com.doob.mathagent.teacher.dto.TeacherResourceRegistrationRequest;
import com.doob.mathagent.teacher.service.InMemoryTeacherDocumentBlockStore;
import com.doob.mathagent.teacher.service.InMemoryTeacherResourceAssetStore;
import com.doob.mathagent.teacher.service.InMemoryTeacherResourceStore;
import com.doob.mathagent.teacher.service.InMemoryTeacherSourceSyncCheckpointStore;
import com.doob.mathagent.teacher.service.InMemoryTeacherSourceSyncJobStore;
import com.doob.mathagent.teacher.search.audit.RecentTeacherResourceBlockSearchAuditStore;
import com.doob.mathagent.teacher.search.audit.TeacherResourceBlockSearchAuditEvent;
import com.doob.mathagent.teacher.search.audit.TeacherResourceBlockSearchAuditLookup;
import com.doob.mathagent.teacher.service.TeacherResourceBlockSearchService;
import com.doob.mathagent.teacher.service.TeacherResourceAssetService;
import com.doob.mathagent.teacher.feishu.TeacherFeishuDownloadClient;
import com.doob.mathagent.teacher.feishu.TeacherFeishuDownloadException;
import com.doob.mathagent.teacher.search.TeacherResourceGraphAlignmentService;
import com.doob.mathagent.teacher.service.TeacherResourceUploadService;
import com.doob.mathagent.teacher.service.TeacherResourceService;
import com.doob.mathagent.teacher.sync.TeacherSourceSyncCheckpointQueryService;
import com.doob.mathagent.teacher.service.TeacherSourceSyncExecutionService;
import com.doob.mathagent.teacher.service.TeacherSourceSyncJobService;
import com.doob.mathagent.teacher.sync.TeacherSourceSyncProperties;
import com.doob.mathagent.teacher.block.TeacherDocumentBlockStore;
import com.doob.mathagent.teacher.block.TeacherDocumentBlockResponse;
import com.doob.mathagent.teacher.vo.TeacherResourceAssetResponse;
import com.doob.mathagent.teacher.search.TeacherResourceBlockSearchResponse;
import com.doob.mathagent.teacher.document.TeacherResourceDocumentResponse;
import com.doob.mathagent.teacher.vo.TeacherSourceSyncCheckpointResponse;
import com.doob.mathagent.teacher.vo.TeacherSourceSyncJobResponse;
import com.doob.mathagent.vector.service.TestVectorIndexService;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import javax.imageio.ImageIO;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.server.ResponseStatusException;

class TeacherResourceControllerTest {

    @TempDir
    Path tempDir;

    private static TeacherSourceSyncExecutionService syncExecutionService(
            InMemoryTeacherResourceStore resourceStore,
            InMemoryTeacherSourceSyncJobStore jobStore,
            InMemoryTeacherDocumentBlockStore blockStore) {
        return syncExecutionService(
                resourceStore,
                jobStore,
                blockStore,
                new EmptyFeishuDownloadClient(),
                testSyncProperties(),
                new InMemoryTeacherSourceSyncCheckpointStore());
    }

    private static TeacherSourceSyncExecutionService syncExecutionService(
            InMemoryTeacherResourceStore resourceStore,
            InMemoryTeacherSourceSyncJobStore jobStore,
            InMemoryTeacherDocumentBlockStore blockStore,
            TeacherFeishuDownloadClient feishuDownloadClient,
            TeacherSourceSyncProperties syncProperties,
            InMemoryTeacherSourceSyncCheckpointStore checkpointStore) {
        return new TeacherSourceSyncExecutionService(
                resourceStore,
                jobStore,
                blockStore,
                feishuDownloadClient,
                syncProperties,
                checkpointStore,
                TestVectorIndexService.successful(resourceStore, blockStore));
    }

    private static TeacherResourceController controller(
            TeacherResourceService resourceService,
            TeacherSourceSyncJobService jobService,
            TeacherSourceSyncExecutionService executionService,
            RequestSubjectResolver subjectResolver) {
        return controller(
                resourceService,
                jobService,
                executionService,
                null,
                null,
                null,
                subjectResolver);
    }

    private static TeacherResourceController controller(
            TeacherResourceService resourceService,
            TeacherSourceSyncJobService jobService,
            TeacherSourceSyncExecutionService executionService,
            TeacherResourceBlockSearchService searchService,
            RequestSubjectResolver subjectResolver) {
        return controller(
                resourceService,
                jobService,
                executionService,
                searchService,
                null,
                null,
                subjectResolver);
    }

    private static TeacherResourceController controller(
            TeacherResourceService resourceService,
            TeacherSourceSyncJobService jobService,
            TeacherSourceSyncExecutionService executionService,
            TeacherResourceBlockSearchService searchService,
            TeacherDocumentBlockStore blockStore,
            RequestSubjectResolver subjectResolver) {
        return controller(
                resourceService,
                jobService,
                executionService,
                searchService,
                null,
                null,
                blockStore,
                subjectResolver);
    }

    private static TeacherResourceController controller(
            TeacherResourceService resourceService,
            TeacherSourceSyncJobService jobService,
            TeacherSourceSyncExecutionService executionService,
            TeacherResourceBlockSearchService searchService,
            TeacherResourceBlockSearchAuditLookup auditLookup,
            TeacherSourceSyncCheckpointQueryService checkpointQueryService,
            RequestSubjectResolver subjectResolver) {
        return controller(
                resourceService,
                jobService,
                executionService,
                searchService,
                auditLookup,
                checkpointQueryService,
                null,
                subjectResolver);
    }

    private static TeacherResourceController controller(
            TeacherResourceService resourceService,
            TeacherSourceSyncJobService jobService,
            TeacherSourceSyncExecutionService executionService,
            TeacherResourceBlockSearchService searchService,
            TeacherSourceSyncCheckpointQueryService checkpointQueryService,
            RequestSubjectResolver subjectResolver) {
        return controller(
                resourceService,
                jobService,
                executionService,
                searchService,
                null,
                checkpointQueryService,
                null,
                subjectResolver);
    }

    private static TeacherResourceController controller(
            TeacherResourceService resourceService,
            TeacherSourceSyncJobService jobService,
            TeacherSourceSyncExecutionService executionService,
            TeacherResourceBlockSearchService searchService,
            TeacherResourceBlockSearchAuditLookup auditLookup,
            TeacherSourceSyncCheckpointQueryService checkpointQueryService,
            TeacherDocumentBlockStore explicitBlockStore,
            RequestSubjectResolver subjectResolver) {
        return controller(
                resourceService,
                jobService,
                executionService,
                searchService,
                auditLookup,
                checkpointQueryService,
                explicitBlockStore,
                TeacherResourceUploadService.disabled(),
                subjectResolver);
    }

    private static TeacherResourceController controller(
            TeacherResourceService resourceService,
            TeacherSourceSyncJobService jobService,
            TeacherSourceSyncExecutionService executionService,
            TeacherResourceBlockSearchService searchService,
            TeacherResourceBlockSearchAuditLookup auditLookup,
            TeacherSourceSyncCheckpointQueryService checkpointQueryService,
            TeacherDocumentBlockStore explicitBlockStore,
            TeacherResourceUploadService uploadService,
            RequestSubjectResolver subjectResolver) {
        InMemoryTeacherResourceStore fallbackResourceStore = new InMemoryTeacherResourceStore();
        InMemoryTeacherDocumentBlockStore fallbackBlockStore = new InMemoryTeacherDocumentBlockStore();
        InMemoryTeacherSourceSyncJobStore fallbackJobStore = new InMemoryTeacherSourceSyncJobStore();
        InMemoryTeacherSourceSyncCheckpointStore fallbackCheckpointStore = new InMemoryTeacherSourceSyncCheckpointStore();
        RecentTeacherResourceBlockSearchAuditStore fallbackAuditStore = new RecentTeacherResourceBlockSearchAuditStore(20);
        return new TeacherResourceController(
                resourceService,
                jobService,
                executionService,
                searchService == null
                        ? com.doob.mathagent.teacher.TeacherResourceBlockSearchServiceFixture.service(
                                fallbackResourceStore, fallbackBlockStore)
                        : searchService,
                auditLookup == null ? fallbackAuditStore : auditLookup,
                checkpointQueryService == null
                        ? new TeacherSourceSyncCheckpointQueryService(
                                fallbackResourceStore, fallbackJobStore, fallbackCheckpointStore)
                        : checkpointQueryService,
                explicitBlockStore == null ? fallbackBlockStore : explicitBlockStore,
                TeacherResourceAssetService.disabled(),
                uploadService == null ? TeacherResourceUploadService.disabled() : uploadService,
                subjectResolver);
    }

    @Test
    void controllerBuildsTeacherContextFromBackendSubject() throws Exception {
        Path folder = tempDir.resolve("teacher-bank");
        Files.createDirectories(folder);
        Files.writeString(folder.resolve("function.md"), "# function");

        TeacherResourceController controller = controller(
                TeacherResourceServiceFixture.service(new InMemoryTeacherResourceStore()),
                new TeacherSourceSyncJobService(new InMemoryTeacherResourceStore(), new InMemoryTeacherSourceSyncJobStore()),
                syncExecutionService(new InMemoryTeacherResourceStore(), new InMemoryTeacherSourceSyncJobStore(), new InMemoryTeacherDocumentBlockStore()),
                request -> new RequestSubject("school-a", "teacher", "teacher-88", "device-1"));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Subject-Type", "admin");
        request.addHeader("X-Subject-Id", "teacher-spoofed");

        TeacherResourceDocumentResponse response = controller.register(new TeacherResourceRegistrationRequest(
                "local_path",
                "function local bank",
                null,
                folder.toString(),
                "MATH_VIP",
                null), request);

        assertThat(response.tenantId()).isEqualTo("school-a");
        assertThat(response.ownerSubjectId()).isEqualTo("teacher-88");
        assertThat(controller.list(request)).extracting(TeacherResourceDocumentResponse::title)
                .contains("function local bank");
    }

    @Test
    void teacherCannotSelfAssignPublicResourceScope() throws Exception {
        Path folder = tempDir.resolve("teacher-public-claim");
        Files.createDirectories(folder);
        Files.writeString(folder.resolve("function.md"), "# function");

        TeacherResourceController controller = controller(
                TeacherResourceServiceFixture.service(new InMemoryTeacherResourceStore()),
                new TeacherSourceSyncJobService(new InMemoryTeacherResourceStore(), new InMemoryTeacherSourceSyncJobStore()),
                syncExecutionService(new InMemoryTeacherResourceStore(), new InMemoryTeacherSourceSyncJobStore(), new InMemoryTeacherDocumentBlockStore()),
                request -> new RequestSubject("school-a", "teacher", "teacher-88", "device-1"));

        TeacherResourceDocumentResponse response = controller.register(new TeacherResourceRegistrationRequest(
                "local_path",
                "teacher public claim",
                null,
                folder.toString(),
                "PUBLIC_TEXTBOOK",
                null), new MockHttpServletRequest());

        assertThat(response.permissionScope()).isEqualTo("PUBLIC_TEXTBOOK");
    }

    @Test
    void uploadEndpointStoresFilesRegistersLocalResourceAndSyncsThroughExistingPipeline() throws Exception {
        InMemoryTeacherResourceStore resourceStore = new InMemoryTeacherResourceStore();
        InMemoryTeacherDocumentBlockStore blockStore = new InMemoryTeacherDocumentBlockStore();
        InMemoryTeacherSourceSyncJobStore jobStore = new InMemoryTeacherSourceSyncJobStore();
        InMemoryTeacherSourceSyncCheckpointStore checkpointStore = new InMemoryTeacherSourceSyncCheckpointStore();
        TeacherResourceService resourceService = TeacherResourceServiceFixture.service(resourceStore);
        TeacherSourceSyncJobService jobService = new TeacherSourceSyncJobService(resourceStore, jobStore);
        TeacherSourceSyncExecutionService executionService = syncExecutionService(
                resourceStore,
                jobStore,
                blockStore,
                new EmptyFeishuDownloadClient(),
                testSyncProperties(),
                checkpointStore);
        TeacherResourceUploadService uploadService = new TeacherResourceUploadService(
                new com.doob.mathagent.resources.ProjectResourceProperties(tempDir, tempDir, tempDir, tempDir, tempDir),
                java.time.Clock.systemUTC(),
                1024 * 1024,
                64);
        TeacherResourceController controller = controller(
                resourceService,
                jobService,
                executionService,
                null,
                null,
                new TeacherSourceSyncCheckpointQueryService(resourceStore, jobStore, checkpointStore),
                blockStore,
                uploadService,
                request -> new RequestSubject("school-a", "teacher", "teacher-88", "device-1"));
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockMultipartFile markdown = new MockMultipartFile(
                "files",
                "lesson/functions/monotonicity.md",
                "text/markdown",
                "# 函数单调性\n增函数与减函数判定。".getBytes(java.nio.charset.StandardCharsets.UTF_8));

        TeacherResourceDocumentResponse created = controller.uploadAndRegister(
                List.of(markdown),
                "qq_bundle",
                null,
                "PUBLIC_TEXTBOOK",
                "AI",
                request);

        assertThat(created.sourceType()).isEqualTo("teacher_resource");
        assertThat(created.title()).isEqualTo("lesson");
        assertThat(created.permissionScope()).isEqualTo("PUBLIC_TEXTBOOK");
        assertThat(created.parseMode()).isEqualTo("AI");
        assertThat(Path.of(created.localPath())).exists();
        assertThat(created.previewFiles()).extracting(TeacherResourceDocumentResponse.PreviewFile::relativePath)
                .contains("lesson/functions/monotonicity.md");

        TeacherSourceSyncJobResponse job = controller.createSyncJob(created.documentId(), request);
        TeacherSourceSyncJobResponse completed = controller.executeSyncJob(created.documentId(), job.jobId(), request);

        assertThat(completed.status()).isEqualTo("completed");
        assertThat(controller.listBlocks(created.documentId(), request))
                .extracting(TeacherDocumentBlockResponse::rawText)
                .anySatisfy(text -> assertThat(text).contains("增函数与减函数判定"));
    }

    @Test
    void uploadEndpointAcceptsActualMultipartFiles() {
        MockMultipartFile pdf = new MockMultipartFile(
                "files", "paper/mock-exam.pdf", "application/pdf", new byte[] {1, 2, 3, 4});
        TeacherResourceController controller = controller(
                TeacherResourceServiceFixture.service(new InMemoryTeacherResourceStore()),
                new TeacherSourceSyncJobService(new InMemoryTeacherResourceStore(), new InMemoryTeacherSourceSyncJobStore()),
                syncExecutionService(new InMemoryTeacherResourceStore(), new InMemoryTeacherSourceSyncJobStore(), new InMemoryTeacherDocumentBlockStore()),
                null,
                null,
                null,
                null,
                new TeacherResourceUploadService(
                        new com.doob.mathagent.resources.ProjectResourceProperties(tempDir, tempDir, tempDir, tempDir, tempDir),
                        java.time.Clock.systemUTC(), 1024 * 1024, 64),
                request -> new RequestSubject("school-a", "teacher", "teacher-88", "device-1"));

        TeacherResourceDocumentResponse response = controller.uploadAndRegister(
                List.of(pdf), "mock_exam", null, "MATH_VIP", "AI", new MockHttpServletRequest());

        assertThat(response.sourceType()).isEqualTo("teacher_resource");
    }

    @Test
    void uploadEndpointNormalizesLegacyFeishuSourceTypeToTeacherResource() {
        TeacherResourceController controller = controller(
                TeacherResourceServiceFixture.service(new InMemoryTeacherResourceStore()),
                new TeacherSourceSyncJobService(new InMemoryTeacherResourceStore(), new InMemoryTeacherSourceSyncJobStore()),
                syncExecutionService(new InMemoryTeacherResourceStore(), new InMemoryTeacherSourceSyncJobStore(), new InMemoryTeacherDocumentBlockStore()),
                null,
                null,
                null,
                null,
                new TeacherResourceUploadService(
                        new com.doob.mathagent.resources.ProjectResourceProperties(tempDir, tempDir, tempDir, tempDir, tempDir),
                        java.time.Clock.systemUTC(),
                        1024 * 1024,
                        64),
                request -> new RequestSubject("school-a", "teacher", "teacher-88", "device-1"));

        TeacherResourceDocumentResponse response = controller.uploadAndRegister(
                List.of(new MockMultipartFile("files", "function.md", "text/markdown", "# f".getBytes(java.nio.charset.StandardCharsets.UTF_8))),
                "feishu",
                "错误来源",
                "TEACHER_PRIVATE",
                "TEXT",
                new MockHttpServletRequest());

        assertThat(response.sourceType()).isEqualTo("teacher_resource");
    }

    @Test
    void controllerRegistersFeishuResourceWithSelectedExportFormat() {
        InMemoryTeacherResourceStore store = new InMemoryTeacherResourceStore();
        TeacherResourceController controller = controller(
                TeacherResourceServiceFixture.service(store),
                new TeacherSourceSyncJobService(store, new InMemoryTeacherSourceSyncJobStore()),
                syncExecutionService(store, new InMemoryTeacherSourceSyncJobStore(), new InMemoryTeacherDocumentBlockStore()),
                request -> new RequestSubject("school-a", "teacher", "teacher-88", "device-1"));

        TeacherResourceDocumentResponse response = controller.register(new TeacherResourceRegistrationRequest(
                "feishu",
                "Feishu PDF source",
                "https://example.feishu.cn/docx/doc1",
                null,
                "TEACHER_PRIVATE",
                "pdf"), new MockHttpServletRequest());

        assertThat(response.feishuExportFormat()).isEqualTo("pdf");
        assertThat(controller.list(new MockHttpServletRequest()))
                .extracting(TeacherResourceDocumentResponse::feishuExportFormat)
                .containsExactly("pdf");
    }

    @Test
    void adminCanAssignSharedResourceScope() throws Exception {
        Path folder = tempDir.resolve("admin-shared-resource");
        Files.createDirectories(folder);
        Files.writeString(folder.resolve("vector.md"), "# vector");

        TeacherResourceController controller = controller(
                TeacherResourceServiceFixture.service(new InMemoryTeacherResourceStore()),
                new TeacherSourceSyncJobService(new InMemoryTeacherResourceStore(), new InMemoryTeacherSourceSyncJobStore()),
                syncExecutionService(new InMemoryTeacherResourceStore(), new InMemoryTeacherSourceSyncJobStore(), new InMemoryTeacherDocumentBlockStore()),
                request -> new RequestSubject("school-a", "admin", "admin-1", "device-1"));

        TeacherResourceDocumentResponse response = controller.register(new TeacherResourceRegistrationRequest(
                "local_path",
                "admin shared resource",
                null,
                folder.toString(),
                "MATH_VIP",
                null), new MockHttpServletRequest());

        assertThat(response.permissionScope()).isEqualTo("MATH_VIP");
    }

    @Test
    void createsSyncJobWithBackendSubject() throws Exception {
        InMemoryTeacherResourceStore store = new InMemoryTeacherResourceStore();
        InMemoryTeacherSourceSyncJobStore jobStore = new InMemoryTeacherSourceSyncJobStore();
        TeacherResourceController controller = controller(
                TeacherResourceServiceFixture.service(store),
                new TeacherSourceSyncJobService(store, jobStore),
                syncExecutionService(store, jobStore, new InMemoryTeacherDocumentBlockStore()),
                request -> new RequestSubject("school-a", "teacher", "teacher-88", "device-1"));
        TeacherResourceDocumentResponse resource = controller.register(new TeacherResourceRegistrationRequest(
                "feishu",
                "Feishu question bank",
                "https://example.feishu.cn/docx/doc-token",
                null,
                "TEACHER_PRIVATE",
                "md"), new MockHttpServletRequest());

        TeacherSourceSyncJobResponse job = controller.createSyncJob(
                resource.documentId(),
                new MockHttpServletRequest());

        assertThat(job.documentId()).isEqualTo(resource.documentId());
        assertThat(job.operation()).isEqualTo("feishu_download");
        assertThat(controller.listSyncJobs(resource.documentId(), new MockHttpServletRequest()))
                .extracting(TeacherSourceSyncJobResponse::status)
                .containsExactly("queued");
    }

    @Test
    void executesLocalSyncJobWithBackendSubject() throws Exception {
        Path folder = tempDir.resolve("sync-execute-resource");
        Files.createDirectories(folder);
        Files.writeString(folder.resolve("vector.md"), "# Space vector\n\nA vector has magnitude and direction.");
        InMemoryTeacherResourceStore store = new InMemoryTeacherResourceStore();
        InMemoryTeacherSourceSyncJobStore jobStore = new InMemoryTeacherSourceSyncJobStore();
        InMemoryTeacherDocumentBlockStore blockStore = new InMemoryTeacherDocumentBlockStore();
        TeacherResourceController controller = controller(
                TeacherResourceServiceFixture.service(store),
                new TeacherSourceSyncJobService(store, jobStore),
                syncExecutionService(store, jobStore, blockStore),
                request -> new RequestSubject("school-a", "teacher", "teacher-88", "device-1"));
        TeacherResourceDocumentResponse resource = controller.register(new TeacherResourceRegistrationRequest(
                "local_path",
                "Executable local resource",
                null,
                folder.toString(),
                "TEACHER_PRIVATE",
                null), new MockHttpServletRequest());
        TeacherSourceSyncJobResponse queued = controller.createSyncJob(
                resource.documentId(),
                new MockHttpServletRequest());

        TeacherSourceSyncJobResponse completed = controller.executeSyncJob(
                resource.documentId(),
                queued.jobId(),
                new MockHttpServletRequest());

        assertThat(completed.status()).isEqualTo("completed");
        assertThat(completed.phase()).isEqualTo("parse_completed");
        assertThat(completed.message()).contains("Parsed 1 blocks");
        assertThat(store.find("school-a", resource.documentId()).syncStatus()).isEqualTo("synced");
    }

    @Test
    void resumesPausedFeishuSyncJobWithCheckpoint() {
        InMemoryTeacherResourceStore store = new InMemoryTeacherResourceStore();
        InMemoryTeacherSourceSyncJobStore jobStore = new InMemoryTeacherSourceSyncJobStore();
        InMemoryTeacherSourceSyncCheckpointStore checkpointStore = new InMemoryTeacherSourceSyncCheckpointStore();
        FailsOnceThenSucceedsFeishuClient feishuClient =
                new FailsOnceThenSucceedsFeishuClient(tempDir.resolve("feishu-resumed"));
        TeacherResourceController controller = controller(
                TeacherResourceServiceFixture.service(store),
                new TeacherSourceSyncJobService(store, jobStore),
                syncExecutionService(
                        store,
                        jobStore,
                        new InMemoryTeacherDocumentBlockStore(),
                        feishuClient,
                        testSyncProperties(),
                        checkpointStore),
                request -> new RequestSubject("school-a", "teacher", "teacher-88", "device-1"));
        TeacherResourceDocumentResponse resource = controller.register(new TeacherResourceRegistrationRequest(
                "feishu",
                "Retryable Feishu resource",
                "https://my.feishu.cn/drive/folder/XVn7fXppJlQMK5dkuOkc1ePan2f",
                null,
                "TEACHER_PRIVATE",
                "md"), new MockHttpServletRequest());
        TeacherSourceSyncJobResponse queued = controller.createSyncJob(
                resource.documentId(),
                new MockHttpServletRequest());
        TeacherSourceSyncJobResponse paused = controller.executeSyncJob(
                resource.documentId(),
                queued.jobId(),
                new MockHttpServletRequest());

        TeacherSourceSyncJobResponse resumed = controller.resumeSyncJob(
                resource.documentId(),
                queued.jobId(),
                new MockHttpServletRequest());

        assertThat(paused.status()).isEqualTo("paused");
        assertThat(paused.phase()).isEqualTo("download_paused");
        assertThat(resumed.status()).isEqualTo("completed");
        assertThat(resumed.phase()).isEqualTo("download_completed");
        assertThat(checkpointStore.findByJobId("school-a", queued.jobId()).orElseThrow().downloadedItemsJson())
                .contains("feishu-resumed");
    }

    @Test
    void readsSyncCheckpointForVisibleTeacherResource() {
        InMemoryTeacherResourceStore store = new InMemoryTeacherResourceStore();
        InMemoryTeacherSourceSyncJobStore jobStore = new InMemoryTeacherSourceSyncJobStore();
        InMemoryTeacherSourceSyncCheckpointStore checkpointStore = new InMemoryTeacherSourceSyncCheckpointStore();
        TeacherResourceController controller = controller(
                TeacherResourceServiceFixture.service(store),
                new TeacherSourceSyncJobService(store, jobStore),
                syncExecutionService(
                        store,
                        jobStore,
                        new InMemoryTeacherDocumentBlockStore(),
                        new FailsOnceThenSucceedsFeishuClient(tempDir.resolve("feishu-resumed")),
                        testSyncProperties(),
                        checkpointStore),
                null,
                new TeacherSourceSyncCheckpointQueryService(store, jobStore, checkpointStore),
                request -> new RequestSubject("school-a", "teacher", "teacher-88", "device-1"));
        TeacherResourceDocumentResponse resource = controller.register(new TeacherResourceRegistrationRequest(
                "feishu",
                "Checkpoint visible Feishu",
                "https://my.feishu.cn/drive/folder/rootToken",
                null,
                "TEACHER_PRIVATE",
                "md"), new MockHttpServletRequest());
        TeacherSourceSyncJobResponse queued = controller.createSyncJob(
                resource.documentId(),
                new MockHttpServletRequest());
        checkpointStore.save(new TeacherSourceSyncCheckpointResponse(
                queued.jobId(),
                "school-a",
                resource.documentId(),
                "rootToken",
                "folderToken-2",
                "妤傛ü鑵戦弫鏉款劅/缁屾椽妫块崥鎴﹀櫤",
                "pageToken-3",
                "[\"rootToken\",\"folderToken-2\"]",
                "[{\"token\":\"docx-1\"}]",
                "[{\"message\":\"ProxyError\",\"retryable\":true}]",
                2,
                "2026-06-30T06:00:00Z"));

        TeacherSourceSyncCheckpointResponse checkpoint = controller.getSyncCheckpoint(
                resource.documentId(),
                queued.jobId(),
                new MockHttpServletRequest()).orElseThrow();

        assertThat(checkpoint.currentPath()).isEqualTo("妤傛ü鑵戦弫鏉款劅/缁屾椽妫块崥鎴﹀櫤");
        assertThat(checkpoint.pageToken()).isEqualTo("pageToken-3");
        assertThat(checkpoint.downloadedItemsJson()).contains("docx-1");
        assertThat(checkpoint.failedItemsJson()).contains("ProxyError");
    }

    @Test
    void searchesParsedBlocksWithBackendSubjectAndIgnoresSpoofedHeaders() {
        InMemoryTeacherResourceStore store = new InMemoryTeacherResourceStore();
        InMemoryTeacherDocumentBlockStore blockStore = new InMemoryTeacherDocumentBlockStore();
        TeacherResourceDocumentResponse ownResource = store.save(new TeacherResourceDocumentResponse(
                "doc-own",
                "school-a",
                "teacher-88",
                "local_path",
                "Own parsed vectors",
                null,
                "C:/math/own",
                "TEACHER_PRIVATE",
                "synced",
                "parsed",
                "ready",
                "ready",
                java.util.List.of()));
        store.save(new TeacherResourceDocumentResponse(
                "doc-spoofed",
                "school-a",
                "teacher-spoofed",
                "local_path",
                "Spoofed teacher private vectors",
                null,
                "C:/math/spoofed",
                "TEACHER_PRIVATE",
                "synced",
                "parsed",
                "ready",
                "ready",
                java.util.List.of()));
        blockStore.replaceActiveBlocks("school-a", ownResource.documentId(), java.util.List.of(searchBlock(
                "block-own",
                ownResource.documentId(),
                "backend subject vector theorem")));
        blockStore.replaceActiveBlocks("school-a", "doc-spoofed", java.util.List.of(searchBlock(
                "block-spoofed",
                "doc-spoofed",
                "spoofed private vector theorem")));
        TeacherResourceController controller = controller(
                TeacherResourceServiceFixture.service(store),
                new TeacherSourceSyncJobService(store, new InMemoryTeacherSourceSyncJobStore()),
                syncExecutionService(store, new InMemoryTeacherSourceSyncJobStore(), blockStore),
                com.doob.mathagent.teacher.TeacherResourceBlockSearchServiceFixture.service(store, blockStore),
                request -> new RequestSubject("school-a", "teacher", "teacher-88", "device-1"));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Subject-Type", "teacher");
        request.addHeader("X-Subject-Id", "teacher-spoofed");

        TeacherResourceBlockSearchResponse response = controller.searchBlocks("vector theorem", 10, request);

        assertThat(response.hits()).extracting(TeacherResourceBlockSearchResponse.Hit::blockId)
                .containsExactly("block-own");
    }

    @Test
    void listsParsedBlocksOnlyForVisibleTeacherResource() {
        InMemoryTeacherResourceStore store = new InMemoryTeacherResourceStore();
        InMemoryTeacherDocumentBlockStore blockStore = new InMemoryTeacherDocumentBlockStore();
        store.save(new TeacherResourceDocumentResponse(
                "doc-own",
                "school-a",
                "teacher-88",
                "local_path",
                "Own parsed blocks",
                null,
                "C:/math/own",
                "TEACHER_PRIVATE",
                "synced",
                "parsed",
                "ready",
                "ready",
                java.util.List.of()));
        store.save(new TeacherResourceDocumentResponse(
                "doc-other",
                "school-a",
                "teacher-other",
                "local_path",
                "Other private blocks",
                null,
                "C:/math/other",
                "TEACHER_PRIVATE",
                "synced",
                "parsed",
                "ready",
                "ready",
                java.util.List.of()));
        blockStore.replaceActiveBlocks("school-a", "doc-own", java.util.List.of(searchBlock(
                "block-own",
                "doc-own",
                "strict recall source block")));
        blockStore.replaceActiveBlocks("school-a", "doc-other", java.util.List.of(searchBlock(
                "block-other",
                "doc-other",
                "hidden private block")));
        TeacherResourceController controller = controller(
                TeacherResourceServiceFixture.service(store, blockStore),
                new TeacherSourceSyncJobService(store, new InMemoryTeacherSourceSyncJobStore()),
                syncExecutionService(store, new InMemoryTeacherSourceSyncJobStore(), blockStore),
                com.doob.mathagent.teacher.TeacherResourceBlockSearchServiceFixture.service(store, blockStore),
                blockStore,
                request -> new RequestSubject("school-a", "teacher", "teacher-88", "device-1"));

        List<TeacherDocumentBlockResponse> blocks =
                controller.listBlocks("doc-own", new MockHttpServletRequest());

        assertThat(blocks).extracting(TeacherDocumentBlockResponse::blockId).containsExactly("block-own");
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        controller.listBlocks("doc-other", new MockHttpServletRequest()))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Teacher resource not found");
    }

    @Test
    void rejectsStudentTeacherBlockSearch() {
        InMemoryTeacherResourceStore store = new InMemoryTeacherResourceStore();
        InMemoryTeacherDocumentBlockStore blockStore = new InMemoryTeacherDocumentBlockStore();
        TeacherResourceController controller = controller(
                TeacherResourceServiceFixture.service(store),
                new TeacherSourceSyncJobService(store, new InMemoryTeacherSourceSyncJobStore()),
                syncExecutionService(store, new InMemoryTeacherSourceSyncJobStore(), blockStore),
                com.doob.mathagent.teacher.TeacherResourceBlockSearchServiceFixture.service(store, blockStore),
                request -> new RequestSubject("school-a", "student", "student-1", "device-1"));

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        controller.searchBlocks("vector theorem", 10, new MockHttpServletRequest()))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("teacher or admin");
    }

    @Test
    void searchesParsedBlocksWithScopeAndTagFilters() {
        InMemoryTeacherResourceStore store = new InMemoryTeacherResourceStore();
        InMemoryTeacherDocumentBlockStore blockStore = new InMemoryTeacherDocumentBlockStore();
        store.save(new TeacherResourceDocumentResponse(
                "doc-vip",
                "school-a",
                "teacher-88",
                "local_path",
                "VIP derivative notes",
                null,
                "C:/math/vip",
                "MATH_VIP",
                "synced",
                "parsed",
                "ready",
                "ready",
                java.util.List.of()));
        store.save(new TeacherResourceDocumentResponse(
                "doc-private",
                "school-a",
                "teacher-88",
                "local_path",
                "Private vector notes",
                null,
                "C:/math/private",
                "TEACHER_PRIVATE",
                "synced",
                "parsed",
                "ready",
                "ready",
                java.util.List.of()));
        blockStore.replaceActiveBlocks("school-a", "doc-vip", java.util.List.of(searchBlock(
                "block-vip",
                "doc-vip",
                "derivative monotonicity endpoint method")));
        blockStore.replaceActiveBlocks("school-a", "doc-private", java.util.List.of(searchBlock(
                "block-private",
                "doc-private",
                "vector normal line angle method")));
        TeacherResourceController controller = controller(
                TeacherResourceServiceFixture.service(store, blockStore),
                new TeacherSourceSyncJobService(store, new InMemoryTeacherSourceSyncJobStore()),
                syncExecutionService(store, new InMemoryTeacherSourceSyncJobStore(), blockStore),
                com.doob.mathagent.teacher.TeacherResourceBlockSearchServiceFixture.service(store, blockStore),
                request -> new RequestSubject("school-a", "teacher", "teacher-88", "device-1"));

        TeacherResourceBlockSearchResponse response = controller.searchBlocks(
                "method",
                10,
                java.util.List.of("MATH_VIP"),
                null,
                null,
                java.util.List.of("derivative"),
                new MockHttpServletRequest());

        assertThat(response.retrievalMode()).isEqualTo("two_stage_doc_block_filtered");
        assertThat(response.hits()).extracting(TeacherResourceBlockSearchResponse.Hit::blockId)
                .containsExactly("block-vip");
    }

    @Test
    void searchAcceptsLibraryAliasAndMatchesLogicalLibrarySelectors() {
        InMemoryTeacherResourceStore store = new InMemoryTeacherResourceStore();
        InMemoryTeacherDocumentBlockStore blockStore = new InMemoryTeacherDocumentBlockStore();
        store.save(new TeacherResourceDocumentResponse(
                "doc-qq",
                "school-a",
                "teacher-88",
                "local_path",
                "Runtime QQ bundle package",
                null,
                "C:/workspace/teacher-materials/02-qq-bundle-vector",
                "MATH_VIP",
                "synced",
                "parsed",
                "ready",
                "ready",
                java.util.List.of()));
        store.save(new TeacherResourceDocumentResponse(
                "doc-feishu",
                "school-a",
                "teacher-88",
                "local_path",
                "Runtime Feishu method package",
                null,
                "C:/workspace/teacher-materials/03-feishu-method-probability",
                "TEACHER_PRIVATE",
                "synced",
                "parsed",
                "ready",
                "ready",
                java.util.List.of()));
        blockStore.replaceActiveBlocks("school-a", "doc-qq", java.util.List.of(searchBlock(
                "block-qq",
                "doc-qq",
                "vector angle bundle analysis and lesson summary")));
        blockStore.replaceActiveBlocks("school-a", "doc-feishu", java.util.List.of(searchBlock(
                "block-feishu",
                "doc-feishu",
                "probability method template and classroom reminder")));
        TeacherResourceController controller = controller(
                TeacherResourceServiceFixture.service(store, blockStore),
                new TeacherSourceSyncJobService(store, new InMemoryTeacherSourceSyncJobStore()),
                syncExecutionService(store, new InMemoryTeacherSourceSyncJobStore(), blockStore),
                com.doob.mathagent.teacher.TeacherResourceBlockSearchServiceFixture.service(store, blockStore),
                request -> new RequestSubject("school-a", "teacher", "teacher-88", "device-1"));

        TeacherResourceBlockSearchResponse response = controller.searchBlocks(
                "vector angle analysis",
                10,
                null,
                null,
                java.util.List.of("qq_bundle"),
                null,
                new MockHttpServletRequest());

        assertThat(response.retrievalMode()).isEqualTo("two_stage_doc_block_filtered");
        assertThat(response.hits()).extracting(TeacherResourceBlockSearchResponse.Hit::documentId)
                .containsExactly("doc-qq");
        assertThat(response.hits().getFirst().sourceType()).isEqualTo("qq_bundle");
    }

    @Test
    void teacherResourceLibraryAliasExcludesSpecializedLocalPathLibraries() {
        InMemoryTeacherResourceStore store = new InMemoryTeacherResourceStore();
        InMemoryTeacherDocumentBlockStore blockStore = new InMemoryTeacherDocumentBlockStore();
        store.save(new TeacherResourceDocumentResponse(
                "doc-generic",
                "school-a",
                "teacher-88",
                "local_path",
                "Teacher owned vector notes",
                null,
                "C:/workspace/teacher-materials/space-vector-handout",
                "MATH_VIP",
                "synced",
                "parsed",
                "ready",
                "ready",
                java.util.List.of()));
        store.save(new TeacherResourceDocumentResponse(
                "doc-qq",
                "school-a",
                "teacher-88",
                "local_path",
                "Runtime QQ bundle package",
                null,
                "C:/workspace/teacher-materials/02-qq-bundle-vector",
                "MATH_VIP",
                "synced",
                "parsed",
                "ready",
                "ready",
                java.util.List.of()));
        store.save(new TeacherResourceDocumentResponse(
                "doc-feishu",
                "school-a",
                "teacher-88",
                "local_path",
                "Runtime Feishu method package",
                null,
                "C:/workspace/teacher-materials/03-feishu-method-probability",
                "TEACHER_PRIVATE",
                "synced",
                "parsed",
                "ready",
                "ready",
                java.util.List.of()));
        blockStore.replaceActiveBlocks("school-a", "doc-generic", java.util.List.of(searchBlock(
                "block-generic",
                "doc-generic",
                "space vector teacher notes and generic classroom handout")));
        blockStore.replaceActiveBlocks("school-a", "doc-qq", java.util.List.of(searchBlock(
                "block-qq",
                "doc-qq",
                "vector angle bundle analysis and lesson summary")));
        blockStore.replaceActiveBlocks("school-a", "doc-feishu", java.util.List.of(searchBlock(
                "block-feishu",
                "doc-feishu",
                "probability method template and classroom reminder")));
        TeacherResourceController controller = controller(
                TeacherResourceServiceFixture.service(store, blockStore),
                new TeacherSourceSyncJobService(store, new InMemoryTeacherSourceSyncJobStore()),
                syncExecutionService(store, new InMemoryTeacherSourceSyncJobStore(), blockStore),
                com.doob.mathagent.teacher.TeacherResourceBlockSearchServiceFixture.service(store, blockStore),
                request -> new RequestSubject("school-a", "teacher", "teacher-88", "device-1"));

        TeacherResourceBlockSearchResponse response = controller.searchBlocks(
                "space vector handout notes",
                10,
                null,
                null,
                java.util.List.of("teacher_resource"),
                null,
                new MockHttpServletRequest());

        assertThat(response.retrievalMode()).isEqualTo("two_stage_doc_block_filtered");
        assertThat(response.hits()).extracting(TeacherResourceBlockSearchResponse.Hit::documentId)
                .containsExactly("doc-generic");
        assertThat(response.hits()).extracting(TeacherResourceBlockSearchResponse.Hit::sourceType)
                .containsExactly("teacher_resource");
    }

    @Test
    void ranksMatchingSectionBlockAheadOfSiblingBlock() {
        InMemoryTeacherResourceStore store = new InMemoryTeacherResourceStore();
        InMemoryTeacherDocumentBlockStore blockStore = new InMemoryTeacherDocumentBlockStore();
        store.save(new TeacherResourceDocumentResponse(
                "doc-derivative",
                "school-a",
                "teacher-88",
                "local_path",
                "导数参数讨论讲义",
                null,
                "C:/math/derivative",
                "MATH_VIP",
                "synced",
                "parsed",
                "ready",
                "ready",
                java.util.List.of()));
        blockStore.replaceActiveBlocks("school-a", "doc-derivative", java.util.List.of(
                searchBlockWithSection(
                        "block-sign",
                        "doc-derivative",
                        "导数参数讨论",
                        "符号表",
                        "讲符号表时要结合区间端点讨论单调性变化"),
                searchBlockWithSection(
                        "block-method",
                        "doc-derivative",
                        "导数参数讨论",
                        "方法总览",
                        "先说明为什么不能只看导数零点，再回到原函数增减变化")));
        TeacherResourceController controller = controller(
                TeacherResourceServiceFixture.service(store, blockStore),
                new TeacherSourceSyncJobService(store, new InMemoryTeacherSourceSyncJobStore()),
                syncExecutionService(store, new InMemoryTeacherSourceSyncJobStore(), blockStore),
                com.doob.mathagent.teacher.TeacherResourceBlockSearchServiceFixture.service(store, blockStore),
                request -> new RequestSubject("school-a", "teacher", "teacher-88", "device-1"));

        TeacherResourceBlockSearchResponse response = controller.searchBlocks(
                "导数参数讨论 符号表 区间端点 单调性",
                10,
                new MockHttpServletRequest());

        assertThat(response.hits()).isNotEmpty();
        assertThat(response.hits().getFirst().blockId()).isEqualTo("block-sign");
    }

    @Test
    void diversifiesSiblingBlocksSoAnotherRelevantDocumentCanReachTopResults() {
        InMemoryTeacherResourceStore store = new InMemoryTeacherResourceStore();
        InMemoryTeacherDocumentBlockStore blockStore = new InMemoryTeacherDocumentBlockStore();
        store.save(new TeacherResourceDocumentResponse(
                "doc-noisy",
                "school-a",
                "teacher-88",
                "local_path",
                "Derivative noise bundle",
                null,
                "C:/math/noisy",
                "MATH_VIP",
                "synced",
                "parsed",
                "ready",
                "ready",
                java.util.List.of()));
        store.save(new TeacherResourceDocumentResponse(
                "doc-target",
                "school-a",
                "teacher-88",
                "local_path",
                "Vector angle guide",
                null,
                "C:/math/target",
                "MATH_VIP",
                "synced",
                "parsed",
                "ready",
                "ready",
                java.util.List.of()));
        blockStore.replaceActiveBlocks("school-a", "doc-noisy", java.util.List.of(
                searchBlock("block-noisy-1", "doc-noisy", "vector angle method with repeated generic theorem words"),
                searchBlock("block-noisy-2", "doc-noisy", "vector angle method with repeated generic endpoint words"),
                searchBlock("block-noisy-3", "doc-noisy", "vector angle method with repeated generic summary words")));
        blockStore.replaceActiveBlocks("school-a", "doc-target", java.util.List.of(
                searchBlockWithSection(
                        "block-target",
                        "doc-target",
                        "Space vector",
                        "Line-plane angle",
                        "vector angle method explains direction vector then normal vector to convert the angle")));
        TeacherResourceController controller = controller(
                TeacherResourceServiceFixture.service(store, blockStore),
                new TeacherSourceSyncJobService(store, new InMemoryTeacherSourceSyncJobStore()),
                syncExecutionService(store, new InMemoryTeacherSourceSyncJobStore(), blockStore),
                com.doob.mathagent.teacher.TeacherResourceBlockSearchServiceFixture.service(store, blockStore),
                request -> new RequestSubject("school-a", "teacher", "teacher-88", "device-1"));

        TeacherResourceBlockSearchResponse response = controller.searchBlocks(
                "vector angle method normal vector",
                3,
                new MockHttpServletRequest());

        assertThat(response.hits()).hasSize(3);
        assertThat(response.hits().stream()
                .limit(2)
                .map(TeacherResourceBlockSearchResponse.Hit::documentId)
                .distinct()
                .count()).isEqualTo(2);
        assertThat(response.hits().subList(0, 2))
                .extracting(TeacherResourceBlockSearchResponse.Hit::documentId)
                .contains("doc-target");
        assertThat(response.hits().subList(0, 2))
                .extracting(TeacherResourceBlockSearchResponse.Hit::blockId)
                .contains("block-target");
    }

    @Test
    void returnsTeacherBlockSearchAuditOnlyToOwningTeacherOrAdmin() {
        InMemoryTeacherResourceStore store = new InMemoryTeacherResourceStore();
        InMemoryTeacherDocumentBlockStore blockStore = new InMemoryTeacherDocumentBlockStore();
        RecentTeacherResourceBlockSearchAuditStore auditStore = new RecentTeacherResourceBlockSearchAuditStore(10);
        TeacherResourceDocumentResponse ownResource = new TeacherResourceDocumentResponse(
                "doc-own",
                "school-a",
                "teacher-88",
                "local_path",
                "Own parsed vectors",
                null,
                "C:/math/own",
                "TEACHER_PRIVATE",
                "synced",
                "parsed",
                "ready",
                "ready",
                java.util.List.of());
        store.save(ownResource);
        blockStore.replaceActiveBlocks("school-a", ownResource.documentId(), java.util.List.of(searchBlock(
                "block-own",
                ownResource.documentId(),
                "backend subject vector theorem")));
        TeacherResourceBlockSearchService searchService = new TeacherResourceBlockSearchService(
                store,
                blockStore,
                auditStore,
                TestVectorIndexService.successful(store, blockStore));
        TeacherResourceController ownerController = controller(
                TeacherResourceServiceFixture.service(store),
                new TeacherSourceSyncJobService(store, new InMemoryTeacherSourceSyncJobStore()),
                syncExecutionService(store, new InMemoryTeacherSourceSyncJobStore(), blockStore),
                searchService,
                auditStore,
                null,
                request -> new RequestSubject("school-a", "teacher", "teacher-88", "device-1"));

        TeacherResourceBlockSearchResponse response =
                ownerController.searchBlocks("vector theorem", 10, new MockHttpServletRequest());
        TeacherResourceBlockSearchAuditEvent event =
                ownerController.searchAudit(response.queryId(), new MockHttpServletRequest());

        assertThat(event.queryId()).isEqualTo(response.queryId());
        assertThat(event.subjectId()).isEqualTo("teacher-88");
        assertThat(event.hits()).extracting(TeacherResourceBlockSearchAuditEvent.Hit::blockId)
                .containsExactly("block-own");

        TeacherResourceController otherTeacherController = controller(
                TeacherResourceServiceFixture.service(store),
                new TeacherSourceSyncJobService(store, new InMemoryTeacherSourceSyncJobStore()),
                syncExecutionService(store, new InMemoryTeacherSourceSyncJobStore(), blockStore),
                searchService,
                auditStore,
                null,
                request -> new RequestSubject("school-a", "teacher", "teacher-other", "device-2"));
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        otherTeacherController.searchAudit(response.queryId(), new MockHttpServletRequest()))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("404");
    }

    private static final class EmptyFeishuDownloadClient implements TeacherFeishuDownloadClient {

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
    }

    private static final class FailsOnceThenSucceedsFeishuClient implements TeacherFeishuDownloadClient {

        private final Path savedPath;
        private int calls;

        private FailsOnceThenSucceedsFeishuClient(Path savedPath) {
            this.savedPath = savedPath;
        }

        @Override
        public FeishuDownloadResult download(
                String url,
                Path stagingRoot,
                int maxFiles,
                String fileExtension,
                FeishuDownloadCheckpoint checkpoint) {
            calls += 1;
            if (calls == 1) {
                throw new TeacherFeishuDownloadException("ProxyError: proxy connection reset", true);
            }
            try {
                Files.createDirectories(savedPath);
                Files.writeString(savedPath.resolve("resume-result.txt"), "Feishu resume downloaded text");
            } catch (java.io.IOException exception) {
                throw new IllegalStateException("Failed to create resumed Feishu fixture", exception);
            }
            return new FeishuDownloadResult(
                    savedPath,
                    1,
                    0,
                    0,
                    "Downloaded 1 Feishu files after resume",
                    checkpoint == null ? FeishuDownloadCheckpoint.empty() : checkpoint,
                    checkpoint == null ? "[]" : checkpoint.downloadedItemsJson(),
                    "[]",
                    "Feishu resumed resource",
                    "resume-v1",
                    "[{\"name\":\"resume-result.txt\"}]",
                    "[{\"name\":\"resume-result.txt\"}]",
                    "[]");
        }
    }

    /**
     * Explicit Feishu sync fixture config. Production code must not expose a default Feishu URL.
     */
    private static TeacherSourceSyncProperties testSyncProperties() {
        Path root = Path.of(System.getProperty("java.io.tmpdir")).resolve("math-agent-teacher-controller-test");
        return new TeacherSourceSyncProperties(
                "",
                Path.of("ai-worker-python/scripts/download_feishu_url.py"),
                root.resolve("APPKEY.md"),
                root.resolve("feishu-staging"),
                1,
                30);
    }

    @Test
    void searchReturnsVisibleAssetRefsForTeacherOwnedBlocks() throws Exception {
        InMemoryTeacherResourceStore store = new InMemoryTeacherResourceStore();
        InMemoryTeacherDocumentBlockStore blockStore = new InMemoryTeacherDocumentBlockStore();
        InMemoryTeacherResourceAssetStore assetStore = new InMemoryTeacherResourceAssetStore();
        RecentTeacherResourceBlockSearchAuditStore auditStore = new RecentTeacherResourceBlockSearchAuditStore(20);
        TeacherResourceDocumentResponse ownResource = new TeacherResourceDocumentResponse(
                "doc-own-assets",
                "school-a",
                "teacher-88",
                "local_path",
                "Own diagram bank",
                null,
                "C:/math/own",
                "TEACHER_PRIVATE",
                "synced",
                "parsed",
                "ready",
                "ready",
                List.of());
        store.save(ownResource);
        TeacherResourceAssetService assetService = new TeacherResourceAssetService(assetStore, store, testSyncProperties());
        TeacherResourceAssetResponse asset = assetService.saveExtractedAsset(
                ownResource,
                "diagram-note.docx",
                null,
                "/word/media/image1.png",
                pngBytes(),
                "image/png").orElseThrow();
        blockStore.replaceActiveBlocks("school-a", ownResource.documentId(), List.of(searchBlockWithImageRef(
                "block-own-assets",
                ownResource.documentId(),
                "Diagram note before image",
                asset.assetId())));
        TeacherResourceBlockSearchService searchService = new TeacherResourceBlockSearchService(
                store,
                blockStore,
                auditStore,
                TestVectorIndexService.successful(store, blockStore),
                TeacherResourceGraphAlignmentService.disabled(),
                assetService);
        TeacherResourceController controller = controller(
                TeacherResourceServiceFixture.service(store),
                new TeacherSourceSyncJobService(store, new InMemoryTeacherSourceSyncJobStore()),
                syncExecutionService(store, new InMemoryTeacherSourceSyncJobStore(), blockStore),
                searchService,
                auditStore,
                null,
                request -> new RequestSubject("school-a", "teacher", "teacher-88", "device-1"));

        TeacherResourceBlockSearchResponse response =
                controller.searchBlocks("diagram note", 10, new MockHttpServletRequest());

        assertThat(response.hits())
                .singleElement()
                .satisfies(hit -> {
                    assertThat(hit.imageAssetIds()).containsExactly(asset.assetId());
                    assertThat(hit.assetRefs())
                            .singleElement()
                            .satisfies(assetRef -> {
                                assertThat(assetRef.assetId()).isEqualTo(asset.assetId());
                                assertThat(assetRef.assetUri()).isEqualTo("/api/teacher/resources/assets/" + asset.assetId());
                                assertThat(assetRef.mimeType()).isEqualTo("image/png");
                            });
                });
    }

    /**
     * Builds a parsed text block for controller-level search assertions.
     */
    private static TeacherDocumentBlockResponse searchBlock(String blockId, String documentId, String text) {
        return new TeacherDocumentBlockResponse(
                blockId,
                documentId,
                documentId + ":1",
                "text",
                1,
                "Vectors",
                "Theorem",
                null,
                null,
                text,
                text,
                "[]",
                "[]",
                blockId + "-checksum",
                1.0,
                "active");
    }

    private static TeacherDocumentBlockResponse searchBlockWithSection(
            String blockId,
            String documentId,
            String chapter,
            String section,
            String text) {
        return new TeacherDocumentBlockResponse(
                blockId,
                documentId,
                documentId + ":" + blockId,
                "text",
                1,
                chapter,
                section,
                null,
                null,
                text,
                text,
                "[]",
                "[]",
                blockId + "-checksum",
                1.0,
                "active");
    }

    private static TeacherDocumentBlockResponse searchBlockWithImageRef(
            String blockId,
            String documentId,
            String text,
            String assetId) {
        return new TeacherDocumentBlockResponse(
                blockId,
                documentId,
                documentId + ":" + blockId,
                "text",
                1,
                "Vectors",
                "Diagram",
                null,
                null,
                text,
                text,
                "[{\"assetId\":\"" + assetId + "\"}]",
                "[]",
                blockId + "-checksum",
                1.0,
                "active");
    }

    private static byte[] pngBytes() throws Exception {
        BufferedImage image = new BufferedImage(8, 6, BufferedImage.TYPE_INT_RGB);
        image.setRGB(0, 0, Color.BLUE.getRGB());
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(image, "png", output);
        return output.toByteArray();
    }
}


