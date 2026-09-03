package com.doob.mathagent.teaching.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.doob.mathagent.agent.service.InMemoryAgentTraceStore;
import com.doob.mathagent.infrastructure.security.RequestSubject;
import com.doob.mathagent.teaching.TeachingTaskStatus;
import com.doob.mathagent.teaching.service.InMemoryTeachingTaskStore;
import com.doob.mathagent.teaching.service.PythonTeachingHandoutClient;
import com.doob.mathagent.teaching.service.TeachingHandoutTemplateService;
import com.doob.mathagent.teaching.service.TeachingWorkflowService;
import com.doob.mathagent.teaching.vo.TeachingTaskResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.web.server.ResponseStatusException;

/** 思考轨迹读边的权限合同：学生 403、非本人任务 404、教师只见投影字段且片段有上限。 */
class TeachingModelDiagnosticsControllerTest {

    private static TeachingWorkflowService workflow(String ownedTaskId) {
        return new TeachingWorkflowService(
                Path.of("."), null, new InMemoryTeachingTaskStore(), null, null, new InMemoryAgentTraceStore(),
                new TeachingHandoutTemplateService(), Optional.empty(), Optional.empty(), Runnable::run) {
            @Override
            public Optional<TeachingTaskResponse> get(String taskId,
                    com.doob.mathagent.teaching.TeachingRequestContext context) {
                // 真实服务按 (tenant, subjectType, subjectId) 隔离；替身复刻归属条件。
                boolean owned = ownedTaskId.equals(taskId) && "teacher".equals(context.subjectType())
                        && "teacher-1".equals(context.subjectId());
                return owned
                        ? Optional.of(new TeachingTaskResponse(
                                taskId, "client", "school-a", "teacher", "teacher-1", TeachingTaskStatus.COMPLETED,
                                "q", "二次函数顶点式", List.of(), List.of(), List.of(),
                                "\\section{师}", "\\section{生}", "\\section{讲}", List.of(), null, List.of(), null, null))
                        : Optional.empty();
            }
        };
    }

    private static PythonTeachingHandoutClient recordingClient(List<Integer> clampedExcerpts) {
        return new PythonTeachingHandoutClient(new MockEnvironment(), new ObjectMapper(), null, null) {
            @Override
            public List<Map<String, Object>> readModelDiagnostics(String runId, int excerptChars) {
                clampedExcerpts.add(excerptChars);
                return List.of(Map.of(
                        "recordId", "plan_writer:1:4",
                        "provider", "openai",
                        "finishReason", "stop",
                        "reasoningChars", 321,
                        "reasoningExcerpt", "先配方再判开口方向"));
            }
        };
    }

    @Test
    void teacherSeesProjectedReasoningTrace() {
        List<Integer> clamped = new java.util.ArrayList<>();
        TeachingModelDiagnosticsController controller = new TeachingModelDiagnosticsController(
                workflow("task-owned"), recordingClient(clamped),
                request -> new RequestSubject("school-a", "teacher", "teacher-1", "device"));

        List<Map<String, Object>> turns = controller.modelDiagnostics("task-owned", 1200, null);

        assertThat(turns).hasSize(1);
        assertThat(turns.getFirst().get("reasoningExcerpt")).isEqualTo("先配方再判开口方向");
        assertThat(clamped).containsExactly(1200);
    }

    @Test
    void studentIsForbiddenAndForeignTasksAreHidden() {
        List<Integer> ignored = new java.util.ArrayList<>();
        TeachingModelDiagnosticsController studentController = new TeachingModelDiagnosticsController(
                workflow("task-owned"), recordingClient(ignored),
                request -> new RequestSubject("school-a", "student", "student-1", "device"));
        assertThatThrownBy(() -> studentController.modelDiagnostics("task-owned", 1200, null))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        exception -> assertThat(exception.getStatusCode().value()).isEqualTo(403));

        TeachingModelDiagnosticsController foreign = new TeachingModelDiagnosticsController(
                workflow("task-owned"), recordingClient(ignored),
                request -> new RequestSubject("school-a", "teacher", "teacher-2", "device"));
        assertThatThrownBy(() -> foreign.modelDiagnostics("task-owned", 1200, null))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        exception -> assertThat(exception.getStatusCode().value()).isEqualTo(404));
        assertThat(ignored).isEmpty();
    }

    @Test
    void excerptRequestIsClampedToTheProjectionCeiling() {
        List<Integer> clamped = new java.util.ArrayList<>();
        TeachingModelDiagnosticsController controller = new TeachingModelDiagnosticsController(
                workflow("task-owned"), recordingClient(clamped),
                request -> new RequestSubject("school-a", "teacher", "teacher-1", "device"));

        controller.modelDiagnostics("task-owned", 999_999, null);

        assertThat(clamped).containsExactly(4_000);
    }
}
