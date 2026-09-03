package com.doob.mathagent.feishu;

import static org.assertj.core.api.Assertions.assertThat;

import com.doob.mathagent.teaching.service.TeachingHandoutPdfExportService;
import com.doob.mathagent.teaching.vo.TeachingTaskResponse;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/** 批量上传的幂等/失败语义：内容哈希决定跳过或重传，单文件失败不报废整批。 */
class FeishuHandoutUploadServiceTest {

    private static final class FakeDrive implements FeishuDriveClient {
        final Map<String, String> children = new HashMap<>();
        final List<String> uploads = new ArrayList<>();
        final List<byte[]> payloads = new ArrayList<>();
        int failAfter = -1;

        @Override
        public String createFolder(String accessToken, String name, String parentToken) {
            children.put(name, "fld-lib");
            return "fld-lib";
        }

        @Override
        public Map<String, String> listFolderChildren(String accessToken, String folderToken) {
            return children;
        }

        @Override
        public String uploadFile(String accessToken, String parentFolderToken, String fileName, byte[] bytes) {
            if (failAfter >= 0 && uploads.size() >= failAfter) {
                throw new IllegalStateException("FEISHU_FILE_UPLOAD_FAILED");
            }
            uploads.add(fileName);
            payloads.add(bytes);
            return "file-" + uploads.size();
        }
    }

