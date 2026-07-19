package com.doob.mathagent.teacher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.doob.mathagent.infrastructure.security.RequestSubject;
import com.doob.mathagent.resources.ProjectResourceProperties;
import com.doob.mathagent.teacher.service.TeacherResourceUploadService;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

class TeacherResourceUploadServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void storesFolderStyleMultipartPathsUnderTeacherOwnedRoot() throws Exception {
        TeacherResourceUploadService service = service();

        TeacherResourceUploadService.StoredUpload upload = service.store(
                java.util.List.of(new MockMultipartFile(
                        "files",
                        "qq_bundle/lesson01/notes.md",
                        "text/markdown",
                        "# 向量\n数量积方法".getBytes(StandardCharsets.UTF_8))),
                new RequestSubject("school-a", "teacher", "teacher-01", "device-1"));

        assertThat(upload.rootPath()).exists();
        assertThat(upload.storedFileCount()).isEqualTo(1);
        assertThat(upload.suggestedTitle()).isEqualTo("qq_bundle");
        assertThat(Files.readString(upload.rootPath().resolve("qq_bundle/lesson01/notes.md"), StandardCharsets.UTF_8))
                .contains("数量积方法");
    }

    @Test
    void expandsZipUploadsIntoManagedDirectory() throws Exception {
        TeacherResourceUploadService service = service();

        TeacherResourceUploadService.StoredUpload upload = service.store(
                java.util.List.of(new MockMultipartFile(
                        "files",
                        "method-pack.zip",
                        "application/zip",
                        zipBytes("docs/lesson.md", "# 单调性\n增减性证明"))),
                new RequestSubject("school-a", "teacher", "teacher-01", "device-1"));

        assertThat(upload.storedFileCount()).isEqualTo(1);
        assertThat(upload.suggestedTitle()).isEqualTo("method-pack");
        assertThat(upload.rootPath().resolve("method-pack/docs/lesson.md")).exists();
        assertThat(Files.readString(
                upload.rootPath().resolve("method-pack/docs/lesson.md"),
                StandardCharsets.UTF_8)).contains("增减性证明");
    }

    @Test
    void derivesBatchTitleForLooseMultiFileUpload() throws Exception {
        TeacherResourceUploadService service = service();

        TeacherResourceUploadService.StoredUpload upload = service.store(
                java.util.List.of(
                        new MockMultipartFile("files", "导数讲义.pdf", "application/pdf", "pdf".getBytes(StandardCharsets.UTF_8)),
                        new MockMultipartFile(
                                "files",
                                "课堂例题.docx",
                                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                                "docx".getBytes(StandardCharsets.UTF_8))),
                new RequestSubject("school-a", "teacher", "teacher-01", "device-1"));

        assertThat(upload.suggestedTitle()).isEqualTo("导数讲义 等2个文件");
    }

    @Test
    void rejectsNonTeacherOwners() {
        TeacherResourceUploadService service = service();

        assertThatThrownBy(() -> service.store(
                        java.util.List.of(new MockMultipartFile(
                                "files",
                                "notes.md",
                                "text/markdown",
                                "# 集合".getBytes(StandardCharsets.UTF_8))),
                        new RequestSubject("school-a", "student", "student-01", "device-1")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("teacher or admin");
    }

    private TeacherResourceUploadService service() {
        return new TeacherResourceUploadService(
                new ProjectResourceProperties(tempDir, tempDir, tempDir, tempDir, tempDir),
                Clock.systemUTC(),
                1024 * 1024,
                64);
    }

    private static byte[] zipBytes(String entryName, String content) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (java.util.zip.ZipOutputStream zip = new java.util.zip.ZipOutputStream(output)) {
            zip.putNextEntry(new java.util.zip.ZipEntry(entryName));
            zip.write(content.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return output.toByteArray();
    }
}
