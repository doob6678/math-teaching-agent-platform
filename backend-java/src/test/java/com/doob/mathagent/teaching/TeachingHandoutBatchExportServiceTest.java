package com.doob.mathagent.teaching;

import static org.assertj.core.api.Assertions.assertThat;

import com.doob.mathagent.teaching.dto.TeachingHandoutBatchExportRequest;
import com.doob.mathagent.teaching.service.TeachingHandoutBatchExportService;
import com.doob.mathagent.teaching.service.TeachingHandoutPdfExportService;
import com.doob.mathagent.teaching.vo.TeachingHandoutBatchExportResponse;
import com.doob.mathagent.teaching.vo.TeachingTaskResponse;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.Test;

class TeachingHandoutBatchExportServiceTest {

    private static TeachingHandoutBatchExportService service() {
        return new TeachingHandoutBatchExportService(
                new TeachingHandoutPdfExportService(),
                Clock.systemUTC(),
                Duration.ofMinutes(30));
    }

    @Test
    void writesEachSelectedTaskIntoItsMatchingFolderPath() throws Exception {
        TeachingHandoutBatchExportService service = service();
        List<TeachingTaskResponse> tasks = List.of(
                task("task-algebra"),
                task("task-geometry"));

        TeachingHandoutBatchExportResponse response = service.create(
                new TeachingHandoutBatchExportRequest(
                        List.of("task-algebra", "task-geometry"),
                        List.of("folder-algebra", "folder-geometry"),
                        List.of("grade-10/functions", "grade-10/vectors")),
                new TeachingRequestContext("school-a", "teacher", "teacher-001", "browser-console"),
                tasks);

        assertThat(zipEntries(service.findDownload(response.batchId(), new TeachingRequestContext("school-a", "teacher", "teacher-001", "browser-console"))
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
        TeachingHandoutBatchExportService service = service();

        TeachingHandoutBatchExportResponse response = service.create(
                new TeachingHandoutBatchExportRequest(
                        List.of("task-safe"),
                        List.of("folder-safe"),
                        List.of("../C:/unsafe/../../grade-10//vectors")),
                new TeachingRequestContext("school-a", "teacher", "teacher-001", "browser-console"),
                List.of(task("task-safe")));

        List<String> entries = zipEntries(service.findDownload(response.batchId(), new TeachingRequestContext("school-a", "teacher", "teacher-001", "browser-console"))
                .orElseThrow()
                .zipBytes());
        assertThat(entries).contains("grade-10/vectors/task-safe.tex", "grade-10/vectors/task-safe.pdf");
        assertThat(entries).noneMatch(entry ->
                entry.contains("..") || entry.startsWith("/") || entry.startsWith("\\") || entry.contains(":"));
    }

    @Test
    void includesTeacherAndStudentHandoutVersionsInBatchZip() throws Exception {
        TeachingHandoutBatchExportService service = service();

        TeachingHandoutBatchExportResponse response = service.create(
                new TeachingHandoutBatchExportRequest(
                        List.of("task-versioned"),
                        List.of("folder-versioned"),
                        List.of("grade-10/versioned")),
                new TeachingRequestContext("school-a", "teacher", "teacher-001", "browser-console"),
                List.of(task("task-versioned")));

        assertThat(zipEntries(service.findDownload(response.batchId(), new TeachingRequestContext("school-a", "teacher", "teacher-001", "browser-console"))
                        .orElseThrow()
                        .zipBytes()))
                .contains(
                        "grade-10/versioned/teacher/task-versioned.tex",
                        "grade-10/versioned/teacher/task-versioned.pdf",
                        "grade-10/versioned/lecture/task-versioned.tex",
                        "grade-10/versioned/lecture/task-versioned.pdf",
                        "grade-10/versioned/student/task-versioned.tex",
                        "grade-10/versioned/student/task-versioned.pdf");
    }

    @Test
    void batchZipTexEntriesUseSanitizedHandoutLatex() throws Exception {
        TeachingHandoutBatchExportService service = service();
        TeachingTaskResponse task = new TeachingTaskResponse(
                "task-sanitized-zip",
                "client-task-sanitized-zip",
                "default",
                "teacher",
                "teacher-001",
                null,
                TeachingTaskStatus.COMPLETED,
                "question",
                "goal",
                List.of(),
                List.of(),
                List.of(),
                "",
                """
                \\section{讲义模板与版式}
                PDF 版式要求：页眉展示主题和版本，页脚展示页码；教师版使用讲评色。
                \\section{教材与资料证据}
                # p159 - 书名：人教B版选择性必修一 - 页图：![p159](../../pages/p159.png)
                ## 正文
                旧 OCR 正文不应该进导出文件。
                \\section{教师讲评页}
                \\paragraph{答案与评分点}
                由 $2a=6$ 得 $a=3$。
                """,
                "\\section{学生版}\n\\subsection*{第1题 练习}\n\\paragraph{题目}\n根据定义独立完成推导。\n\\vspace{6em}",
                "\\subsection*{第1题 投屏讲解}\n\\paragraph{题目}\n根据定义完成投屏讲解。",
                List.of(),
                null,
                List.of(),
                null,
                null);

        TeachingHandoutBatchExportResponse response = service.create(
                new TeachingHandoutBatchExportRequest(
                        List.of("task-sanitized-zip"),
                        List.of("folder-sanitized-zip"),
                        List.of("grade-10/sanitized")),
                new TeachingRequestContext("school-a", "teacher", "teacher-001", "browser-console"),
                List.of(task));

        Map<String, String> entries = zipTextEntries(service.findDownload(
                        response.batchId(),
                        new TeachingRequestContext("school-a", "teacher", "teacher-001", "browser-console"))
                .orElseThrow()
                .zipBytes());

        assertThat(entries.get("grade-10/sanitized/task-sanitized-zip.tex"))
                .contains("\\section{来源索引}", "\\section{教师讲评页}", "$2a=6$", "$a=3$")
                .doesNotContain("讲义模板与版式", "PDF 版式要求", "页眉", "页脚", "讲评色", "![p159]",
                        "../../pages", "## 正文", "旧 OCR 正文");
        assertThat(entries.get("grade-10/sanitized/teacher/task-sanitized-zip.tex"))
                .contains("\\section{来源索引}", "\\section{教师讲评页}")
                .doesNotContain("PDF 版式要求", "../../pages", "旧 OCR 正文");
    }

    @Test
    void studentBatchZipContainsOnlyStudentHandoutVersion() throws Exception {
        TeachingHandoutBatchExportService service = service();

        TeachingHandoutBatchExportResponse response = service.create(
                new TeachingHandoutBatchExportRequest(
                        List.of("task-student"),
                        List.of("folder-student"),
                        List.of("grade-10/student")),
                new TeachingRequestContext("default", "student", "student-1", "browser-1"),
                List.of(task("task-student")));

        List<String> entries = zipEntries(service.findDownload(
                        response.batchId(),
                        new TeachingRequestContext("default", "student", "student-1", "browser-1"))
                .orElseThrow()
                .zipBytes());
        assertThat(entries).contains(
                "grade-10/student/student/task-student.tex",
                "grade-10/student/student/task-student.pdf",
                "manifest.txt");
        assertThat(entries).noneMatch(entry ->
                entry.equals("grade-10/student/task-student.tex")
                        || entry.equals("grade-10/student/task-student.pdf")
                        || entry.contains("/teacher/")
                        || entry.contains("/lecture/"));
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
                "\\section{教师讲义}\n\\subsection*{第1题 " + taskId + "}\n\\paragraph{题目}\n根据定义完成推导。\n\\paragraph{答案与评分点}\n写出定义并完成代入。",
                "\\section{学生练习}\n\\subsection*{第1题 " + taskId + "}\n\\paragraph{题目}\n根据定义独立完成推导。\n\\vspace{6em}",
                "\\subsection*{第1题 " + taskId + "}\n\\paragraph{题目}\n根据定义完成投屏讲解。",
                List.of(),
                null,
                List.of(),
                null,
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

    private static Map<String, String> zipTextEntries(byte[] zipBytes) throws Exception {
        try (ZipInputStream input = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
            Map<String, String> entries = new LinkedHashMap<>();
            java.util.zip.ZipEntry entry;
            while ((entry = input.getNextEntry()) != null) {
                byte[] bytes = input.readAllBytes();
                if (entry.getName().endsWith(".tex") || entry.getName().endsWith(".txt")) {
                    entries.put(entry.getName(), new String(bytes, StandardCharsets.UTF_8));
                }
            }
            return entries;
        }
    }
}
