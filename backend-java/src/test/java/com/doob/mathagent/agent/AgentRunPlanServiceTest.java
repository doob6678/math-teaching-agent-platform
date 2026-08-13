package com.doob.mathagent.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.doob.mathagent.agent.dto.AgentRunPlanRequest;
import com.doob.mathagent.agent.service.AgentRunPlanService;
import com.doob.mathagent.agent.vo.AgentRunPlanResponse;
import com.doob.mathagent.infrastructure.ai.AiProviderCatalog;
import com.doob.mathagent.infrastructure.ai.AiModelPriceCatalog;
import com.doob.mathagent.infrastructure.ai.AiProviderProperties;
import com.doob.mathagent.infrastructure.security.RequestSubject;
import java.util.List;
import org.junit.jupiter.api.Test;

class AgentRunPlanServiceTest {

    @Test
    void usesConfiguredProviderModelPricesAndRejectsRawTokenOverflow() {
        AgentRunPlanService service = new AgentRunPlanService(
                providerCatalog(),
                new AiModelPriceCatalog("{\"dashscope/qwen3.6-flash\":{\"inputPerMillion\":1.0,\"outputPerMillion\":3.0}}"));
                AgentRunPlanResponse priced = service.plan(new AgentRunPlanRequest(
                        "CoursewareAgent", "courseware_generation", "teacher", 3000, 1600, false, true,
                        "medium", "normal", 0.01, 0, true, List.of(), List.of(), List.of("TEACHER_PRIVATE"), true,
                        "dashscope", "qwen3.6-flash"),
                new RequestSubject("school-a", "teacher", "teacher-001", "device-1"));

        assertThat(priced.estimatedCost()).isEqualTo(0.0078d);
        assertThat(priced.withinBudget()).isTrue();

        AgentRunPlanResponse overflow = service.plan(new AgentRunPlanRequest(
                        "CoursewareAgent", "courseware_generation", "teacher", 12001, 1600, false, true,
                        "medium", "normal", 3.0, 0, true, List.of(), List.of(), List.of("TEACHER_PRIVATE"), true),
                new RequestSubject("school-a", "teacher", "teacher-001", "device-1"));
        assertThat(overflow.withinBudget()).isFalse();
    }

    @Test
    void plansStudentTutorWithOnlyStudentSafeToolsAndDataScopes() {
        AgentRunPlanService service = new AgentRunPlanService(providerCatalog());
        AgentRunPlanRequest request = new AgentRunPlanRequest(
                "StudentTutorAgent",
                "question_solving",
                "free",
                5000,
                2000,
                false,
                true,
                "hard",
                "normal",
                0.08,
                0,
                false,
                List.of("tool:search:textbook", "tool:student:progress:read", "tool:knowledge:write"),
                List.of(),
                List.of("PUBLIC_TEXTBOOK", "STUDENT_PRIVATE", "TEACHER_PRIVATE"),
                false);

        AgentRunPlanResponse plan = service.plan(
                request,
                new RequestSubject("school-a", "student", "student-001", "device-1"));

        assertThat(plan.agentCode()).isEqualTo("StudentTutorAgent");
        assertThat(plan.providerName()).isEqualTo("dashscope");
        assertThat(plan.modelLevel()).isEqualTo("reasoning");
        assertThat(plan.allowedToolScopes()).containsExactly("tool:search:textbook", "tool:student:progress:read");
        assertThat(plan.deniedToolScopes()).containsExactly("tool:knowledge:write");
        assertThat(plan.allowedDataScopes()).containsExactly("PUBLIC_TEXTBOOK", "STUDENT_PRIVATE");
        assertThat(plan.deniedDataScopes()).containsExactly("TEACHER_PRIVATE");
        assertThat(plan.maxInputTokens()).isEqualTo(2400);
        assertThat(plan.maxOutputTokens()).isEqualTo(900);
        assertThat(plan.stageTimings()).extracting(AgentRunPlanResponse.StageTiming::stage)
                .containsExactly("agent_policy", "model_route", "budget_guard");
        assertThat(plan.concurrencyKeys()).contains(
                "concurrent:user:student-001:StudentTutorAgent",
                "concurrent:tenant:school-a:StudentTutorAgent",
                "concurrent:model:qwen3.6-flash");
    }

    @Test
    void teacherCoursewareAgentUsesBackendPolicyAndRejectsStudentWriteTool() {
        AgentRunPlanService service = new AgentRunPlanService(providerCatalog());
        AgentRunPlanRequest request = new AgentRunPlanRequest(
                "CoursewareAgent",
                "courseware_generation",
                "teacher",
                3000,
                1600,
                false,
                true,
                "medium",
                "normal",
                2.5,
                0,
                true,
                List.of("tool:courseware:generate", "tool:search:private", "tool:student:progress:write"),
                List.of(),
                List.of("TEACHER_PRIVATE", "CLASS_AUTHORIZED", "STUDENT_PRIVATE"),
                true);

        AgentRunPlanResponse plan = service.plan(
                request,
                new RequestSubject("school-a", "teacher", "teacher-001", "device-1"));

        assertThat(plan.agentCode()).isEqualTo("CoursewareAgent");
        assertThat(plan.allowedToolScopes()).containsExactly("tool:courseware:generate", "tool:search:private");
        assertThat(plan.deniedToolScopes()).containsExactly("tool:student:progress:write");
        assertThat(plan.allowedDataScopes()).containsExactly("TEACHER_PRIVATE", "CLASS_AUTHORIZED");
        assertThat(plan.deniedDataScopes()).containsExactly("STUDENT_PRIVATE");
        assertThat(plan.maxOutputTokens()).isEqualTo(8000);
        assertThat(plan.withinBudget()).isTrue();
    }

