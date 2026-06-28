package com.doob.mathagent.securityrisk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

    /**
     * Builds a minimal audit event for store tests.
     */
    private static CapabilityAuditEvent event(String eventId) {
        return new CapabilityAuditEvent(
                eventId,
                Instant.parse("2026-06-28T08:00:00Z"),
                "school-a",
                "student",
                "student-1",
                "teaching:submit",
                "/api/teaching/tasks",
                "hash-1",
                "client-1",
                "token-1",
                "issued",
                "Capability token issued");
    }
}