    private static FeishuHandoutUploadMapper inMemoryUploadMapper(Map<String, FeishuHandoutUploadEntity> rows) {
        AtomicLong ids = new AtomicLong();
        return (FeishuHandoutUploadMapper) Proxy.newProxyInstance(
                FeishuHandoutUploadMapper.class.getClassLoader(),
                new Class<?>[]{FeishuHandoutUploadMapper.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "find" -> rows.get(args[0] + "|" + args[1] + "|" + args[2]);
                    case "insert" -> {
                        FeishuHandoutUploadEntity entity = (FeishuHandoutUploadEntity) args[0];
                        entity.setId(ids.incrementAndGet());
                        rows.put(entity.getTenantId() + "|" + entity.getTaskId() + "|" + entity.getVersion(), entity);
                        yield 1;
                    }
                    case "updateById" -> {
                        FeishuHandoutUploadEntity entity = (FeishuHandoutUploadEntity) args[0];
                        rows.put(entity.getTenantId() + "|" + entity.getTaskId() + "|" + entity.getVersion(), entity);
                        yield 1;
                    }
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    /** 渲染替身：按任务+版本返回固定内容，"changed" 版本模拟重编译后的不同 PDF。 */
    private static TeachingHandoutPdfExportService stubbedRenderer(String marker) {
        return new TeachingHandoutPdfExportService() {
            @Override
            public RenderedHandoutPdf renderForPublication(TeachingTaskResponse task, String version) {
                byte[] bytes = (marker + "/" + task.taskId() + "/" + version).getBytes(StandardCharsets.UTF_8);
                return new RenderedHandoutPdf(bytes, "test", 1);
            }
        };
    }

    private static TeachingTaskResponse task(String taskId) {
        return new TeachingTaskResponse(
                taskId, "client-" + taskId, "default", "teacher", "local-teacher-console", null,
                com.doob.mathagent.teaching.TeachingTaskStatus.COMPLETED,
                "question", "二次函数",
                List.of(), List.of(), List.of(),
                "\\section{讲义} x", "\\section{学生} x", "\\section{讲解} x",
                List.of(), null, List.of(), null, null);
    }

    private static FeishuTenantLibraryService library(FakeDrive drive) {
        return new FeishuTenantLibraryService(
                (FeishuTenantLibraryMapper) Proxy.newProxyInstance(
                        FeishuTenantLibraryMapper.class.getClassLoader(),
                        new Class<?>[]{FeishuTenantLibraryMapper.class},
                        (proxy, method, args) -> switch (method.getName()) {
                            case "findByTenant" -> null;
                            case "insert" -> 1;
                            default -> throw new UnsupportedOperationException(method.getName());
                        }),
                drive,
                new FeishuTenantTokenService(new MockEnvironment()) {
                    @Override
                    public String token() {
                        return "tenant-token";
                    }
                },
                new MockEnvironment());
    }

    @Test
    void uploadsRenderedPdfsAndRecordsTokens() {
        FakeDrive drive = new FakeDrive();
        Map<String, FeishuHandoutUploadEntity> rows = new HashMap<>();
        FeishuHandoutUploadService service = new FeishuHandoutUploadService(
                inMemoryUploadMapper(rows), library(drive), drive,
                new FeishuTenantTokenService(new MockEnvironment()) {
                    @Override
                    public String token() {
                        return "tenant-token";
                    }
                },
                stubbedRenderer("v1"));

        List<FeishuHandoutUploadService.UploadResult> results = service.uploadBatch(
                "school-a", "teacher-1", List.of(task("task-1")), List.of("student", "teacher"));

        assertThat(results).extracting(FeishuHandoutUploadService.UploadResult::status)
                .containsExactly("UPLOADED", "UPLOADED");
        assertThat(drive.uploads).hasSize(2);
        assertThat(drive.uploads.get(0)).startsWith("二次函数-学生版-task-1");
        assertThat(rows).hasSize(2);
        assertThat(rows.values()).allSatisfy(row -> assertThat(row.getFileToken()).startsWith("file-"));
    }

    @Test
    void unchangedContentIsSkippedAndChangedContentReuploaded() {
        FakeDrive drive = new FakeDrive();
        Map<String, FeishuHandoutUploadEntity> rows = new HashMap<>();
        FeishuTenantTokenService tokens = new FeishuTenantTokenService(new MockEnvironment()) {
            @Override
            public String token() {
                return "tenant-token";
            }
        };
        FeishuHandoutUploadService service = new FeishuHandoutUploadService(
                inMemoryUploadMapper(rows), library(drive), drive, tokens, stubbedRenderer("v1"));
        service.uploadBatch("school-a", "teacher-1", List.of(task("task-1")), List.of("student"));

        List<FeishuHandoutUploadService.UploadResult> rerun = service.uploadBatch(
                "school-a", "teacher-1", List.of(task("task-1")), List.of("student"));
        assertThat(rerun.getFirst().status()).isEqualTo("SKIPPED");
        assertThat(rerun.getFirst().fileToken()).isEqualTo("file-1");
        assertThat(drive.uploads).hasSize(1);

        // 模拟重编译内容变化：必须重新上传而不是停在旧 token。
        FeishuHandoutUploadService edited = new FeishuHandoutUploadService(
                inMemoryUploadMapper(rows), library(drive), drive, tokens, stubbedRenderer("v2"));
        List<FeishuHandoutUploadService.UploadResult> afterEdit = edited.uploadBatch(
                "school-a", "teacher-1", List.of(task("task-1")), List.of("student"));
        assertThat(afterEdit.getFirst().status()).isEqualTo("UPLOADED");
        assertThat(drive.uploads).hasSize(2);
    }

    @Test
    void oneUploadFailureDoesNotAbortTheBatch() {
        FakeDrive drive = new FakeDrive();
        Map<String, FeishuHandoutUploadEntity> rows = new HashMap<>();
        FeishuHandoutUploadService service = new FeishuHandoutUploadService(
                inMemoryUploadMapper(rows), library(drive), drive,
                new FeishuTenantTokenService(new MockEnvironment()) {
                    @Override
                    public String token() {
                        return "tenant-token";
                    }
                },
                stubbedRenderer("v1"));
        drive.failAfter = 1;

        List<FeishuHandoutUploadService.UploadResult> results = service.uploadBatch(
                "school-a", "teacher-1", List.of(task("task-1")), List.of("student", "teacher"));

        assertThat(results).extracting(FeishuHandoutUploadService.UploadResult::status)
                .containsExactly("UPLOADED", "FAILED");
        FeishuHandoutUploadEntity failed = rows.get("school-a|task-1|teacher");
        assertThat(failed.getStatus()).isEqualTo("FAILED");
        assertThat(failed.getMessage()).contains("IllegalStateException");
    }
}
