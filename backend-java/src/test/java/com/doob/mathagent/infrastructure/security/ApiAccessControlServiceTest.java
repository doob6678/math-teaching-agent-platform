package com.doob.mathagent.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class ApiAccessControlServiceTest {

    @Test
    void deniesAuditEndpointWhenSubjectIsAnonymous() {
        ApiAccessControlService service = new ApiAccessControlService(
                FixedWindowRateLimiter.empty(),
                Clock.fixed(Instant.parse("2026-06-28T10:00:00Z"), ZoneOffset.UTC),
                ApiAccessPolicy.defaultRules());
        ApiRequestIdentity identity = new ApiRequestIdentity(
                "GET",
                "/api/retrieval/audit/query-1",
                "default",
                "anonymous",
                null,
                "127.0.0.1",
                "device-1",
                "JUnit");

        ApiAccessDecision decision = service.evaluate(identity);

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.httpStatus()).isEqualTo(403);
        assertThat(decision.reason()).contains("teacher");
    }

    @Test
    void capabilityAuditQueryRequiresTeacherOrAdminSubject() {
        ApiAccessControlService service = new ApiAccessControlService(
                FixedWindowRateLimiter.empty(),
                Clock.fixed(Instant.parse("2026-06-28T10:00:00Z"), ZoneOffset.UTC),
                ApiAccessPolicy.defaultRules());
        ApiRequestIdentity student = new ApiRequestIdentity(
                "GET",
                "/api/security/capability-audits",
                "default",
                "student",
                "student-1",
                "127.0.0.1",
                "device-1",
                "JUnit");
        ApiRequestIdentity teacher = new ApiRequestIdentity(
                "GET",
                "/api/security/capability-audits",
                "default",
                "teacher",
                "teacher-1",
                "127.0.0.1",
                "device-1",
                "JUnit");

        ApiAccessDecision denied = service.evaluate(student);
        ApiAccessDecision allowed = service.evaluate(teacher);

        assertThat(denied.allowed()).isFalse();
        assertThat(denied.httpStatus()).isEqualTo(403);
        assertThat(allowed.allowed()).isTrue();
        assertThat(allowed.level()).isEqualTo(ApiAccessLevel.ADMIN);
    }

    @Test
    void limitsSearchEndpointByDeviceAndEndpointWindow() {
        ApiAccessControlService service = new ApiAccessControlService(
                FixedWindowRateLimiter.empty(),
                Clock.fixed(Instant.parse("2026-06-28T10:00:00Z"), ZoneOffset.UTC),
                ApiAccessPolicy.defaultRulesForTests(2));
        ApiRequestIdentity identity = new ApiRequestIdentity(
                "GET",
                "/api/retrieval/textbooks/search",
                "default",
                "guest",
                "guest-1",
                "127.0.0.1",
                "device-1",
                "JUnit");

        assertThat(service.evaluate(identity).allowed()).isTrue();
        assertThat(service.evaluate(identity).allowed()).isTrue();
        ApiAccessDecision blocked = service.evaluate(identity);

        assertThat(blocked.allowed()).isFalse();
        assertThat(blocked.httpStatus()).isEqualTo(429);
        assertThat(blocked.limit()).isEqualTo(2);
        assertThat(blocked.used()).isEqualTo(3);
    }

    @Test
    void isolatesTeachingTaskEndpointFromAnonymousSubject() {
        ApiAccessControlService service = new ApiAccessControlService(
                FixedWindowRateLimiter.empty(),
                Clock.fixed(Instant.parse("2026-06-28T10:00:00Z"), ZoneOffset.UTC),
                ApiAccessPolicy.defaultRules());
        ApiRequestIdentity anonymous = new ApiRequestIdentity(
                "POST",
                "/api/teaching/tasks",
                "default",
                "anonymous",
                null,
                "127.0.0.1",
                "device-1",
                "JUnit");
        ApiRequestIdentity teacher = new ApiRequestIdentity(
                "POST",
                "/api/teaching/tasks",
                "default",
                "teacher",
                "teacher-1",
                "127.0.0.1",
                "device-1",
                "JUnit");

        ApiAccessDecision denied = service.evaluate(anonymous);
        ApiAccessDecision allowed = service.evaluate(teacher);

        assertThat(denied.allowed()).isFalse();
        assertThat(denied.httpStatus()).isEqualTo(403);
        assertThat(allowed.allowed()).isTrue();
        assertThat(allowed.limit()).isEqualTo(20);
    }

    @Test
    void teacherResourcesRequireTeacherOrAdminSubject() {
        ApiAccessControlService service = new ApiAccessControlService(
                FixedWindowRateLimiter.empty(),
                Clock.fixed(Instant.parse("2026-06-28T10:00:00Z"), ZoneOffset.UTC),
                ApiAccessPolicy.defaultRules());
        ApiRequestIdentity student = new ApiRequestIdentity(
                "POST",
                "/api/teacher/resources",
                "default",
                "student",
                "student-1",
                "127.0.0.1",
                "device-1",
                "JUnit");
        ApiRequestIdentity teacher = new ApiRequestIdentity(
                "POST",
                "/api/teacher/resources",
                "default",
                "teacher",
                "teacher-1",
                "127.0.0.1",
                "device-1",
                "JUnit");

        ApiAccessDecision denied = service.evaluate(student);
        ApiAccessDecision allowed = service.evaluate(teacher);

        assertThat(denied.allowed()).isFalse();
        assertThat(denied.httpStatus()).isEqualTo(403);
        assertThat(allowed.allowed()).isTrue();
        assertThat(allowed.level()).isEqualTo(ApiAccessLevel.ADMIN);
    }

    @Test
    void studentMemoryRequiresLoggedInStudentTeacherOrAdmin() {
        ApiAccessControlService service = new ApiAccessControlService(
                FixedWindowRateLimiter.empty(),
                Clock.fixed(Instant.parse("2026-06-28T10:00:00Z"), ZoneOffset.UTC),
                ApiAccessPolicy.defaultRules());
        ApiRequestIdentity anonymous = new ApiRequestIdentity(
                "POST",
                "/api/students/memory/reuse",
                "default",
                "anonymous",
                null,
                "127.0.0.1",
                "device-1",
                "JUnit");
        ApiRequestIdentity student = new ApiRequestIdentity(
                "POST",
                "/api/students/memory/reuse",
                "default",
                "student",
                "student-1",
                "127.0.0.1",
                "device-1",
                "JUnit");

        ApiAccessDecision denied = service.evaluate(anonymous);
        ApiAccessDecision allowed = service.evaluate(student);

        assertThat(denied.allowed()).isFalse();
        assertThat(denied.httpStatus()).isEqualTo(403);
        assertThat(allowed.allowed()).isTrue();
        assertThat(allowed.level()).isEqualTo(ApiAccessLevel.USER);
    }

    @Test
    void agentRunPlanRequiresLoggedInStudentTeacherOrAdmin() {
        ApiAccessControlService service = new ApiAccessControlService(
                FixedWindowRateLimiter.empty(),
                Clock.fixed(Instant.parse("2026-06-28T10:00:00Z"), ZoneOffset.UTC),
                ApiAccessPolicy.defaultRules());
        ApiRequestIdentity anonymous = new ApiRequestIdentity(
                "POST",
                "/api/agents/run-plan",
                "default",
                "anonymous",
                null,
                "127.0.0.1",
                "device-1",
                "JUnit");
        ApiRequestIdentity student = new ApiRequestIdentity(
                "POST",
                "/api/agents/run-plan",
                "default",
                "student",
                "student-1",
                "127.0.0.1",
                "device-1",
                "JUnit");

        ApiAccessDecision denied = service.evaluate(anonymous);
        ApiAccessDecision allowed = service.evaluate(student);

        assertThat(denied.allowed()).isFalse();
        assertThat(denied.httpStatus()).isEqualTo(403);
        assertThat(allowed.allowed()).isTrue();
        assertThat(allowed.level()).isEqualTo(ApiAccessLevel.USER);
    }

    @Test
    void agentExecuteRequiresLoggedInStudentTeacherOrAdmin() {
        ApiAccessControlService service = new ApiAccessControlService(
                FixedWindowRateLimiter.empty(),
                Clock.fixed(Instant.parse("2026-06-28T10:00:00Z"), ZoneOffset.UTC),
                ApiAccessPolicy.defaultRules());
        ApiRequestIdentity anonymous = new ApiRequestIdentity(
                "POST",
                "/api/agents/execute",
                "default",
                "anonymous",
                null,
                "127.0.0.1",
                "device-1",
                "JUnit");
        ApiRequestIdentity teacher = new ApiRequestIdentity(
                "POST",
                "/api/agents/execute",
                "default",
                "teacher",
                "teacher-1",
                "127.0.0.1",
                "device-1",
                "JUnit");

        ApiAccessDecision denied = service.evaluate(anonymous);
        ApiAccessDecision allowed = service.evaluate(teacher);

        assertThat(denied.allowed()).isFalse();
        assertThat(denied.httpStatus()).isEqualTo(403);
        assertThat(allowed.allowed()).isTrue();
        assertThat(allowed.level()).isEqualTo(ApiAccessLevel.USER);
    }
}
