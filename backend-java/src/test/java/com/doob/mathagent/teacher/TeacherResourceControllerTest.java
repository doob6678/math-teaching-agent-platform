package com.doob.mathagent.teacher;

import static org.assertj.core.api.Assertions.assertThat;

import com.doob.mathagent.infrastructure.security.RequestSubject;
import com.doob.mathagent.teacher.controller.TeacherResourceController;
import com.doob.mathagent.teacher.dto.TeacherResourceRegistrationRequest;
import com.doob.mathagent.teacher.service.InMemoryTeacherDocumentBlockStore;
import com.doob.mathagent.teacher.service.InMemoryTeacherResourceStore;
import com.doob.mathagent.teacher.service.InMemoryTeacherSourceSyncCheckpointStore;
import com.doob.mathagent.teacher.service.InMemoryTeacherSourceSyncJobStore;
import com.doob.mathagent.teacher.service.TeacherFeishuDownloadClient;
import com.doob.mathagent.teacher.service.TeacherFeishuDownloadException;
import com.doob.mathagent.teacher.service.TeacherResourceService;
import com.doob.mathagent.teacher.service.TeacherSourceSyncExecutionService;
import com.doob.mathagent.teacher.service.TeacherSourceSyncJobService;
import com.doob.mathagent.teacher.service.TeacherSourceSyncProperties;
import com.doob.mathagent.teacher.vo.TeacherResourceDocumentResponse;
import com.doob.mathagent.teacher.vo.TeacherSourceSyncJobResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.server.ResponseStatusException;

class TeacherResourceControllerTest {

    @TempDir
    Path tempDir;

    @Test
    void controllerBuildsTeacherContextFromBackendSubject() throws Exception {
        Path folder = tempDir.resolve("teacher-bank");
        Files.createDirectories(folder);
        Files.writeString(folder.resolve("function.md"), "# function");

        TeacherResourceController controller = new TeacherResourceController(
                new TeacherResourceService(new InMemoryTeacherResourceStore()),
                new TeacherSourceSyncJobService(new InMemoryTeacherResourceStore(), new InMemoryTeacherSourceSyncJobStore()),
                new TeacherSourceSyncExecutionService(
                        new InMemoryTeacherResourceStore(),
                        new InMemoryTeacherSourceSyncJobStore(),
                        new InMemoryTeacherDocumentBlockStore()),
                request -> new RequestSubject("school-a", "teacher", "teacher-88", "device-1"),
                (token, action, path, requestHash, subject) -> true);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Subject-Type", "admin");
        request.addHeader("X-Subject-Id", "teacher-spoofed");

        TeacherResourceDocumentResponse response = controller.register(new TeacherResourceRegistrationRequest(
                "local_path",
                "function local bank",
                null,
                folder.toString(),
                "MATH_VIP"), request);

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

        TeacherResourceController controller = new TeacherResourceController(
                new TeacherResourceService(new InMemoryTeacherResourceStore()),
                new TeacherSourceSyncJobService(new InMemoryTeacherResourceStore(), new InMemoryTeacherSourceSyncJobStore()),
                new TeacherSourceSyncExecutionService(
                        new InMemoryTeacherResourceStore(),
                        new InMemoryTeacherSourceSyncJobStore(),
                        new InMemoryTeacherDocumentBlockStore()),
                request -> new RequestSubject("school-a", "teacher", "teacher-88", "device-1"),
                (token, action, path, requestHash, subject) -> true);

        TeacherResourceDocumentResponse response = controller.register(new TeacherResourceRegistrationRequest(
                "local_path",
                "teacher public claim",
                null,
                folder.toString(),
                "PUBLIC_TEXTBOOK"), new MockHttpServletRequest());

        assertThat(response.permissionScope()).isEqualTo("TEACHER_PRIVATE");
    }

