package com.doob.mathagent.securityrisk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.doob.mathagent.infrastructure.security.RequestSubject;
import com.doob.mathagent.securityrisk.dto.CapabilityTokenApplyRequest;
import com.doob.mathagent.securityrisk.service.CapabilityAuditEvent;
import com.doob.mathagent.securityrisk.service.CapabilityAuditSink;
import com.doob.mathagent.securityrisk.service.CapabilityTokenService;
import com.doob.mathagent.securityrisk.service.InMemoryCapabilityTokenStore;
import com.doob.mathagent.securityrisk.vo.CapabilityTokenResponse;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
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

    @Test
    void issuesTeacherResourceCapabilityTokensForRegisterAndArchive() {
        CapabilityTokenService service = new CapabilityTokenService(new InMemoryCapabilityTokenStore(), clock);
        RequestSubject teacher = new RequestSubject("school-a", "teacher", "teacher-001", "device-1");

        CapabilityTokenResponse register = service.apply(new CapabilityTokenApplyRequest(
                "teacher-resource:register",
                "/api/teacher/resources",
                "hash-register",
                "resource-register-001",
                1.0), teacher);
        CapabilityTokenResponse archive = service.apply(new CapabilityTokenApplyRequest(
                "teacher-resource:archive",
                "/api/teacher/resources/doc-1",
                "hash-archive",
                "resource-archive-doc-1",
                1.0), teacher);

        assertThat(service.consume(
                register.token(),
                "teacher-resource:register",
                "/api/teacher/resources",
                "hash-register",
                teacher).allowed()).isTrue();
        assertThat(service.consume(
                archive.token(),
                "teacher-resource:archive",
                "/api/teacher/resources/doc-1",
                "hash-archive",
                teacher).allowed()).isTrue();
    }

    @Test
    void recordsAuditEventsForIssueConsumeAndDeniedReplay() {
        CapturingCapabilityAuditSink auditSink = new CapturingCapabilityAuditSink();
        CapabilityTokenService service =
                new CapabilityTokenService(new InMemoryCapabilityTokenStore(), clock, auditSink);

        CapabilityTokenResponse response = service.apply(new CapabilityTokenApplyRequest(
                "teaching:submit",
                "/api/teaching/tasks",
                "hash-001",
                "task-001",
                0.2), new RequestSubject("school-a", "student", "student-001", "device-1"));
        service.consume(
                response.token(),
                "teaching:submit",
                "/api/teaching/tasks",
                "hash-001",
                new RequestSubject("school-a", "student", "student-001", "device-1"));
        service.consume(
                response.token(),
                "teaching:submit",
                "/api/teaching/tasks",
                "hash-001",
                new RequestSubject("school-a", "student", "student-001", "device-1"));

        assertThat(auditSink.events()).extracting(CapabilityAuditEvent::decision)
                .containsExactly("issued", "consumed", "denied");
        assertThat(auditSink.events()).allSatisfy(event -> {
            assertThat(event.tenantId()).isEqualTo("school-a");
            assertThat(event.subjectType()).isEqualTo("student");
            assertThat(event.subjectId()).isEqualTo("student-001");
            assertThat(event.action()).isEqualTo("teaching:submit");
            assertThat(event.path()).isEqualTo("/api/teaching/tasks");
            assertThat(event.requestHash()).isEqualTo("hash-001");
        });
        assertThat(auditSink.events().get(2).reason()).contains("already used");
    }

    @Test
    void recordsAuditEventWhenCapabilityApplicationIsRejected() {
        CapturingCapabilityAuditSink auditSink = new CapturingCapabilityAuditSink();
        CapabilityTokenService service =
                new CapabilityTokenService(new InMemoryCapabilityTokenStore(), clock, auditSink);

        assertThatThrownBy(() -> service.apply(new CapabilityTokenApplyRequest(
                "teacher:archive-resource",
                "/api/teacher/resources/doc-1",
                "hash-001",
                "task-001",
                0.2), new RequestSubject("school-a", "teacher", "teacher-001", "device-1")))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(auditSink.events()).hasSize(1);
        CapabilityAuditEvent event = auditSink.events().getFirst();
        assertThat(event.decision()).isEqualTo("rejected");
        assertThat(event.reason()).contains("Unsupported capability action");
        assertThat(event.subjectType()).isEqualTo("teacher");
        assertThat(event.subjectId()).isEqualTo("teacher-001");
    }

    private static final class CapturingCapabilityAuditSink implements CapabilityAuditSink {

        private final List<CapabilityAuditEvent> events = new ArrayList<>();

        @Override
        public void record(CapabilityAuditEvent event) {
            events.add(event);
        }

        List<CapabilityAuditEvent> events() {
            return events;
        }
    }
}
