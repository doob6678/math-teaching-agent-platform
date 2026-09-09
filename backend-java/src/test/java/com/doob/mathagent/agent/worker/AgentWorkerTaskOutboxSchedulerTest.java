package com.doob.mathagent.agent.worker;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.TaskScheduler;

/**
 * Covers the two guarantees the batch claim and the wake fast path rely on: a process-unique publisher
 * identity, and at most one queued immediate round per pending wake. Dependencies are hand-rolled proxies
 * (project tests carry no Mockito dependency); the scheduling proxy records runnables instead of running them.
 */
class AgentWorkerTaskOutboxSchedulerTest {

    @Test
    void instancePublisherIdStaysReadableButUniquePerProcess() {
        String first = AgentWorkerTaskOutboxScheduler.instancePublisherId("agent-worker-outbox");
        String second = AgentWorkerTaskOutboxScheduler.instancePublisherId("agent-worker-outbox");

        assertThat(first).startsWith("agent-worker-outbox-");
        assertThat(first).isNotEqualTo(second);
    }

    @Test
    void wakesCoalesceToOneQueuedRoundAndReArmAfterItRuns() {
        List<Runnable> queued = new ArrayList<>();
        AgentWorkerTaskOutboxScheduler scheduler = scheduler(queued::add);

        scheduler.onOutboxWake(new AgentWorkerOutboxWakeEvent("task-1"));
        scheduler.onOutboxWake(new AgentWorkerOutboxWakeEvent("task-2"));
        assertThat(queued).hasSize(1);

        // Running the round clears the pending-wake flag, so a later submission queues a fresh round.
        queued.get(0).run();
        scheduler.onOutboxWake(new AgentWorkerOutboxWakeEvent("task-3"));
        assertThat(queued).hasSize(2);
    }

    @Test
    void scheduledEntryToleratesAnEmptyRound() {
        AgentWorkerTaskOutboxScheduler scheduler = scheduler(runnable -> { });
        // Smoke: the @Scheduled entry point against an empty store must complete without throwing.
        scheduler.publishPendingEvents();
    }

    private static AgentWorkerTaskOutboxScheduler scheduler(Consumer<Runnable> scheduleRecorder) {
        TaskScheduler taskScheduler = (TaskScheduler) Proxy.newProxyInstance(
                TaskScheduler.class.getClassLoader(),
                new Class<?>[] {TaskScheduler.class},
                (proxy, method, args) -> {
                    if ("schedule".equals(method.getName()) && args != null && args.length == 2
                            && args[1] instanceof Instant) {
                        scheduleRecorder.accept((Runnable) args[0]);
                    }
                    return "toString".equals(method.getName()) ? "fakeTaskScheduler" : null;
                });
        Environment environment = (Environment) Proxy.newProxyInstance(
                Environment.class.getClassLoader(),
                new Class<?>[] {Environment.class},
                (proxy, method, args) -> switch (method.getName()) {
                    // The scheduler only reads config through the two defaulted getProperty overloads.
                    case "getProperty" -> args.length == 3 ? args[2] : args.length == 2 ? args[1] : null;
                    case "containsProperty" -> false;
                    case "toString" -> "fakeEnvironment";
                    default -> null;
                });
        return new AgentWorkerTaskOutboxScheduler(
                new EmptyDispatchService(), emptyOutboxStore(), null, environment, new SimpleMeterRegistry(), taskScheduler);
    }

    /** Concrete classes need subclass overrides because their constructors take collaborators; only no-ops matter here. */
    private static final class EmptyDispatchService extends AgentWorkerTaskDispatchService {
        EmptyDispatchService() {
            super(null, null, null);
        }

        @Override public int recoverExpiredPublishing(Instant now) { return 0; }
        @Override public int reconcileOrphanQueued(Instant olderThan, int limit) { return 0; }
    }

    private static AgentWorkerTaskOutboxStore emptyOutboxStore() {
        return (AgentWorkerTaskOutboxStore) Proxy.newProxyInstance(
                AgentWorkerTaskOutboxStore.class.getClassLoader(),
                new Class<?>[] {AgentWorkerTaskOutboxStore.class},
                (proxy, method, args) -> {
                    if (method.getReturnType() == List.class) {
                        return List.of();
                    }
                    if (method.getReturnType() == int.class) {
                        return 0;
                    }
                    if (method.getReturnType() == long.class) {
                        return 0L;
                    }
                    if (method.getReturnType() == boolean.class) {
                        return false;
                    }
                    return "toString".equals(method.getName()) ? "fakeOutboxStore" : null;
                });
    }
}
