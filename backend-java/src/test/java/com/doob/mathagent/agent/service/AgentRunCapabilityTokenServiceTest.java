package com.doob.mathagent.agent.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.doob.mathagent.infrastructure.security.RequestSubject;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Proves that an Agent tool token cannot be replayed by another user or used for a non-granted tool. */
class AgentRunCapabilityTokenServiceTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-07-24T00:00:00Z"), ZoneOffset.UTC);
    private static final RequestSubject STUDENT = new RequestSubject("tenant-a", "student", "student-a", null);

    @Test
    void verifies_only_the_bound_run_subject_and_tool() {
        AgentRunCapabilityTokenService service = new AgentRunCapabilityTokenService("test-signing-secret", CLOCK);
        String token = service.issue("run-1", STUDENT, List.of("search_visible_resources"));

        assertThat(service.verify(token, "run-1", STUDENT, "search_visible_resources").allowed()).isTrue();
        assertThat(service.verify(token, "run-1", STUDENT, "read_resource_blocks").allowed()).isFalse();
        assertThat(service.verify(token, "run-1",
                new RequestSubject("tenant-a", "student", "student-b", null), "search_visible_resources").allowed())
                .isFalse();
    }
}
