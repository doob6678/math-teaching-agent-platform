package com.doob.mathagent.teacher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.doob.mathagent.teacher.service.InMemoryTeacherResourceStore;
import com.doob.mathagent.teacher.service.TeacherResourceRegistrationCommand;
import com.doob.mathagent.teacher.service.TeacherResourceService;
import com.doob.mathagent.teacher.vo.TeacherResourceDocumentResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TeacherResourceServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void teacherCanRegisterLocalFolderAndPreviewFiles() throws Exception {
        Path folder = tempDir.resolve("math-notes");
        Files.createDirectories(folder);
        Files.writeString(folder.resolve("vector.md"), "# Space Vector\nVector dot product method.");

        TeacherResourceService service = TeacherResourceServiceFixture.service(new InMemoryTeacherResourceStore());
        TeacherResourceRegistrationCommand request = new TeacherResourceRegistrationCommand(
                "default",
                "teacher",
                "teacher-001",
                "local_path",
                "Space Vector Notes",
                null,
                folder.toString(),
                "MATH_VIP",
                null);

        TeacherResourceDocumentResponse response = service.register(request);

        assertThat(response.tenantId()).isEqualTo("default");
        assertThat(response.ownerSubjectId()).isEqualTo("teacher-001");
        assertThat(response.sourceType()).isEqualTo("local_path");
        assertThat(response.syncStatus()).isEqualTo("registered");
        assertThat(response.parseStatus()).isEqualTo("pending");
        assertThat(response.embeddingStatus()).isEqualTo("pending");
        assertThat(response.indexStatus()).isEqualTo("waiting_rebuild");
        assertThat(response.previewFiles()).extracting(TeacherResourceDocumentResponse.PreviewFile::fileName)
                .contains("vector.md");
    }

    @Test
    void studentCannotRegisterTeacherResource() {
        TeacherResourceService service = TeacherResourceServiceFixture.service(new InMemoryTeacherResourceStore());
        TeacherResourceRegistrationCommand request = new TeacherResourceRegistrationCommand(
                "default",
                "student",
                "student-001",
                "feishu",
                "Feishu question bank",
                "https://example.feishu.cn/docs/doc1",
                null,
                "TEACHER_PRIVATE",
                "md");

        assertThatThrownBy(() -> service.register(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("teacher or admin");
    }

    @Test
    void invalidLocalPathFailsBeforePersistingResource() {
        TeacherResourceService service = TeacherResourceServiceFixture.service(new InMemoryTeacherResourceStore());
        TeacherResourceRegistrationCommand request = new TeacherResourceRegistrationCommand(
                "default",
                "teacher",
                "teacher-001",
                "local_path",
                "Broken Path",
                null,
                "C:\\workspace\\??\\resource",
                "TEACHER_PRIVATE",
                null);

        assertThatThrownBy(() -> service.register(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Local resource path is invalid");

        assertThat(service.list("default", "teacher", "teacher-001")).isEmpty();
    }

    @Test
    void feishuResourceRegistrationDefaultsAndReturnsMarkdownExportFormat() {
        TeacherResourceService service = TeacherResourceServiceFixture.service(new InMemoryTeacherResourceStore());
        TeacherResourceRegistrationCommand request = new TeacherResourceRegistrationCommand(
                "default",
                "teacher",
                "teacher-001",
                "feishu",
                "Feishu question bank",
                "https://example.feishu.cn/docx/doc1",
                null,
                "TEACHER_PRIVATE",
                "md");

        TeacherResourceDocumentResponse response = service.register(request);

        assertThat(response.feishuExportFormat()).isEqualTo("md");
        assertThat(service.list("default", "teacher", "teacher-001"))
                .extracting(TeacherResourceDocumentResponse::feishuExportFormat)
                .containsExactly("md");
    }

    @Test
    void feishuResourceRegistrationSavesSelectedPdfExportFormat() {
        TeacherResourceService service = TeacherResourceServiceFixture.service(new InMemoryTeacherResourceStore());
        TeacherResourceRegistrationCommand request = new TeacherResourceRegistrationCommand(
                "default",
                "teacher",
                "teacher-001",
                "feishu",
                "Feishu PDF handout",
                "https://example.feishu.cn/docx/doc1",
                null,
                "TEACHER_PRIVATE",
                "pdf");

        TeacherResourceDocumentResponse response = service.register(request);

        assertThat(response.feishuExportFormat()).isEqualTo("pdf");
    }

    @Test
    void ownerTeacherCanArchiveOwnResource() {
        TeacherResourceService service = TeacherResourceServiceFixture.service(new InMemoryTeacherResourceStore());
        TeacherResourceDocumentResponse created = service.register(new TeacherResourceRegistrationCommand(
                "default",
                "teacher",
                "teacher-001",
                "feishu",
                "Feishu question bank",
                "https://example.feishu.cn/docs/doc1",
                null,
                "TEACHER_PRIVATE",
                "md"));

        TeacherResourceDocumentResponse archived = service.archive(
                "default",
                "teacher",
                "teacher-001",
                created.documentId());

        assertThat(archived.syncStatus()).isEqualTo("archived");
        assertThat(service.list("default", "teacher", "teacher-001"))
                .extracting(TeacherResourceDocumentResponse::documentId)
                .doesNotContain(created.documentId());
    }
}
