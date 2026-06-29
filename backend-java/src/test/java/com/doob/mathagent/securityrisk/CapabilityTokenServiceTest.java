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
    void issuesTeacherResourceCapabilityTokensForRegisterArchiveAndSync() {
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
        CapabilityTokenResponse sync = service.apply(new CapabilityTokenApplyRequest(
                "teacher-resource:sync",
                "/api/teacher/resources/doc-1/sync-jobs",
                "hash-sync",
                "resource-sync-doc-1",
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
        assertThat(service.consume(
                sync.token(),
                "teacher-resource:sync",
                "/api/teacher/resources/doc-1/sync-jobs",
                "hash-sync",
                teacher).allowed()).isTrue();
    }

    @Test
    void issuesTeacherResourceSyncExecuteCapabilityTokenForExactJobPath() {
        CapabilityTokenService service = new CapabilityTokenService(new InMemoryCapabilityTokenStore(), clock);
        RequestSubject teacher = new RequestSubject("school-a", "teacher", "teacher-001", "device-1");

        CapabilityTokenResponse execute = service.apply(new CapabilityTokenApplyRequest(
                "teacher-resource:sync-execute",
                "/api/teacher/resources/doc-1/sync-jobs/job-1/execute",
                "hash-execute",
                "resource-sync-execute-doc-1-job-1",
                2.0), teacher);

        assertThat(service.consume(
                execute.token(),
                "teacher-resource:sync-execute",
                "/api/teacher/resources/doc-1/sync-jobs/job-1/execute",
                "hash-execute",
                teacher).allowed()).isTrue();
    }

    @Test
    void rejectsMalformedTeacherResourceSyncExecuteCapabilityPaths() {
        CapabilityTokenService service = new CapabilityTokenService(new InMemoryCapabilityTokenStore(), clock);
        RequestSubject teacher = new RequestSubject("school-a", "teacher", "teacher-001", "device-1");

        assertThatThrownBy(() -> service.apply(new CapabilityTokenApplyRequest(
                "teacher-resource:sync-execute",
                "/api/teacher/resources/doc-1/sync-jobs/execute",
                "hash-execute",
                "resource-sync-execute-doc-1",
                2.0), teacher))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported capability action");
    }

    @Test
    void issuesStudentMemoryCapabilityTokenOnlyForStudents() {
        CapabilityTokenService service = new CapabilityTokenService(new InMemoryCapabilityTokenStore(), clock);
        RequestSubject student = new RequestSubject("school-a", "student", "student-001", "device-1");

        CapabilityTokenResponse remember = service.apply(new CapabilityTokenApplyRequest(
                "student-memory:remember",
                "/api/students/memory/remember",
                "hash-memory",
                "student-memory-remember:student-001",
                1.0), student);

        assertThat(service.consume(
                remember.token(),
                "student-memory:remember",
                "/api/students/memory/remember",
                "hash-memory",
                student).allowed()).isTrue();
        assertThatThrownBy(() -> service.apply(new CapabilityTokenApplyRequest(
                "student-memory:remember",
                "/api/students/memory/remember",
                "hash-memory",
                "student-memory-remember:teacher-001",
                1.0), new RequestSubject("school-a", "teacher", "teacher-001", "device-1")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Capability subject not allowed");
    }

    @Test
    void issuesTeachingHandoutLatexExportCapabilityTokens() {
        CapabilityTokenService service = new CapabilityTokenService(new InMemoryCapabilityTokenStore(), clock);
        RequestSubject student = new RequestSubject("school-a", "student", "student-001", "device-1");

        CapabilityTokenResponse export = service.apply(new CapabilityTokenApplyRequest(
                "teaching-handout:export-latex",
                "/api/teaching/tasks/task-1/handout/latex",
                "hash-empty-body",
                "teaching-handout-export-latex:task-1",
                1.0), student);

        assertThat(service.consume(
                export.token(),
                "teaching-handout:export-latex",
                "/api/teaching/tasks/task-1/handout/latex",
                "hash-empty-body",
                student).allowed()).isTrue();
    }

    @Test
    void issuesTeachingHandoutPdfExportCapabilityTokens() {
        CapabilityTokenService service = new CapabilityTokenService(new InMemoryCapabilityTokenStore(), clock);
        RequestSubject student = new RequestSubject("school-a", "student", "student-001", "device-1");

        CapabilityTokenResponse export = service.apply(new CapabilityTokenApplyRequest(
                "teaching-handout:export-pdf",
                "/api/teaching/tasks/task-1/handout/pdf",
                "hash-empty-body",
                "teaching-handout-export-pdf:task-1",
                2.0), student);

        assertThat(service.consume(
                export.token(),
                "teaching-handout:export-pdf",
                "/api/teaching/tasks/task-1/handout/pdf",
                "hash-empty-body",
                student).allowed()).isTrue();
    }

    @Test
    void issuesTeachingHandoutLatexPreviewCapabilityTokens() {
        CapabilityTokenService service = new CapabilityTokenService(new InMemoryCapabilityTokenStore(), clock);
        RequestSubject student = new RequestSubject("school-a", "student", "student-001", "device-1");

        CapabilityTokenResponse preview = service.apply(new CapabilityTokenApplyRequest(
                "teaching-handout:preview-latex",
                "/api/teaching/tasks/task-1/handout/latex/preview",
                "hash-empty-body",
                "teaching-handout-preview-latex:task-1",
                1.0), student);

        assertThat(service.consume(
                preview.token(),
                "teaching-handout:preview-latex",
                "/api/teaching/tasks/task-1/handout/latex/preview",
                "hash-empty-body",
                student).allowed()).isTrue();
    }

    @Test
    void issuesVersionBoundTeachingHandoutCapabilityTokens() {
        CapabilityTokenService service = new CapabilityTokenService(new InMemoryCapabilityTokenStore(), clock);
        RequestSubject teacher = new RequestSubject("school-a", "teacher", "teacher-001", "device-1");

        CapabilityTokenResponse teacherPreview = service.apply(new CapabilityTokenApplyRequest(
                "teaching-handout:preview-latex",
                "/api/teaching/tasks/task-1/handout/teacher/latex/preview",
                "hash-empty-body",
                "teaching-handout-preview-latex:task-1:teacher",
                1.0), teacher);
        CapabilityTokenResponse studentPdf = service.apply(new CapabilityTokenApplyRequest(
                "teaching-handout:export-pdf",
                "/api/teaching/tasks/task-1/handout/student/pdf",
                "hash-empty-body",
                "teaching-handout-export-pdf:task-1:student",
                2.0), teacher);

        assertThat(service.consume(
                teacherPreview.token(),
                "teaching-handout:preview-latex",
                "/api/teaching/tasks/task-1/handout/student/latex/preview",
                "hash-empty-body",
                teacher).allowed()).isFalse();
        assertThat(service.consume(
                teacherPreview.token(),
                "teaching-handout:preview-latex",
                "/api/teaching/tasks/task-1/handout/teacher/latex/preview",
                "hash-empty-body",
                teacher).allowed()).isTrue();
        assertThat(service.consume(
                studentPdf.token(),
                "teaching-handout:export-pdf",
                "/api/teaching/tasks/task-1/handout/student/pdf",
                "hash-empty-body",
                teacher).allowed()).isTrue();
    }

    @Test
    void issuesTeachingHandoutBatchZipCapabilityTokens() {
        CapabilityTokenService service = new CapabilityTokenService(new InMemoryCapabilityTokenStore(), clock);
        RequestSubject teacher = new RequestSubject("school-a", "teacher", "teacher-001", "device-1");

        CapabilityTokenResponse export = service.apply(new CapabilityTokenApplyRequest(
                "teaching-handout:batch-export-zip",
                "/api/teaching/handouts/batch/zip",
                "hash-batch-body",
                "teaching-handout-batch-export-zip:folder-a",
                5.0), teacher);
        CapabilityTokenResponse download = service.apply(new CapabilityTokenApplyRequest(
                "teaching-handout:batch-download-zip",
                "/api/teaching/handouts/batch/zip/batch-1/download",
                "hash-empty-body",
                "teaching-handout-batch-download-zip:batch-1",
                5.0), teacher);

        assertThat(service.consume(
                export.token(),
                "teaching-handout:batch-export-zip",
                "/api/teaching/handouts/batch/zip",
                "hash-batch-body",
                teacher).allowed()).isTrue();
        assertThat(service.consume(
                download.token(),
                "teaching-handout:batch-download-zip",
                "/api/teaching/handouts/batch/zip/batch-1/download",
                "hash-empty-body",
                teacher).allowed()).isTrue();
    }

    @Test
    void issuesTeachingHumanFeedbackCapabilityTokens() {
        CapabilityTokenService service = new CapabilityTokenService(new InMemoryCapabilityTokenStore(), clock);
        RequestSubject student = new RequestSubject("school-a", "student", "student-001", "device-1");

        CapabilityTokenResponse feedback = service.apply(new CapabilityTokenApplyRequest(
                "teaching-feedback:submit",
                "/api/teaching/tasks/task-1/feedback",
                "hash-feedback-body",
                "teaching-feedback-submit:task-1",
                1.0), student);

        assertThat(service.consume(
                feedback.token(),
                "teaching-feedback:submit",
                "/api/teaching/tasks/task-1/feedback",
                "hash-feedback-body",
                student).allowed()).isTrue();
    }

    @Test
    void rejectsMalformedTeachingHumanFeedbackCapabilityPaths() {
        CapabilityTokenService service = new CapabilityTokenService(new InMemoryCapabilityTokenStore(), clock);
        RequestSubject student = new RequestSubject("school-a", "student", "student-001", "device-1");

        assertThatThrownBy(() -> service.apply(new CapabilityTokenApplyRequest(
                "teaching-feedback:submit",
                "/api/teaching/tasks/task-1/extra/feedback",
                "hash-feedback-body",
                "teaching-feedback-submit:task-1",
                1.0), student))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported capability action");
    }

    @Test
    void rejectsMalformedTeachingBatchDownloadCapabilityPaths() {
        CapabilityTokenService service = new CapabilityTokenService(new InMemoryCapabilityTokenStore(), clock);
        RequestSubject teacher = new RequestSubject("school-a", "teacher", "teacher-001", "device-1");

        assertThatThrownBy(() -> service.apply(new CapabilityTokenApplyRequest(
                "teaching-handout:batch-download-zip",
                "/api/teaching/handouts/batch/zip/batch-1/extra/download",
                "hash-empty-body",
                "teaching-handout-batch-download-zip:batch-1",
                5.0), teacher))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported capability action");
    }

    @Test
    void rejectsMalformedTeachingHandoutCapabilityPaths() {
        CapabilityTokenService service = new CapabilityTokenService(new InMemoryCapabilityTokenStore(), clock);
        RequestSubject teacher = new RequestSubject("school-a", "teacher", "teacher-001", "device-1");

        assertThatThrownBy(() -> service.apply(new CapabilityTokenApplyRequest(
                "teaching-handout:export-latex",
                "/api/teaching/tasks/task-1/extra/handout/latex",
                "hash-empty-body",
                "teaching-handout-export-latex:task-1",
                1.0), teacher))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported capability action");
    }

    @Test
    void issuesAgentRunCapabilityTokensForLoggedInSubjects() {
        CapabilityTokenService service = new CapabilityTokenService(new InMemoryCapabilityTokenStore(), clock);
        RequestSubject teacher = new RequestSubject("school-a", "teacher", "teacher-001", "device-1");

        CapabilityTokenResponse execute = service.apply(new CapabilityTokenApplyRequest(
                "agent-run:CoursewareAgent",
                "/api/agents/execute",
                "hash-execute-body",
                "agent-run:plan-1",
                3.0), teacher);

        assertThat(execute.action()).isEqualTo("agent-run:CoursewareAgent");
        assertThat(execute.path()).isEqualTo("/api/agents/execute");
        assertThat(service.consume(
                execute.token(),
                "agent-run:CoursewareAgent",
                "/api/agents/execute",
                "hash-execute-body",
                teacher).allowed()).isTrue();
        assertThat(service.consume(
                execute.token(),
                "agent-run:CoursewareAgent",
                "/api/agents/execute",
                "hash-execute-body",
                teacher).allowed()).isFalse();
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
