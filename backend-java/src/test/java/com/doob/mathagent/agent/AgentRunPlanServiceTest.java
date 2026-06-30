package com.doob.mathagent.agent;

import static org.assertj.core.api.Assertions.assertThat;

import com.doob.mathagent.agent.dto.AgentRunPlanRequest;
import com.doob.mathagent.agent.service.AgentRunPlanService;
import com.doob.mathagent.agent.vo.AgentRunPlanResponse;
import com.doob.mathagent.infrastructure.ai.AiProviderCatalog;
import com.doob.mathagent.infrastructure.ai.AiProviderProperties;
import com.doob.mathagent.infrastructure.security.RequestSubject;
import java.util.List;
import org.junit.jupiter.api.Test;

class AgentRunPlanServiceTest {

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
        assertThat(plan.capabilityRequired()).isFalse();
        assertThat(plan.stageTimings()).extracting(AgentRunPlanResponse.StageTiming::stage)
                .containsExactly("agent_policy", "model_route", "budget_guard");
        assertThat(plan.concurrencyKeys()).contains(
                "concurrent:user:student-001:StudentTutorAgent",
                "concurrent:tenant:school-a:StudentTutorAgent",
                "concurrent:model:qwen3.6-flash");
    }

    @Test
    void teacherCoursewareAgentRequiresCapabilityAndRejectsStudentWriteTool() {
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
        assertThat(plan.capabilityRequired()).isTrue();
        assertThat(plan.capabilityAction()).isEqualTo("agent-run:CoursewareAgent");
        assertThat(plan.allowedToolScopes()).containsExactly("tool:courseware:generate", "tool:search:private");
        assertThat(plan.deniedToolScopes()).containsExactly("tool:student:progress:write");
        assertThat(plan.allowedDataScopes()).containsExactly("TEACHER_PRIVATE", "CLASS_AUTHORIZED");
        assertThat(plan.deniedDataScopes()).containsExactly("STUDENT_PRIVATE");
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

        assertThat(plan.providerName()).isEqualTo("dashscope");
        assertThat(plan.modelCode()).isEqualTo("qwen3.6-flash");
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
    void ignoresUnknownPreferredModelAndKeepsDefaultProvider() {
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

        AgentRunPlanResponse plan = service.plan(
                request,
                new RequestSubject("school-a", "teacher", "teacher-001", "device-1"));

        assertThat(plan.providerName()).isEqualTo("dashscope");
        assertThat(plan.modelCode()).isEqualTo("qwen3.6-flash");
        assertThat(plan.routeReason()).contains("ignored preferred model");
    }

    private static AiProviderCatalog providerCatalog() {
        AiProviderProperties properties = new AiProviderProperties();
        properties.setDefaultProvider("dashscope");
        properties.getDashscope().setApiKey("dashscope-key");
        properties.getDashscope().setChatModel("qwen3.6-flash");
        properties.getOpenai().setApiKey("openai-key");
        properties.getOpenai().setChatModel("gpt-5.4");
        properties.getDeepseek().setApiKey("deepseek-key");
        properties.getDeepseek().setChatModel("deepseek-v4-flash");
        properties.getArk().setApiKey("ark-key");
        properties.getArk().setChatModel("doubao-seed-2-0-lite-260428");
        return new AiProviderCatalog(properties);
    }
}
