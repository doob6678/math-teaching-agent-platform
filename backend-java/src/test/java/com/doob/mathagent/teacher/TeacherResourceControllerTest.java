package com.doob.mathagent.teacher;

import static org.assertj.core.api.Assertions.assertThat;

import com.doob.mathagent.infrastructure.security.RequestSubject;
import com.doob.mathagent.teacher.controller.TeacherResourceController;
import com.doob.mathagent.teacher.dto.TeacherResourceRegistrationRequest;
import com.doob.mathagent.teacher.service.InMemoryTeacherResourceStore;
import com.doob.mathagent.teacher.service.InMemoryTeacherSourceSyncJobStore;
import com.doob.mathagent.teacher.service.TeacherResourceService;
import com.doob.mathagent.teacher.service.TeacherSourceSyncJobService;
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
