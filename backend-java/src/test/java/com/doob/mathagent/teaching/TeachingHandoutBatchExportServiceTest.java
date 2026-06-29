package com.doob.mathagent.teaching;

import static org.assertj.core.api.Assertions.assertThat;

import com.doob.mathagent.teaching.dto.TeachingHandoutBatchExportRequest;
import com.doob.mathagent.teaching.service.TeachingHandoutBatchExportService;
import com.doob.mathagent.teaching.service.TeachingHandoutPdfExportService;
import com.doob.mathagent.teaching.vo.TeachingHandoutBatchExportResponse;
import com.doob.mathagent.teaching.vo.TeachingTaskResponse;
import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.Test;

class TeachingHandoutBatchExportServiceTest {

    @Test
    void writesEachSelectedTaskIntoItsMatchingFolderPath() throws Exception {
        TeachingHandoutBatchExportService service = new TeachingHandoutBatchExportService(
                new TeachingHandoutPdfExportService());
        List<TeachingTaskResponse> tasks = List.of(
                task("task-algebra"),
                task("task-geometry"));

        TeachingHandoutBatchExportResponse response = service.create(
                new TeachingHandoutBatchExportRequest(
                        List.of("task-algebra", "task-geometry"),
                        List.of("folder-algebra", "folder-geometry"),
                        List.of("grade-10/functions", "grade-10/vectors")),
                TeachingRequestContext.localTeacher(),
                tasks);

        assertThat(zipEntries(service.findDownload(response.batchId(), TeachingRequestContext.localTeacher())
                        .orElseThrow()
                        .zipBytes()))
                .contains(
                        "grade-10/functions/task-algebra.tex",
                        "grade-10/functions/task-algebra.pdf",
                        "grade-10/vectors/task-geometry.tex",
                        "grade-10/vectors/task-geometry.pdf");
    }

    @Test
    void removesTraversalAndAbsolutePathSegmentsFromFolderZipEntries() throws Exception {
        TeachingHandoutBatchExportService service = new TeachingHandoutBatchExportService(
                new TeachingHandoutPdfExportService());

        TeachingHandoutBatchExportResponse response = service.create(
                new TeachingHandoutBatchExportRequest(
                        List.of("task-safe"),
                        List.of("folder-safe"),
                        List.of("../C:/unsafe/../../grade-10//vectors")),
                TeachingRequestContext.localTeacher(),
                List.of(task("task-safe")));

        List<String> entries = zipEntries(service.findDownload(response.batchId(), TeachingRequestContext.localTeacher())
                .orElseThrow()
                .zipBytes());
        assertThat(entries).contains("grade-10/vectors/task-safe.tex", "grade-10/vectors/task-safe.pdf");
        assertThat(entries).noneMatch(entry ->
                entry.contains("..") || entry.startsWith("/") || entry.startsWith("\\") || entry.contains(":"));
    }

    @Test
    void includesTeacherAndStudentHandoutVersionsInBatchZip() throws Exception {
        TeachingHandoutBatchExportService service = new TeachingHandoutBatchExportService(
                new TeachingHandoutPdfExportService());

        TeachingHandoutBatchExportResponse response = service.create(
                new TeachingHandoutBatchExportRequest(
                        List.of("task-versioned"),
                        List.of("folder-versioned"),
                        List.of("grade-10/versioned")),
                TeachingRequestContext.localTeacher(),
                List.of(task("task-versioned")));

        assertThat(zipEntries(service.findDownload(response.batchId(), TeachingRequestContext.localTeacher())
                        .orElseThrow()
                        .zipBytes()))
                .contains(
                        "grade-10/versioned/teacher/task-versioned.tex",
                        "grade-10/versioned/teacher/task-versioned.pdf",
                        "grade-10/versioned/student/task-versioned.tex",
                        "grade-10/versioned/student/task-versioned.pdf");
    }

    private static TeachingTaskResponse task(String taskId) {
        return new TeachingTaskResponse(
                taskId,
                "client-" + taskId,
                "default",
                "teacher",
                "local-teacher-console",
                TeachingTaskStatus.COMPLETED,
                "question",
                "goal",
                List.of(),
                List.of(),
                List.of(),
                "\\section{" + taskId + "}",
                "\\section{Teacher " + taskId + "}",
                "\\section{Student " + taskId + "}",
                List.of(),
                null,
                List.of(),
                null);
    }

    private static List<String> zipEntries(byte[] zipBytes) throws Exception {
        try (ZipInputStream input = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
            List<String> names = new ArrayList<>();
            java.util.zip.ZipEntry entry;
            while ((entry = input.getNextEntry()) != null) {
                names.add(entry.getName());
            }
            return names;
        }
    }
}
