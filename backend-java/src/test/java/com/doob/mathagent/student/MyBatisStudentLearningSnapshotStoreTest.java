package com.doob.mathagent.student;

import static org.assertj.core.api.Assertions.assertThat;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.doob.mathagent.student.entity.StudentLearningSnapshotEntity;
import com.doob.mathagent.student.mapper.StudentLearningSnapshotMapper;
import com.doob.mathagent.student.service.MyBatisStudentLearningSnapshotStore;
import com.doob.mathagent.student.service.StudentLearningSnapshotRecord;
import java.lang.reflect.Proxy;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.junit.jupiter.api.Test;

class MyBatisStudentLearningSnapshotStoreTest {

    @Test
    void findLatestReturnsNewestTenantScopedStudentSnapshot() {
        CapturingMapper mapper = new CapturingMapper();
        mapper.rows.add(entity("snapshot-old", "school-a", "student-1", "old", LocalDateTime.parse("2026-06-29T08:00:00")));
        mapper.rows.add(entity("snapshot-new", "school-a", "student-1", "new", LocalDateTime.parse("2026-06-29T09:00:00")));
        mapper.rows.add(entity("snapshot-other-tenant", "school-b", "student-1", "other", LocalDateTime.parse("2026-06-29T10:00:00")));
        MyBatisStudentLearningSnapshotStore store = new MyBatisStudentLearningSnapshotStore(mapper.proxy());

        StudentLearningSnapshotRecord record = store.findLatest("school-a", "student-1").orElseThrow();

        assertThat(record.snapshotId()).isEqualTo("snapshot-new");
        assertThat(record.tenantId()).isEqualTo("school-a");
        assertThat(record.studentId()).isEqualTo("student-1");
        assertThat(record.knowledgeProgressJson()).contains("new");
        assertThat(record.knowledgeGraphJson()).contains("mysql_snapshot");
    }

    @Test
    void saveInsertsSnapshotEntityWithTenantScopedPayloads() {
        CapturingMapper mapper = new CapturingMapper();
        MyBatisStudentLearningSnapshotStore store = new MyBatisStudentLearningSnapshotStore(mapper.proxy());

        StudentLearningSnapshotRecord saved = store.save(new StudentLearningSnapshotRecord(
                "snapshot-write",
                "school-a",
                "student-1",
                null,
                "[{\"knowledgePointId\":\"memory-1\"}]",
                "{\"nodes\":[],\"edges\":[],\"generatedFrom\":\"student_memory_entry\"}",
                "[]",
                "[{\"recordId\":\"mem-1\"}]",
                "[]",
                "[{\"scopeCode\":\"STUDENT_MEMORY_PRIVATE\"}]",
                "student_memory_entry:total=1"));

        assertThat(saved.snapshotId()).isEqualTo("snapshot-write");
        assertThat(mapper.inserted).hasSize(1);
        StudentLearningSnapshotEntity inserted = mapper.inserted.getFirst();
        assertThat(inserted.getTenantId()).isEqualTo("school-a");
        assertThat(inserted.getStudentId()).isEqualTo("student-1");
        assertThat(inserted.getKnowledgeProgressJson()).contains("memory-1");
        assertThat(inserted.getRecentQuestionsJson()).contains("mem-1");
        assertThat(inserted.getScoreTrendJson()).isEqualTo("[]");
        assertThat(inserted.getSourceSummary()).isEqualTo("student_memory_entry:total=1");
    }

    /**
     * Builds a snapshot entity for mapper proxy tests.
     */
    private static StudentLearningSnapshotEntity entity(
            String snapshotId,
            String tenantId,
            String studentId,
            String label,
            LocalDateTime updatedAt) {
        StudentLearningSnapshotEntity entity = new StudentLearningSnapshotEntity();
        entity.setSnapshotId(snapshotId);
        entity.setTenantId(tenantId);
        entity.setStudentId(studentId);
        entity.setGradeName("Senior Grade 2");
        entity.setKnowledgeProgressJson("[{\"knowledgePointId\":\"" + label + "\"}]");
        entity.setKnowledgeGraphJson("{\"nodes\":[],\"edges\":[],\"generatedFrom\":\"mysql_snapshot\"}");
        entity.setWeakPointsJson("[]");
        entity.setRecentQuestionsJson("[]");
        entity.setScoreTrendJson("[]");
        entity.setResourceScopesJson("[]");
        entity.setSourceSummary("mysql_snapshot");
        entity.setUpdatedAt(updatedAt);
        return entity;
    }

    private static class CapturingMapper {
        private final List<StudentLearningSnapshotEntity> rows = new ArrayList<>();
        private final List<StudentLearningSnapshotEntity> inserted = new ArrayList<>();

        StudentLearningSnapshotMapper proxy() {
            return (StudentLearningSnapshotMapper) Proxy.newProxyInstance(
                    StudentLearningSnapshotMapper.class.getClassLoader(),
                    new Class<?>[] {StudentLearningSnapshotMapper.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "selectPage" -> selectPage((Page<StudentLearningSnapshotEntity>) args[0], (Wrapper<StudentLearningSnapshotEntity>) args[1]);
                        case "insert" -> {
                            inserted.add((StudentLearningSnapshotEntity) args[0]);
                            yield 1;
                        }
                        default -> throw new UnsupportedOperationException(method.getName());
                    });
        }

        private Page<StudentLearningSnapshotEntity> selectPage(
                Page<StudentLearningSnapshotEntity> page,
                Wrapper<StudentLearningSnapshotEntity> ignored) {
            page.setRecords(rows.stream()
                    .filter(row -> "school-a".equals(row.getTenantId()))
                    .filter(row -> "student-1".equals(row.getStudentId()))
                    .sorted(Comparator.comparing(StudentLearningSnapshotEntity::getUpdatedAt).reversed())
                    .limit(1)
                    .toList());
            return page;
        }
    }
}
