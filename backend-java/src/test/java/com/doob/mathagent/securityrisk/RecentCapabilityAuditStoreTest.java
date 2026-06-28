package com.doob.mathagent.securityrisk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.doob.mathagent.securityrisk.dto.CapabilityAuditQuery;
import com.doob.mathagent.securityrisk.service.CapabilityAuditEvent;
import com.doob.mathagent.securityrisk.service.RecentCapabilityAuditStore;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class RecentCapabilityAuditStoreTest {

    @Test
    void keepsOnlyMostRecentCapabilityAuditEvents() {
        RecentCapabilityAuditStore store = new RecentCapabilityAuditStore(2);

        store.record(event("event-1"));
        store.record(event("event-2"));
        store.record(event("event-3"));

        assertThat(store.events()).extracting(CapabilityAuditEvent::eventId)
                .containsExactly("event-2", "event-3");
    }

    @Test
    void rejectsInvalidCapacity() {
        assertThatThrownBy(() -> new RecentCapabilityAuditStore(0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("capacity");
    }

    @Test
    void queriesCapabilityAuditEventsByBackendTenantAndFiltersWithoutRawToken() {
        RecentCapabilityAuditStore store = new RecentCapabilityAuditStore(10);
        store.record(event("event-1", "school-a", "student", "student-1", "teaching:submit", "issued", "raw-token-a"));
        store.record(event("event-2", "school-b", "student", "student-1", "teaching:submit", "issued", "raw-token-b"));
        store.record(event("event-3", "school-a", "teacher", "teacher-1", "teaching:submit", "denied", "raw-token-c"));

        var results = store.search(new CapabilityAuditQuery(
                "school-a",
                null,
                null,
                "teaching:submit",
                "issued",
                5));

        assertThat(results).hasSize(1);
        assertThat(results.getFirst().eventId()).isEqualTo("event-1");
        assertThat(results.getFirst().tenantId()).isEqualTo("school-a");
        assertThat(results.getFirst().decision()).isEqualTo("issued");
        assertThat(results.getFirst().tokenHash()).isNotBlank();
        assertThat(results.getFirst().tokenHash()).isNotEqualTo("raw-token-a");
    }

    /**
     * Builds a minimal audit event for store tests.
     */
    private static CapabilityAuditEvent event(String eventId) {
        return event(eventId, "school-a", "student", "student-1", "teaching:submit", "issued", "token-1");
    }

    /**
     * Builds an audit event with explicit filter values for query tests.
     */
    private static CapabilityAuditEvent event(
            String eventId,
            String tenantId,
            String subjectType,
            String subjectId,
            String action,
            String decision,
            String token) {
        return new CapabilityAuditEvent(
                eventId,
                Instant.parse("2026-06-28T08:00:00Z"),
                tenantId,
                subjectType,
                subjectId,
                action,
                "/api/teaching/tasks",
                "hash-1",
                "client-1",
                token,
                decision,
                "Capability token issued");
    }
}