    @Test
    void teacherQuestionBranchReservesReasoningAllowanceWithoutExpandingStudentLimits() {
        AgentRunPlanService service = new AgentRunPlanService(providerCatalog());
        AgentRunPlanRequest request = new AgentRunPlanRequest(
                "TeacherAssistantAgent",
                "question_solving",
                "teacher",
                1200,
                320,
                false,
                true,
                "medium",
                "normal",
                3.0,
                0,
                false,
                List.of(),
                List.of(),
                List.of("PUBLIC_TEXTBOOK", "TEACHER_PRIVATE"),
                false);

        AgentRunPlanResponse plan = service.plan(
                request,
                new RequestSubject("school-a", "teacher", "teacher-001", "device-1"));

        assertThat(plan.maxOutputTokens()).isEqualTo(6000);
        assertThat(plan.withinBudget()).isTrue();
    }

    @Test
    void removesUserDisabledToolsFromDynamicInjectionPlan() {
        AgentRunPlanService service = new AgentRunPlanService(providerCatalog());
        AgentRunPlanRequest request = new AgentRunPlanRequest(
                "CoursewareAgent",
                "courseware_generation",
                "teacher",
                3000,
                1600,
                false,
                true,
                "medium",
                "normal",
                2.5,
                0,
                true,
                List.of("tool:courseware:generate", "tool:search:private", "tool:student:progress:write"),
                List.of("tool:search:private"),
                List.of("TEACHER_PRIVATE", "CLASS_AUTHORIZED"),
                true);

        AgentRunPlanResponse plan = service.plan(
                request,
                new RequestSubject("school-a", "teacher", "teacher-001", "device-1"));

        assertThat(plan.allowedToolScopes()).containsExactly("tool:courseware:generate");
        assertThat(plan.deniedToolScopes()).containsExactly("tool:search:private", "tool:student:progress:write");
        assertThat(plan.toolPolicyDecisions())
                .extracting(AgentRunPlanResponse.ToolPolicyDecision::scope)
                .containsExactly("tool:courseware:generate", "tool:search:private", "tool:student:progress:write");
        assertThat(plan.toolPolicyDecisions())
                .extracting(AgentRunPlanResponse.ToolPolicyDecision::decision)
                .containsExactly("ALLOWED", "DISABLED_BY_USER", "DENIED_BY_AGENT_POLICY");
    }

    @Test
    void switchesToFallbackProviderAfterRepeatedFailures() {
        AgentRunPlanService service = new AgentRunPlanService(providerCatalog());
        AgentRunPlanRequest request = new AgentRunPlanRequest(
                "QualityCheckAgent",
                "quality_check",
                "teacher",
                1200,
                600,
                false,
                false,
                "medium",
                "low",
                0.5,
                2,
                true,
                List.of("tool:quality:check"),
                List.of(),
                List.of("PUBLIC_TEXTBOOK"),
                false);

        AgentRunPlanResponse plan = service.plan(
                request,
                new RequestSubject("school-a", "teacher", "teacher-001", "device-1"));

        assertThat(plan.providerName()).isEqualTo("openai");
        assertThat(plan.modelCode()).isEqualTo("gpt-5.4");
        assertThat(plan.modelLevel()).isEqualTo("json_stable");
        assertThat(plan.routeReason()).contains("fallback");
    }

    @Test
    void honorsBackendValidatedPreferredProviderAndModel() {
        AgentRunPlanService service = new AgentRunPlanService(providerCatalog());
        AgentRunPlanRequest request = new AgentRunPlanRequest(
                "CoursewareAgent",
                "courseware_generation",
                "teacher",
                3000,
                1600,
                false,
                true,
                "medium",
                "normal",
                2.5,
                0,
                true,
                List.of("tool:courseware:generate"),
                List.of(),
                List.of("TEACHER_PRIVATE"),
                true,
                "openai",
                "gpt-5.4-mini");

        AgentRunPlanResponse plan = service.plan(
                request,
                new RequestSubject("school-a", "teacher", "teacher-001", "device-1"));

        assertThat(plan.providerName()).isEqualTo("openai");
        assertThat(plan.modelCode()).isEqualTo("gpt-5.4-mini");
        assertThat(plan.routeReason()).contains("preferred model");
    }

    @Test
    void rejectsUnknownExplicitWritingModelInsteadOfSilentlyChangingRoute() {
        AgentRunPlanService service = new AgentRunPlanService(providerCatalog());
        AgentRunPlanRequest request = new AgentRunPlanRequest(
                "CoursewareAgent",
                "courseware_generation",
                "teacher",
                3000,
                1600,
                false,
                true,
                "medium",
                "normal",
                2.5,
                0,
                true,
                List.of("tool:courseware:generate"),
                List.of(),
                List.of("TEACHER_PRIVATE"),
                true,
                "openai",
                "drop table agent_trace");

        assertThatThrownBy(() -> service.plan(
                request,
                new RequestSubject("school-a", "teacher", "teacher-001", "device-1")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not enabled or allow-listed");
    }

    private static AiProviderCatalog providerCatalog() {
        AiProviderProperties properties = new AiProviderProperties();
        properties.setDefaultProvider("dashscope");
        properties.getDashscope().setEnabled(true);
        properties.getDashscope().setChatModel("qwen3.6-flash");
        properties.getOpenai().setEnabled(true);
        properties.getOpenai().setChatModel("gpt-5.4");
        properties.getDeepseek().setEnabled(true);
        properties.getDeepseek().setChatModel("deepseek-v4-flash");
        properties.getArk().setEnabled(true);
        properties.getArk().setChatModel("doubao-seed-2-0-lite-260428");
        return new AiProviderCatalog(properties);
    }
}
