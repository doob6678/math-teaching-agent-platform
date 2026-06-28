package com.doob.mathagent.securityrisk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.doob.mathagent.infrastructure.security.RequestSubject;
import com.doob.mathagent.securityrisk.dto.CapabilityTokenApplyRequest;
import com.doob.mathagent.securityrisk.service.CapabilityTokenService;
import com.doob.mathagent.securityrisk.service.InMemoryCapabilityTokenStore;
import com.doob.mathagent.securityrisk.vo.CapabilityTokenResponse;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class CapabilityTokenServiceTest {

    private final Clock clock = Clock.fixed(Instant.parse("2026-06-28T08:00:00Z"), ZoneOffset.UTC);

    @Test
    void issuesCapabilityTokenBoundToSubjectActionPathAndRequestHash() {
        CapabilityTokenService service = new CapabilityTokenService(new InMemoryCapabilityTokenStore(), clock);

        CapabilityTokenResponse response = service.apply(new CapabilityTokenApplyRequest(
                "teaching:submit",
                "/api/teaching/tasks",
                "hash-001",
                "task-001",
                0.2), new RequestSubject("school-a", "student", "student-001", "device-1"));

        assertThat(response.token()).isNotBlank();
        assertThat(response.action()).isEqualTo("teaching:submit");
        assertThat(response.requestHash()).isEqualTo("hash-001");
        assertThat(response.expiresAt()).isEqualTo(Instant.parse("2026-06-28T08:02:00Z"));
    }

    @Test
    void consumesCapabilityTokenOnlyOnceForMatchingRequestHash() {
        CapabilityTokenService service = new CapabilityTokenService(new InMemoryCapabilityTokenStore(), clock);
        CapabilityTokenResponse response = service.apply(new CapabilityTokenApplyRequest(
                "teaching:submit",
                "/api/teaching/tasks",
                "hash-001",
                "task-001",
                0.2), new RequestSubject("school-a", "student", "student-001", "device-1"));

        assertThat(service.consume(
                response.token(),
                "teaching:submit",
                "/api/teaching/tasks",
                "hash-001",
                new RequestSubject("school-a", "student", "student-001", "device-1")).allowed()).isTrue();
        assertThat(service.consume(
                response.token(),
                "teaching:submit",
                "/api/teaching/tasks",
                "hash-001",
                new RequestSubject("school-a", "student", "student-001", "device-1")).allowed()).isFalse();
    }

    @Test
    void rejectsTokenWhenSubjectOrHashDoesNotMatch() {
        CapabilityTokenService service = new CapabilityTokenService(new InMemoryCapabilityTokenStore(), clock);
        CapabilityTokenResponse response = service.apply(new CapabilityTokenApplyRequest(
                "teaching:submit",
                "/api/teaching/tasks",
                "hash-001",
                "task-001",
                0.2), new RequestSubject("school-a", "student", "student-001", "device-1"));

        assertThat(service.consume(
                response.token(),
                "teaching:submit",
                "/api/teaching/tasks",
                "hash-changed",
                new RequestSubject("school-a", "student", "student-001", "device-1")).allowed()).isFalse();
        assertThat(service.consume(
                response.token(),
                "teaching:submit",
                "/api/teaching/tasks",
                "hash-001",
                new RequestSubject("school-a", "student", "student-002", "device-1")).allowed()).isFalse();
    }

    @Test
    void rejectsCapabilityApplicationForUnsupportedActionOrAnonymousSubject() {
        CapabilityTokenService service = new CapabilityTokenService(new InMemoryCapabilityTokenStore(), clock);

        assertThatThrownBy(() -> service.apply(new CapabilityTokenApplyRequest(
                "teacher:archive-resource",
                "/api/teacher/resources/doc-1",
                "hash-001",
                "task-001",
                0.2), new RequestSubject("school-a", "teacher", "teacher-001", "device-1")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported capability action");

        assertThatThrownBy(() -> service.apply(new CapabilityTokenApplyRequest(
                "teaching:submit",
                "/api/teaching/tasks",
                "hash-001",
                "task-001",
                0.2), RequestSubject.anonymous("school-a", "device-1")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Capability subject not allowed");
    }
}
