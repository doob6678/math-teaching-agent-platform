package com.doob.mathagent.teacher;

import static org.assertj.core.api.Assertions.assertThat;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.doob.mathagent.teacher.entity.TeacherSourceSyncCheckpointEntity;
import com.doob.mathagent.teacher.mapper.TeacherSourceSyncCheckpointMapper;
import com.doob.mathagent.teacher.sync.MyBatisTeacherSourceSyncCheckpointStore;
import com.doob.mathagent.teacher.vo.TeacherSourceSyncCheckpointResponse;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class MyBatisTeacherSourceSyncCheckpointStoreTest {

    @Test
    void saveInsertsThenUpdatesCheckpointForSameTenantAndJob() {
        CapturingMapper mapper = new CapturingMapper();
        MyBatisTeacherSourceSyncCheckpointStore store = new MyBatisTeacherSourceSyncCheckpointStore(mapper.proxy());

        TeacherSourceSyncCheckpointResponse inserted = store.save(checkpoint(
                "school-a",
                "job-1",
                "100",
                "root-token",
                "folder-a",
                "A",
                "page-1",
                1));
        mapper.rows.add(mapper.inserted);
        TeacherSourceSyncCheckpointResponse updated = store.save(checkpoint(
                "school-a",
                "job-1",
                "100",
                "root-token",
                "folder-b",
                "A/B",
                "page-2",
                2));

        assertThat(inserted.jobId()).isEqualTo("job-1");
        assertThat(mapper.inserted.getSourceDocumentId()).isEqualTo(100L);
        assertThat(mapper.inserted.getCurrentFolderToken()).isEqualTo("folder-a");
        assertThat(mapper.updated.getId()).isEqualTo(mapper.inserted.getId());
        assertThat(mapper.updated.getCurrentFolderToken()).isEqualTo("folder-b");
        assertThat(updated.currentPath()).isEqualTo("A/B");
    }

    @Test
    void findByJobIdMapsCheckpointEntityWithoutCrossTenantLeakage() {
        CapturingMapper mapper = new CapturingMapper();
        mapper.rows.add(entity(1L, "school-a", "job-1", 100L, "folder-a"));
        mapper.rows.add(entity(2L, "school-b", "job-1", 200L, "folder-b"));
        MyBatisTeacherSourceSyncCheckpointStore store = new MyBatisTeacherSourceSyncCheckpointStore(mapper.proxy());

        TeacherSourceSyncCheckpointResponse found = store.findByJobId("school-a", "job-1").orElseThrow();

        assertThat(found.tenantId()).isEqualTo("school-a");
        assertThat(found.documentId()).isEqualTo("100");
        assertThat(found.currentFolderToken()).isEqualTo("folder-a");
    }

    /**
     * Builds a checkpoint response used by MyBatis mapping tests.
     */
    private static TeacherSourceSyncCheckpointResponse checkpoint(
            String tenantId,
            String jobId,
            String documentId,
            String rootToken,
            String currentFolderToken,
            String currentPath,
            String pageToken,
            int cursorVersion) {
        return new TeacherSourceSyncCheckpointResponse(
                jobId,
                tenantId,
                documentId,
                rootToken,
                currentFolderToken,
                currentPath,
                pageToken,
                "[\"" + currentFolderToken + "\"]",
                "[{\"token\":\"file-1\"}]",
                "[]",
                cursorVersion,
                "2026-06-29T08:00:00Z");
    }

    /**
     * Builds a checkpoint entity used by mapper proxy responses.
     */
    private static TeacherSourceSyncCheckpointEntity entity(
            Long id,
            String tenantId,
            String jobId,
            Long sourceDocumentId,
            String currentFolderToken) {
        TeacherSourceSyncCheckpointEntity entity = new TeacherSourceSyncCheckpointEntity();
        entity.setId(id);
        entity.setTenantId(tenantId);
        entity.setJobId(jobId);
        entity.setSourceDocumentId(sourceDocumentId);
        entity.setRootToken("root-token");
        entity.setCurrentFolderToken(currentFolderToken);
        entity.setCurrentPath("path/" + currentFolderToken);
        entity.setPageToken("page-token");
        entity.setVisitedFolderTokensJson("[\"" + currentFolderToken + "\"]");
        entity.setDownloadedItemsJson("[]");
        entity.setFailedItemsJson("[]");
        entity.setCursorVersion(1);
        return entity;
    }

    private static class CapturingMapper {
        private final List<TeacherSourceSyncCheckpointEntity> rows = new ArrayList<>();
        private TeacherSourceSyncCheckpointEntity inserted;
        private TeacherSourceSyncCheckpointEntity updated;

        TeacherSourceSyncCheckpointMapper proxy() {
            return (TeacherSourceSyncCheckpointMapper) Proxy.newProxyInstance(
                    TeacherSourceSyncCheckpointMapper.class.getClassLoader(),
                    new Class<?>[] {TeacherSourceSyncCheckpointMapper.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "insert" -> {
                            inserted = (TeacherSourceSyncCheckpointEntity) args[0];
                            inserted.setId(1L);
                            yield 1;
                        }
                        case "updateById" -> {
                            updated = (TeacherSourceSyncCheckpointEntity) args[0];
                            yield 1;
                        }
                        case "selectOne" -> selectOne((Wrapper<TeacherSourceSyncCheckpointEntity>) args[0]);
                        default -> throw new UnsupportedOperationException(method.getName());
                    });
        }

        private TeacherSourceSyncCheckpointEntity selectOne(Wrapper<TeacherSourceSyncCheckpointEntity> ignored) {
            return rows.stream()
                    .filter(row -> "school-a".equals(row.getTenantId()))
                    .filter(row -> "job-1".equals(row.getJobId()))
                    .findFirst()
                    .orElse(null);
        }
    }
}
