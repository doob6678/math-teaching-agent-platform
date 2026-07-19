package com.doob.mathagent.agent;

import static org.assertj.core.api.Assertions.assertThat;

import com.doob.mathagent.agent.service.AgentConcurrencyLease;
import com.doob.mathagent.agent.service.InMemoryAgentConcurrencyGuard;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class InMemoryAgentConcurrencyGuardTest {

    @Test
    void rejectsOverlappingConcurrencyKeysUntilLeaseIsReleased() {
        InMemoryAgentConcurrencyGuard guard = new InMemoryAgentConcurrencyGuard();

        Optional<AgentConcurrencyLease> first = guard.tryAcquire(
                List.of("concurrent:user:teacher-1:CoursewareAgent", "concurrent:model:gpt-5.4"),
                "trace-1",
                Duration.ofSeconds(30));
        Optional<AgentConcurrencyLease> overlap = guard.tryAcquire(
                List.of("concurrent:user:teacher-1:CoursewareAgent"),
                "trace-2",
                Duration.ofSeconds(30));

        assertThat(first).isPresent();
        assertThat(overlap).isEmpty();

        first.get().close();

        assertThat(guard.tryAcquire(
                List.of("concurrent:user:teacher-1:CoursewareAgent"),
                "trace-3",
                Duration.ofSeconds(30))).isPresent();
    }

    @Test
    void rollsBackAlreadyAcquiredKeysWhenAnyRequestedKeyConflicts() {
        InMemoryAgentConcurrencyGuard guard = new InMemoryAgentConcurrencyGuard();
        AgentConcurrencyLease active = guard.tryAcquire(List.of("model:gpt-5.4"), "trace-1", Duration.ofSeconds(30))
                .orElseThrow();

        Optional<AgentConcurrencyLease> denied = guard.tryAcquire(
                List.of("user:teacher-1", "model:gpt-5.4"),
                "trace-2",
                Duration.ofSeconds(30));

        assertThat(denied).isEmpty();
        assertThat(guard.tryAcquire(List.of("user:teacher-1"), "trace-3", Duration.ofSeconds(30))).isPresent();
        active.close();
    }
}
