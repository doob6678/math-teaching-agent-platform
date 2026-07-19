package com.doob.mathagent.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.FilterChain;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class ApiAccessControlFilterTest {

    @Test
    void spoofedSubjectHeadersDoNotGrantTeacherAccess() throws Exception {
        ApiAccessControlService service = new ApiAccessControlService(
                FixedWindowRateLimiter.empty(),
                Clock.fixed(Instant.parse("2026-06-28T10:00:00Z"), ZoneOffset.UTC),
                ApiAccessPolicy.defaultRules());
        ApiAccessControlFilter filter = new ApiAccessControlFilter(
                service,
                request -> RequestSubject.anonymous("default", "device-1"));
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/teacher/resources");
        request.addHeader("X-Subject-Type", "teacher");
        request.addHeader("X-Subject-Id", "teacher-spoofed");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean continued = new AtomicBoolean(false);
        FilterChain chain = (servletRequest, servletResponse) -> continued.set(true);

        filter.doFilter(request, response, chain);

        assertThat(continued).isFalse();
        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentAsString()).contains("API_ACCESS_DENIED");
    }

    @Test
    void teachingSubmitIsUserLevelEndpoint() {
        ApiAccessRule rule = ApiAccessPolicy.defaultRules()
                .findRule("/api/teaching/tasks")
                .orElseThrow();

        assertThat(rule.level()).isEqualTo(ApiAccessLevel.USER);
    }

    @Test
    void multiAgentWritingIsTeacherUserEndpoint() {
        ApiAccessRule rule = ApiAccessPolicy.defaultRules()
                .findRule("/api/agents/writing/courseware/async")
                .orElseThrow();

        assertThat(rule.level()).isEqualTo(ApiAccessLevel.USER);
        assertThat(rule.allowedSubjectTypes()).containsExactlyInAnyOrder("teacher", "admin");
    }
}
