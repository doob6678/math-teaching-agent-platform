package com.doob.mathagent.teacher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.doob.mathagent.teacher.block.TeacherDocumentBlockResponse;
import com.doob.mathagent.teacher.service.InMemoryTeacherDocumentBlockStore;
import com.doob.mathagent.teacher.service.InMemoryTeacherResourceStore;
import com.doob.mathagent.teacher.support.TeacherResourceRegistrationCommand;
import com.doob.mathagent.teacher.service.TeacherResourceService;
import com.doob.mathagent.teacher.document.TeacherResourceDocumentResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
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
        assertThat(response.permissionScope()).isEqualTo("MATH_VIP");
        assertThat(response.sourceType()).isEqualTo("teacher_resource");
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
    void teacherUploadDefaultsToTenantSharedWhenPermissionIsBlank() throws Exception {
        Path file = tempDir.resolve("shared-notes.md");
        Files.writeString(file, "# Shared notes");
        TeacherResourceService service = TeacherResourceServiceFixture.service(new InMemoryTeacherResourceStore());

        TeacherResourceDocumentResponse response = service.register(new TeacherResourceRegistrationCommand(
                "default", "teacher", "teacher-001", "local_path", "Shared notes", null,
                file.toString(), "", null));

        assertThat(response.permissionScope()).isEqualTo("TENANT_PUBLIC");
    }

    @Test
    void localPathIsPreservedForLocalDevelopmentWithoutPathConvergence() {
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

        TeacherResourceDocumentResponse response = service.register(request);

        assertThat(response.localPath()).isEqualTo("C:\\workspace\\??\\resource");
        assertThat(service.list("default", "teacher", "teacher-001"))
                .extracting(TeacherResourceDocumentResponse::localPath)
                .containsExactly("C:\\workspace\\??\\resource");
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
    void rejectsLegacyTextbookBridgeSourceTypeDuringRegistration() {
        TeacherResourceService service = TeacherResourceServiceFixture.service(new InMemoryTeacherResourceStore());
        TeacherResourceRegistrationCommand request = new TeacherResourceRegistrationCommand(
                "default",
                "admin",
                "admin-001",
                "textbook_md",
                "旧教材桥接入口",
                null,
                "C:/workspace/old-textbook-bridge",
                "PUBLIC_TEXTBOOK",
                null);

        assertThatThrownBy(() -> service.register(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Legacy textbook sourceType");
    }

    @Test
    void ownerTeacherCanArchiveOwnResource() {
        InMemoryTeacherResourceStore resourceStore = new InMemoryTeacherResourceStore();
        InMemoryTeacherDocumentBlockStore blockStore = new InMemoryTeacherDocumentBlockStore();
        TeacherResourceService service = TeacherResourceServiceFixture.service(resourceStore, blockStore);
        TeacherResourceDocumentResponse created = service.register(new TeacherResourceRegistrationCommand(
                "default",
                "teacher",
                "teacher-001",
                "feishu",
                "Feishu question bank",
                "https://example.feishu.cn/docx/doc1",
                null,
                "TEACHER_PRIVATE",
                "md"));

        blockStore.replaceActiveBlocks("default", created.documentId(), List.of(new TeacherDocumentBlockResponse(
                "block-1",
                created.documentId(),
                "feishu-token|0",
                "markdown",
                0,
                "test",
                null,
                null,
                null,
                "feishu-token.md",
                "reference",
                "sensitive source text",
                "sensitive source text",
                "[]",
                "[]",
                "[]",
                "[]",
                "checksum",
                1.0,
                "active")));

        TeacherResourceDocumentResponse archived = service.archive(
                "default",
                "teacher",
                "teacher-001",
                created.documentId());

        assertThat(archived.syncStatus()).isEqualTo("archived");
        assertThat(service.list("default", "teacher", "teacher-001"))
                .extracting(TeacherResourceDocumentResponse::documentId)
                .doesNotContain(created.documentId());
        assertThat(blockStore.listByDocument("default", created.documentId())).isEmpty();
    }
}

