package com.doob.mathagent.memory;

import static org.assertj.core.api.Assertions.assertThat;

import com.doob.mathagent.memory.service.InMemoryStudentMemoryStore;
import com.doob.mathagent.memory.service.StudentMemoryCommand;
import com.doob.mathagent.memory.service.StudentMemoryReuseService;
import com.doob.mathagent.memory.vo.StudentMemoryResponse;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class StudentMemoryReuseServiceTest {

    private final Clock clock = Clock.fixed(Instant.parse("2026-06-28T08:00:00Z"), ZoneOffset.UTC);

    @Test
    void studentReusesOwnSimilarQuestionMemory() {
        StudentMemoryReuseService service = new StudentMemoryReuseService(new InMemoryStudentMemoryStore(), clock);
        service.remember(new StudentMemoryCommand(
                "default", "student", "student-001", "空间向量数量积怎么求夹角",
                "用 a·b=|a||b|cosθ 先求 cosθ。", "空间向量数量积", "private", false));

        StudentMemoryResponse response = service.reuse(new StudentMemoryCommand(
                "default", "student", "student-001", "空间向量数量积求夹角的方法",
                null, "空间向量数量积", "private", false));

        assertThat(response.reused()).isTrue();
        assertThat(response.reuseScope()).isEqualTo("private");
        assertThat(response.answer()).contains("cosθ");
        assertThat(response.timings()).extracting(StudentMemoryResponse.StageTiming::stage)
                .contains("normalize", "similarity_match", "reuse_decision");
    }

    @Test
    void studentCannotReuseAnotherStudentsPrivateMemory() {
        StudentMemoryReuseService service = new StudentMemoryReuseService(new InMemoryStudentMemoryStore(), clock);
        service.remember(new StudentMemoryCommand(
                "default", "student", "student-001", "分段函数定义域", "先分别看每段限制再取并集。", "分段函数", "private", false));

        StudentMemoryResponse response = service.reuse(new StudentMemoryCommand(
                "default", "student", "student-002", "分段函数定义域怎么求", null, "分段函数", "private", false));

        assertThat(response.reused()).isFalse();
        assertThat(response.reason()).contains("No reusable memory");
    }

    @Test
    void publicMemoryCanBeReusedAcrossStudents() {
        StudentMemoryReuseService service = new StudentMemoryReuseService(new InMemoryStudentMemoryStore(), clock);
        service.remember(new StudentMemoryCommand(
                "default", "teacher", "teacher-001", "函数单调性定义法", "任取 x1<x2，比较 f(x1) 与 f(x2)。", "函数单调性", "public", false));

        StudentMemoryResponse response = service.reuse(new StudentMemoryCommand(
                "default", "student", "student-003", "函数单调性定义法怎么写", null, "函数单调性", "private", false));

        assertThat(response.reused()).isTrue();
        assertThat(response.reuseScope()).isEqualTo("public");
    }

    @Test
    void studentRequestedPublicMemoryIsStoredAsPrivate() {
        StudentMemoryReuseService service = new StudentMemoryReuseService(new InMemoryStudentMemoryStore(), clock);
        StudentMemoryResponse stored = service.remember(new StudentMemoryCommand(
                "default", "student", "student-001", "domain of a piecewise function",
                "Check each branch condition first.", "piecewise function", "public", false));
        StudentMemoryResponse otherStudent = service.reuse(new StudentMemoryCommand(
                "default", "student", "student-002", "how to find a piecewise function domain",
                null, "piecewise function", "private", false));

        assertThat(stored.reuseScope()).isEqualTo("private");
        assertThat(otherStudent.reused()).isFalse();
    }

    @Test
    void expiredOrExplicitlyBypassedMemoryIsNotReused() {
        StudentMemoryReuseService service = new StudentMemoryReuseService(new InMemoryStudentMemoryStore(), clock);
        service.remember(new StudentMemoryCommand(
                "default", "student", "student-001", "立体几何线面垂直证明",
                "先证线垂直平面内两条相交直线。", "立体几何", "private", false));

        StudentMemoryResponse bypassed = service.reuse(new StudentMemoryCommand(
                "default", "student", "student-001", "立体几何线面垂直证明", null, "立体几何", "private", true));

        assertThat(bypassed.reused()).isFalse();
        assertThat(bypassed.reason()).contains("bypass");
    }

    @Test
    void semanticReuseStillRejectsChangedNumericParameters() {
        StudentMemoryReuseService service = new StudentMemoryReuseService(
                new InMemoryStudentMemoryStore(), clock, null, true, false, 0.0d);
        service.remember(new StudentMemoryCommand(
                "default", "student", "student-001", "求二次函数 y=x^2+1 的最小值",
                "顶点纵坐标是 1。", "二次函数", "private", false));

        StudentMemoryResponse response = service.reuse(new StudentMemoryCommand(
                "default", "student", "student-001", "求二次函数 y=x^2+2 的最小值",
                null, "二次函数", "private", false));

        assertThat(response.reused()).isFalse();
    }
}
