package com.doob.mathagent.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

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
    void teachingBatchZipRequiresLoggedInSubjectAndHasOwnRateLimitBucket() {
        ApiAccessControlService service = new ApiAccessControlService(
                FixedWindowRateLimiter.empty(),
                Clock.fixed(Instant.parse("2026-06-28T10:00:00Z"), ZoneOffset.UTC),
                ApiAccessPolicy.defaultRules());
        ApiRequestIdentity anonymousCreate = new ApiRequestIdentity(
                "POST",
                "/api/teaching/handouts/batch/zip",
                "default",
                "anonymous",
                null,
                "127.0.0.1",
                "device-1",
                "JUnit");
        ApiRequestIdentity studentCreate = new ApiRequestIdentity(
                "POST",
                "/api/teaching/handouts/batch/zip",
                "default",
                "student",
                "student-1",
                "127.0.0.1",
                "device-1",
                "JUnit");
        ApiRequestIdentity teacherDownload = new ApiRequestIdentity(
                "GET",
                "/api/teaching/handouts/batch/zip/batch-1/download",
                "default",
                "teacher",
                "teacher-1",
                "127.0.0.1",
                "device-2",
                "JUnit");

        ApiAccessDecision denied = service.evaluate(anonymousCreate);
        ApiAccessDecision studentAllowed = service.evaluate(studentCreate);
        ApiAccessDecision teacherAllowed = service.evaluate(teacherDownload);

        assertThat(denied.allowed()).isFalse();
        assertThat(denied.httpStatus()).isEqualTo(403);
        assertThat(studentAllowed.allowed()).isTrue();
        assertThat(studentAllowed.level()).isEqualTo(ApiAccessLevel.USER);
        assertThat(studentAllowed.limit()).isEqualTo(10);
        assertThat(teacherAllowed.allowed()).isTrue();
        assertThat(teacherAllowed.level()).isEqualTo(ApiAccessLevel.USER);
        assertThat(teacherAllowed.limit()).isEqualTo(10);
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
    void studentDashboardRequiresLoggedInStudentTeacherOrAdmin() {
        ApiAccessControlService service = new ApiAccessControlService(
                FixedWindowRateLimiter.empty(),
                Clock.fixed(Instant.parse("2026-06-28T10:00:00Z"), ZoneOffset.UTC),
                ApiAccessPolicy.defaultRules());
        ApiRequestIdentity anonymous = new ApiRequestIdentity(
                "GET",
                "/api/students/dashboard",
                "default",
                "anonymous",
                null,
                "127.0.0.1",
                "device-1",
                "JUnit");
        ApiRequestIdentity teacher = new ApiRequestIdentity(
                "GET",
                "/api/students/dashboard",
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
        assertThat(allowed.limit()).isEqualTo(40);
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

    @Test
    void agentTraceQueryRequiresLoggedInStudentTeacherOrAdmin() {
        ApiAccessControlService service = new ApiAccessControlService(
                FixedWindowRateLimiter.empty(),
                Clock.fixed(Instant.parse("2026-06-28T10:00:00Z"), ZoneOffset.UTC),
                ApiAccessPolicy.defaultRules());
        ApiRequestIdentity anonymous = new ApiRequestIdentity(
                "GET",
                "/api/agents/traces",
                "default",
                "anonymous",
                null,
                "127.0.0.1",
                "device-1",
                "JUnit");
        ApiRequestIdentity student = new ApiRequestIdentity(
                "GET",
                "/api/agents/traces",
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
    void everyApiControllerPathIsCoveredByAccessPolicy() throws Exception {
        ApiAccessPolicy policy = ApiAccessPolicy.defaultRules();
        List<String> uncovered = new ArrayList<>();

        for (Class<?> controller : restControllerClasses()) {
            for (Method method : controller.getDeclaredMethods()) {
                for (String path : apiPaths(method)) {
                    if (policy.findRule(path).isEmpty()) {
                        uncovered.add(controller.getSimpleName() + "#" + method.getName() + " -> " + path);
                    }
                }
            }
        }

        assertThat(uncovered).isEmpty();
    }

    /**
     * Scans production controllers so new /api endpoints must declare a global access policy rule.
     */
    private static List<Class<?>> restControllerClasses() throws ClassNotFoundException {
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));
        List<Class<?>> controllers = new ArrayList<>();
        for (var component : scanner.findCandidateComponents("com.doob.mathagent")) {
            controllers.add(Class.forName(component.getBeanClassName()));
        }
        return controllers;
    }

    /**
     * Extracts API paths from the Spring mapping annotations used by current controllers.
     */
    private static List<String> apiPaths(Method method) {
        List<String> paths = new ArrayList<>();
        addValues(paths, method.getAnnotation(GetMapping.class));
        addValues(paths, method.getAnnotation(PostMapping.class));
        addValues(paths, method.getAnnotation(PutMapping.class));
        addValues(paths, method.getAnnotation(DeleteMapping.class));
        addValues(paths, method.getAnnotation(RequestMapping.class));
        return paths.stream()
                .filter(path -> path.startsWith("/api/"))
                .toList();
    }

    /**
     * Adds GetMapping values to the collected path list.
     */
    private static void addValues(List<String> paths, GetMapping mapping) {
        if (mapping != null) {
            addPathValues(paths, mapping.value(), mapping.path());
        }
    }

    /**
     * Adds PostMapping values to the collected path list.
     */
    private static void addValues(List<String> paths, PostMapping mapping) {
        if (mapping != null) {
            addPathValues(paths, mapping.value(), mapping.path());
        }
    }

    /**
     * Adds PutMapping values to the collected path list.
     */
    private static void addValues(List<String> paths, PutMapping mapping) {
        if (mapping != null) {
            addPathValues(paths, mapping.value(), mapping.path());
        }
    }

    /**
     * Adds DeleteMapping values to the collected path list.
     */
    private static void addValues(List<String> paths, DeleteMapping mapping) {
        if (mapping != null) {
            addPathValues(paths, mapping.value(), mapping.path());
        }
    }

    /**
     * Adds RequestMapping values to the collected path list.
     */
    private static void addValues(List<String> paths, RequestMapping mapping) {
        if (mapping != null) {
            addPathValues(paths, mapping.value(), mapping.path());
        }
    }

    /**
     * Adds annotation value/path aliases while ignoring empty class-level mappings.
     */
    private static void addPathValues(List<String> paths, String[] values, String[] aliases) {
        for (String value : values) {
            if (!value.isBlank()) {
                paths.add(value);
            }
        }
        for (String alias : aliases) {
            if (!alias.isBlank()) {
                paths.add(alias);
            }
        }
    }
}
