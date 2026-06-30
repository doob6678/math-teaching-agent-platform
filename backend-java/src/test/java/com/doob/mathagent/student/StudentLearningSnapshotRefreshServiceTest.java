package com.doob.mathagent.student;

import static org.assertj.core.api.Assertions.assertThat;

import com.doob.mathagent.memory.service.StudentMemoryEntry;
import com.doob.mathagent.memory.service.StudentMemoryStore;
import com.doob.mathagent.student.dto.StudentDashboardQuery;
import com.doob.mathagent.student.service.StudentLearningSnapshotRecord;
import com.doob.mathagent.student.service.StudentLearningSnapshotRefreshService;
import com.doob.mathagent.student.service.StudentLearningSnapshotStore;
import com.doob.mathagent.student.vo.StudentDashboardResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class StudentLearningSnapshotRefreshServiceTest {

    @Test
    void refreshAggregatesRealMemoryEntriesWithoutInventingExamScores() {
        CapturingMemoryStore memoryStore = new CapturingMemoryStore();
        memoryStore.entries.add(new StudentMemoryEntry(
                "mem-1",
                "school-a",
                "student-1",
                "private",
                "空间向量数量积",
                "已知两个空间向量，求夹角。",
                "answer",
                "active",
                Instant.parse("2026-06-30T08:00:00Z")));
        memoryStore.entries.add(new StudentMemoryEntry(
                "mem-2",
                "school-a",
                "teacher-1",
                "public",
                "立体几何线面关系",
                "证明直线和平面垂直。",
                "answer",
                "active",
                Instant.parse("2026-06-30T09:00:00Z")));
        memoryStore.entries.add(new StudentMemoryEntry(
                "mem-other-tenant",
                "school-b",
                "student-1",
                "private",
                "不应泄露",
                "other",
                "other",
                "active",
                Instant.parse("2026-06-30T10:00:00Z")));
        CapturingSnapshotStore snapshotStore = new CapturingSnapshotStore();
        StudentLearningSnapshotRefreshService service = new StudentLearningSnapshotRefreshService(
                memoryStore,
                snapshotStore,
                new ObjectMapper());

        StudentDashboardResponse response = service.refresh(new StudentDashboardQuery(
                "school-a",
                "student",
                "student-1",
                null));

        assertThat(response.studentId()).isEqualTo("student-1");
        assertThat(response.knowledgeProgress())
                .extracting(StudentDashboardResponse.KnowledgeProgress::knowledgePointName)
                .containsExactly("立体几何线面关系", "空间向量数量积");
        assertThat(response.recentQuestions())
                .extracting(StudentDashboardResponse.RecentQuestion::recordId)
                .containsExactly("mem-2", "mem-1");
        assertThat(response.scoreTrend()).isEmpty();
        assertThat(response.weakPoints()).isEmpty();
        assertThat(response.knowledgeGraph().edges()).isEmpty();
        assertThat(snapshotStore.saved).hasSize(1);
        assertThat(snapshotStore.saved.getFirst().sourceSummary())
                .isEqualTo("student_memory_entry:total=2,private=1,public=1,knowledgePoints=2");
        assertThat(snapshotStore.saved.getFirst().scoreTrendJson()).isEqualTo("[]");
    }

    @Test
    void studentRefreshCannotTargetAnotherStudentFromRequestParameter() {
        CapturingMemoryStore memoryStore = new CapturingMemoryStore();
        memoryStore.entries.add(new StudentMemoryEntry(
                "mem-real",
                "school-a",
                "student-real",
                "private",
                "函数定义域",
                "求函数定义域。",
                "answer",
                "active",
                Instant.parse("2026-06-30T08:00:00Z")));
        memoryStore.entries.add(new StudentMemoryEntry(
                "mem-victim",
                "school-a",
                "student-victim",
                "private",
                "不应出现",
                "victim",
                "answer",
                "active",
                Instant.parse("2026-06-30T09:00:00Z")));
        CapturingSnapshotStore snapshotStore = new CapturingSnapshotStore();
        StudentLearningSnapshotRefreshService service = new StudentLearningSnapshotRefreshService(
                memoryStore,
                snapshotStore,
                new ObjectMapper());

        StudentDashboardResponse response = service.refresh(new StudentDashboardQuery(
                "school-a",
                "student",
                "student-real",
                "student-victim"));

        assertThat(response.studentId()).isEqualTo("student-real");
        assertThat(response.knowledgeProgress())
                .extracting(StudentDashboardResponse.KnowledgeProgress::knowledgePointName)
                .containsExactly("函数定义域");
        assertThat(snapshotStore.saved.getFirst().studentId()).isEqualTo("student-real");
        assertThat(snapshotStore.saved.getFirst().knowledgeProgressJson()).doesNotContain("不应出现");
    }

    private static final class CapturingMemoryStore implements StudentMemoryStore {

        private final List<StudentMemoryEntry> entries = new ArrayList<>();

        @Override
        public StudentMemoryEntry save(StudentMemoryEntry entry) {
            entries.add(entry);
            return entry;
        }

        @Override
        public List<StudentMemoryEntry> candidates(String tenantId, String studentId) {
            return entries.stream()
                    .filter(entry -> tenantId.equals(entry.tenantId()))
                    .filter(entry -> "public".equals(entry.memoryScope()) || studentId.equals(entry.studentId()))
                    .toList();
        }
    }

    private static final class CapturingSnapshotStore implements StudentLearningSnapshotStore {

        private final List<StudentLearningSnapshotRecord> saved = new ArrayList<>();

        @Override
        public Optional<StudentLearningSnapshotRecord> findLatest(String tenantId, String studentId) {
            return Optional.empty();
        }

        @Override
        public StudentLearningSnapshotRecord save(StudentLearningSnapshotRecord record) {
            saved.add(record);
            return record;
        }
    }
}
