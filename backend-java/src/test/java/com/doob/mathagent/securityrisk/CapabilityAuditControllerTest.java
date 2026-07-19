package com.doob.mathagent.securityrisk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.doob.mathagent.infrastructure.security.RequestSubject;
import com.doob.mathagent.infrastructure.security.RequestSubjectResolver;
import com.doob.mathagent.securityrisk.dto.CapabilityAuditQuery;
import com.doob.mathagent.securityrisk.service.CapabilityAuditLookup;
import com.doob.mathagent.securityrisk.controller.CapabilityAuditController;
import com.doob.mathagent.securityrisk.vo.CapabilityAuditLogResponse;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.server.ResponseStatusException;

class CapabilityAuditControllerTest {

    @Test
    void teacherQueriesCapabilityAuditsWithBackendTenantOnly() {
        CapturingLookup lookup = new CapturingLookup();
        CapabilityAuditController controller = new CapabilityAuditController(
                lookup,
                resolver(new RequestSubject("school-a", "teacher", "teacher-1", "device-1")));

        List<CapabilityAuditLogResponse> response = controller.list(
                "student",
                "student-1",
                "teaching:submit",
                "issued",
                50,
                new MockHttpServletRequest());

        assertThat(lookup.query.tenantId()).isEqualTo("school-a");
        assertThat(lookup.query.subjectType()).isEqualTo("student");
        assertThat(lookup.query.subjectId()).isEqualTo("student-1");
        assertThat(lookup.query.action()).isEqualTo("teaching:submit");
        assertThat(lookup.query.decision()).isEqualTo("issued");
        assertThat(lookup.query.limit()).isEqualTo(50);
        assertThat(response).hasSize(1);
    }

    @Test
    void studentCannotQueryCapabilityAuditsEvenWhenFilterTargetsTeacher() {
        CapabilityAuditController controller = new CapabilityAuditController(
                new CapturingLookup(),
                resolver(new RequestSubject("school-a", "student", "student-1", "device-1")));

        assertThatThrownBy(() -> controller.list(
                        "teacher",
                        "teacher-1",
                        "teaching:submit",
                        "denied",
                        20,
                        new MockHttpServletRequest()))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("403");
    }

    private static RequestSubjectResolver resolver(RequestSubject subject) {
        return request -> subject;
    }

    private static final class CapturingLookup implements CapabilityAuditLookup {

        private CapabilityAuditQuery query;

        @Override
        public List<CapabilityAuditLogResponse> search(CapabilityAuditQuery query) {
            this.query = query;
            List<CapabilityAuditLogResponse> results = new ArrayList<>();
            results.add(new CapabilityAuditLogResponse(
                    "event-1",
                    Instant.parse("2026-06-28T08:00:00Z"),
                    query.tenantId(),
                    "student",
                    "student-1",
                    "teaching:submit",
                    "/api/teaching/tasks",
                    "hash-1",
                    "client-1",
                    "token-hash-1",
                    "issued",
                    "Capability token issued"));
            return results;
        }
    }
}