    @Test
    void adminCanAssignSharedResourceScope() throws Exception {
        Path folder = tempDir.resolve("admin-shared-resource");
        Files.createDirectories(folder);
        Files.writeString(folder.resolve("vector.md"), "# vector");

        TeacherResourceController controller = new TeacherResourceController(
                new TeacherResourceService(new InMemoryTeacherResourceStore()),
                new TeacherSourceSyncJobService(new InMemoryTeacherResourceStore(), new InMemoryTeacherSourceSyncJobStore()),
                new TeacherSourceSyncExecutionService(
                        new InMemoryTeacherResourceStore(),
                        new InMemoryTeacherSourceSyncJobStore(),
                        new InMemoryTeacherDocumentBlockStore()),
                request -> new RequestSubject("school-a", "admin", "admin-1", "device-1"),
                (token, action, path, requestHash, subject) -> true);

        TeacherResourceDocumentResponse response = controller.register(new TeacherResourceRegistrationRequest(
                "local_path",
                "admin shared resource",
                null,
                folder.toString(),
                "MATH_VIP"), new MockHttpServletRequest());

        assertThat(response.permissionScope()).isEqualTo("MATH_VIP");
    }

    @Test
    void rejectsRegisterAndArchiveWithoutAcceptedCapabilityToken() throws Exception {
        Path folder = tempDir.resolve("protected-resource");
        Files.createDirectories(folder);
        Files.writeString(folder.resolve("vector.md"), "# vector");
        InMemoryTeacherResourceStore store = new InMemoryTeacherResourceStore();
        TeacherResourceController setupController = new TeacherResourceController(
                new TeacherResourceService(store),
                new TeacherSourceSyncJobService(store, new InMemoryTeacherSourceSyncJobStore()),
                new TeacherSourceSyncExecutionService(
                        store,
                        new InMemoryTeacherSourceSyncJobStore(),
                        new InMemoryTeacherDocumentBlockStore()),
                request -> new RequestSubject("school-a", "teacher", "teacher-88", "device-1"),
                (token, action, path, requestHash, subject) -> true);
        TeacherResourceDocumentResponse created = setupController.register(new TeacherResourceRegistrationRequest(
                "local_path",
                "protected resource",
                null,
                folder.toString(),
                "TEACHER_PRIVATE"), requestWithCapability("token-ok", "hash-ok"));
        TeacherResourceController protectedController = new TeacherResourceController(
                new TeacherResourceService(store),
                new TeacherSourceSyncJobService(store, new InMemoryTeacherSourceSyncJobStore()),
                new TeacherSourceSyncExecutionService(
                        store,
                        new InMemoryTeacherSourceSyncJobStore(),
                        new InMemoryTeacherDocumentBlockStore()),
                request -> new RequestSubject("school-a", "teacher", "teacher-88", "device-1"),
                (token, action, path, requestHash, subject) -> false);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> protectedController.register(
                        new TeacherResourceRegistrationRequest(
                                "local_path",
                                "blocked resource",
                                null,
                                folder.toString(),
                                "TEACHER_PRIVATE"),
                        requestWithCapability("bad-token", "hash-register")))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Capability token");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> protectedController.archive(
                        created.documentId(),
                        requestWithCapability("bad-token", "hash-archive")))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Capability token");
    }

    @Test
    void createsSyncJobWithCapabilityTokenAndBackendSubject() throws Exception {
        InMemoryTeacherResourceStore store = new InMemoryTeacherResourceStore();
        InMemoryTeacherSourceSyncJobStore jobStore = new InMemoryTeacherSourceSyncJobStore();
        TeacherResourceController controller = new TeacherResourceController(
                new TeacherResourceService(store),
                new TeacherSourceSyncJobService(store, jobStore),
                new TeacherSourceSyncExecutionService(
                        store,
                        jobStore,
                        new InMemoryTeacherDocumentBlockStore()),
                request -> new RequestSubject("school-a", "teacher", "teacher-88", "device-1"),
                (token, action, path, requestHash, subject) ->
                        (("teacher-resource:register".equals(action) && "/api/teacher/resources".equals(path))
                                || ("teacher-resource:sync".equals(action) && path.endsWith("/sync-jobs")))
                                && "teacher-88".equals(subject.normalize().subjectId()));
        TeacherResourceDocumentResponse resource = controller.register(new TeacherResourceRegistrationRequest(
                "feishu",
                "Feishu question bank",
                "https://example.feishu.cn/docx/doc-token",
                null,
                "TEACHER_PRIVATE"), requestWithCapability("token-ok", "hash-register"));

        TeacherSourceSyncJobResponse job = controller.createSyncJob(
                resource.documentId(),
                requestWithCapability("token-ok", "hash-empty"));

        assertThat(job.documentId()).isEqualTo(resource.documentId());
        assertThat(job.operation()).isEqualTo("feishu_download");
        assertThat(controller.listSyncJobs(resource.documentId(), new MockHttpServletRequest()))
                .extracting(TeacherSourceSyncJobResponse::status)
                .containsExactly("queued");
    }

    @Test
    void executesLocalSyncJobWithCapabilityTokenAndBackendSubject() throws Exception {
        Path folder = tempDir.resolve("sync-execute-resource");
        Files.createDirectories(folder);
        Files.writeString(folder.resolve("vector.md"), "# Space vector\n\nA vector has magnitude and direction.");
        InMemoryTeacherResourceStore store = new InMemoryTeacherResourceStore();
        InMemoryTeacherSourceSyncJobStore jobStore = new InMemoryTeacherSourceSyncJobStore();
        InMemoryTeacherDocumentBlockStore blockStore = new InMemoryTeacherDocumentBlockStore();
        TeacherResourceController controller = new TeacherResourceController(
                new TeacherResourceService(store),
                new TeacherSourceSyncJobService(store, jobStore),
                new TeacherSourceSyncExecutionService(store, jobStore, blockStore),
                request -> new RequestSubject("school-a", "teacher", "teacher-88", "device-1"),
                (token, action, path, requestHash, subject) ->
                        (("teacher-resource:register".equals(action) && "/api/teacher/resources".equals(path))
                                || ("teacher-resource:sync".equals(action) && path.endsWith("/sync-jobs"))
                                || ("teacher-resource:sync-execute".equals(action) && path.endsWith("/execute")))
                                && "teacher-88".equals(subject.normalize().subjectId()));
        TeacherResourceDocumentResponse resource = controller.register(new TeacherResourceRegistrationRequest(
                "local_path",
                "Executable local resource",
                null,
                folder.toString(),
                "TEACHER_PRIVATE"), requestWithCapability("token-ok", "hash-register"));
        TeacherSourceSyncJobResponse queued = controller.createSyncJob(
                resource.documentId(),
                requestWithCapability("token-ok", "hash-sync"));

        TeacherSourceSyncJobResponse completed = controller.executeSyncJob(
                resource.documentId(),
                queued.jobId(),
                requestWithCapability("token-ok", "hash-execute"));

        assertThat(completed.status()).isEqualTo("completed");
        assertThat(completed.phase()).isEqualTo("parse_completed");
        assertThat(completed.message()).contains("Parsed 1 blocks");
        assertThat(store.find("school-a", resource.documentId()).syncStatus()).isEqualTo("synced");
    }

    @Test
    void resumesPausedFeishuSyncJobWithCapabilityTokenAndCheckpoint() {
        InMemoryTeacherResourceStore store = new InMemoryTeacherResourceStore();
        InMemoryTeacherSourceSyncJobStore jobStore = new InMemoryTeacherSourceSyncJobStore();
        InMemoryTeacherSourceSyncCheckpointStore checkpointStore = new InMemoryTeacherSourceSyncCheckpointStore();
        FailsOnceThenSucceedsFeishuClient feishuClient =
                new FailsOnceThenSucceedsFeishuClient(tempDir.resolve("feishu-resumed"));
        TeacherResourceController controller = new TeacherResourceController(
                new TeacherResourceService(store),
                new TeacherSourceSyncJobService(store, jobStore),
                new TeacherSourceSyncExecutionService(
                        store,
                        jobStore,
                        new InMemoryTeacherDocumentBlockStore(),
                        feishuClient,
                        TeacherSourceSyncProperties.defaults(),
                        checkpointStore),
                request -> new RequestSubject("school-a", "teacher", "teacher-88", "device-1"),
                (token, action, path, requestHash, subject) ->
                        (("teacher-resource:register".equals(action) && "/api/teacher/resources".equals(path))
                                || ("teacher-resource:sync".equals(action) && path.endsWith("/sync-jobs"))
                                || ("teacher-resource:sync-execute".equals(action) && path.endsWith("/execute"))
                                || ("teacher-resource:sync-resume".equals(action) && path.endsWith("/resume")))
                                && "teacher-88".equals(subject.normalize().subjectId()));
        TeacherResourceDocumentResponse resource = controller.register(new TeacherResourceRegistrationRequest(
                "feishu",
                "Retryable Feishu resource",
                "https://my.feishu.cn/drive/folder/XVn7fXppJlQMK5dkuOkc1ePan2f",
                null,
                "TEACHER_PRIVATE"), requestWithCapability("token-ok", "hash-register"));
        TeacherSourceSyncJobResponse queued = controller.createSyncJob(
                resource.documentId(),
                requestWithCapability("token-ok", "hash-sync"));
        TeacherSourceSyncJobResponse paused = controller.executeSyncJob(
                resource.documentId(),
                queued.jobId(),
                requestWithCapability("token-ok", "hash-execute"));

        TeacherSourceSyncJobResponse resumed = controller.resumeSyncJob(
                resource.documentId(),
                queued.jobId(),
                requestWithCapability("token-ok", "hash-resume"));

        assertThat(paused.status()).isEqualTo("paused");
        assertThat(paused.phase()).isEqualTo("download_paused");
        assertThat(resumed.status()).isEqualTo("completed");
        assertThat(resumed.phase()).isEqualTo("download_completed");
        assertThat(checkpointStore.findByJobId("school-a", queued.jobId()).orElseThrow().downloadedItemsJson())
                .contains("feishu-resumed");
    }

    @Test
    void rejectsSyncJobExecutionWithoutAcceptedCapabilityToken() throws Exception {
        Path folder = tempDir.resolve("blocked-sync-execute-resource");
        Files.createDirectories(folder);
        Files.writeString(folder.resolve("vector.md"), "# Space vector\n\nA vector has magnitude and direction.");
        InMemoryTeacherResourceStore store = new InMemoryTeacherResourceStore();
        InMemoryTeacherSourceSyncJobStore jobStore = new InMemoryTeacherSourceSyncJobStore();
        TeacherResourceController setupController = new TeacherResourceController(
                new TeacherResourceService(store),
                new TeacherSourceSyncJobService(store, jobStore),
                new TeacherSourceSyncExecutionService(store, jobStore, new InMemoryTeacherDocumentBlockStore()),
                request -> new RequestSubject("school-a", "teacher", "teacher-88", "device-1"),
                (token, action, path, requestHash, subject) -> true);
        TeacherResourceDocumentResponse resource = setupController.register(new TeacherResourceRegistrationRequest(
                "local_path",
                "Blocked executable local resource",
                null,
                folder.toString(),
                "TEACHER_PRIVATE"), requestWithCapability("token-ok", "hash-register"));
        TeacherSourceSyncJobResponse queued = setupController.createSyncJob(
                resource.documentId(),
                requestWithCapability("token-ok", "hash-sync"));
        TeacherResourceController protectedController = new TeacherResourceController(
                new TeacherResourceService(store),
                new TeacherSourceSyncJobService(store, jobStore),
                new TeacherSourceSyncExecutionService(store, jobStore, new InMemoryTeacherDocumentBlockStore()),
                request -> new RequestSubject("school-a", "teacher", "teacher-88", "device-1"),
                (token, action, path, requestHash, subject) -> false);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> protectedController.executeSyncJob(
                        resource.documentId(),
                        queued.jobId(),
                        requestWithCapability("bad-token", "hash-execute")))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Capability token");
    }

    private static final class FailsOnceThenSucceedsFeishuClient implements TeacherFeishuDownloadClient {

        private final Path savedPath;
        private int calls;

        private FailsOnceThenSucceedsFeishuClient(Path savedPath) {
            this.savedPath = savedPath;
        }

        @Override
        public FeishuDownloadResult download(String url, Path stagingRoot, int maxFiles) {
            calls += 1;
            if (calls == 1) {
                throw new TeacherFeishuDownloadException("ProxyError: proxy connection reset", true);
            }
            return new FeishuDownloadResult(savedPath, 1, 0, 0, "Downloaded 1 Feishu files after resume");
        }
    }

    /**
     * Builds an HTTP request carrying capability headers for controller tests.
     */
    private static MockHttpServletRequest requestWithCapability(String token, String requestHash) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Capability-Token", token);
        request.addHeader("X-Request-Hash", requestHash);
        return request;
    }
}
