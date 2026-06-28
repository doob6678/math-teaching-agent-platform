package com.doob.mathagent.securityrisk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.doob.mathagent.infrastructure.security.RequestSubject;
import com.doob.mathagent.securityrisk.controller.CapabilityTokenController;
import com.doob.mathagent.securityrisk.dto.CapabilityTokenApplyRequest;
import com.doob.mathagent.securityrisk.service.CapabilityTokenService;
import com.doob.mathagent.securityrisk.service.InMemoryCapabilityTokenStore;
import com.doob.mathagent.securityrisk.vo.CapabilityTokenResponse;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.server.ResponseStatusException;

class CapabilityTokenControllerTest {

    @Test
    void appliesCapabilityTokenForBackendResolvedSubject() {
        CapabilityTokenController controller = new CapabilityTokenController(
                new CapabilityTokenService(
                        new InMemoryCapabilityTokenStore(),
                        Clock.fixed(Instant.parse("2026-06-28T08:00:00Z"), ZoneOffset.UTC)),
                request -> new RequestSubject("school-a", "student", "student-001", "device-1"));
        MockHttpServletRequest httpRequest = new MockHttpServletRequest();
        httpRequest.addHeader("X-Subject-Id", "student-spoofed");

        CapabilityTokenResponse response = controller.apply(new CapabilityTokenApplyRequest(
                "teaching:submit",
                "/api/teaching/tasks",
                "hash-001",
                "task-001",
                0.2), httpRequest);

        assertThat(response.token()).isNotBlank();
        assertThat(response.expiresAt()).isEqualTo(Instant.parse("2026-06-28T08:02:00Z"));
    }

    @Test
    void unsupportedCapabilityActionReturnsBadRequest() {
        CapabilityTokenController controller = new CapabilityTokenController(
                new CapabilityTokenService(
                        new InMemoryCapabilityTokenStore(),
                        Clock.fixed(Instant.parse("2026-06-28T08:00:00Z"), ZoneOffset.UTC)),
                request -> new RequestSubject("school-a", "student", "student-001", "device-1"));

        assertThatThrownBy(() -> controller.apply(new CapabilityTokenApplyRequest(
                "teacher:archive-resource",
                "/api/teacher/resources/doc-1",
                "hash-001",
                "task-001",
                0.2), new MockHttpServletRequest()))
                .isInstanceOfSatisfying(ResponseStatusException.class, exception ->
                        assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void anonymousCapabilityApplicationReturnsForbidden() {
        CapabilityTokenController controller = new CapabilityTokenController(
                new CapabilityTokenService(
                        new InMemoryCapabilityTokenStore(),
                        Clock.fixed(Instant.parse("2026-06-28T08:00:00Z"), ZoneOffset.UTC)),
                request -> RequestSubject.anonymous("school-a", "device-1"));

        assertThatThrownBy(() -> controller.apply(new CapabilityTokenApplyRequest(
                "teaching:submit",
                "/api/teaching/tasks",
                "hash-001",
                "task-001",
                0.2), new MockHttpServletRequest()))
                .isInstanceOfSatisfying(ResponseStatusException.class, exception ->
                        assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
    }
}
