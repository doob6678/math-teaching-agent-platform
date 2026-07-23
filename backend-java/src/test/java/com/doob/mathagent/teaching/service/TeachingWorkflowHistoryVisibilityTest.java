package com.doob.mathagent.teaching.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.doob.mathagent.teaching.TeachingTaskStatus;
import com.doob.mathagent.teaching.vo.TeachingTaskResponse;
import java.util.List;
import org.junit.jupiter.api.Test;

class TeachingWorkflowHistoryVisibilityTest {

    @Test
    void displaysCompletedTasksOnlyWhenRealHandoutContentExists() {
        assertThat(TeachingWorkflowService.isFrontendDisplayableTask(task(
                TeachingTaskStatus.COMPLETED,
                "反比例函数学生讲义",
                "\\section{反比例函数}\\n$y=\\frac{k}{x}$，整理定义、图像、性质和课堂练习。",
                "",
                null))).isTrue();

        assertThat(TeachingWorkflowService.isFrontendDisplayableTask(task(
                TeachingTaskStatus.RUNNING,
                "反比例函数学生讲义",
                "\\section{反比例函数}\\n$y=\\frac{k}{x}$，整理定义、图像、性质和课堂练习。",
                "",
                null))).isFalse();

        assertThat(TeachingWorkflowService.isFrontendDisplayableTask(task(
                TeachingTaskStatus.FAILED,
                "反比例函数学生讲义",
                "\\section{反比例函数}\\n已完成检索，等待恢复生成。",
                "",
                null))).isTrue();

        assertThat(TeachingWorkflowService.isFrontendDisplayableTask(task(
                TeachingTaskStatus.COMPLETED,
                "反比例函数学生讲义",
                "",
                "",
                new TeachingTaskResponse.AiDraft(
                        true,
                        "openai",
                        "gpt-5.5",
                        1,
                        1,
                        2,
                        "只有 AI 草稿，没有可下载讲义正文。",
                        "ok",
                        true,
                        "教师草稿内容很长，但不能作为历史讲义入口。",
                        "学生草稿内容很长，但不能作为历史讲义入口。",
                        List.of(),
                        List.of(),
                        "",
                        0,
                        1,
                        false,
                        List.of())))).isFalse();
    }

    @Test
    void hidesProtocolDebugAndSafetyProbeTasksFromHistory() {
        assertThat(TeachingWorkflowService.isFrontendDisplayableTask(task(
                TeachingTaskStatus.COMPLETED,
                "只做安全探针，不做题目生成",
                "\\section{capability requestHash idempotencyKey MODEL_CALL JSON_PARSE}",
                "",
                null))).isFalse();

        assertThat(TeachingWorkflowService.isFrontendDisplayableTask(task(
                TeachingTaskStatus.COMPLETED,
                "双曲线讲义",
                "\\section{双曲线}\\nMCP bearer subject type api access 调试信息。",
                "",
                null))).isFalse();
    }

    private static TeachingTaskResponse task(
            TeachingTaskStatus status,
            String learningGoal,
            String teacherHandout,
            String studentHandout,
            TeachingTaskResponse.AiDraft aiDraft) {
        return new TeachingTaskResponse(
                "task-1",
                "request-1",
                "tenant-a",
                "teacher",
                "teacher-1",
                status,
                "",
                learningGoal,
                List.of(),
                List.of(),
                List.of(),
                "",
                teacherHandout,
                studentHandout,
                List.of(),
                null,
                List.of(),
                aiDraft,
                null);
    }
}
