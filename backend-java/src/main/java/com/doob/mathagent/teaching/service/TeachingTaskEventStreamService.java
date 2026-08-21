package com.doob.mathagent.teaching.service;

import com.doob.mathagent.teaching.TeachingTaskStatus;
import com.doob.mathagent.teaching.vo.TeachingTaskProgressResponse;
import com.doob.mathagent.teaching.vo.TeachingTaskResponse;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Streams changes from the durable teaching-task store to an SSE client.
 *
 * <p>Virtual threads keep each connection isolated without occupying a bounded generation worker. This service reads
 * persisted snapshots rather than manufacturing progress, which also makes reconnects and multi-instance recovery
 * deterministic.</p>
 */
@Service
public class TeachingTaskEventStreamService {

    /** Snapshot cadence balances live feedback with MySQL read pressure. */
    private static final Duration SNAPSHOT_POLL_INTERVAL = Duration.ofMillis(400);
    private static final String STREAM_TIMEOUT_ENV = "MATH_AGENT_TEACHING_SSE_TIMEOUT_MS";
    private static final long DEFAULT_STREAM_TIMEOUT_MILLIS = 900_000L;
    /**
     * Keeps the live stream open through a slow but valid long-form model call. The value is operator-configurable;
     * the default is longer than the seven-minute provider budget used by the local real-worker launch.
     */
    private static final Duration STREAM_TIMEOUT = Duration.ofMillis(readStreamTimeoutMillis());

    private static final Set<String> PYTHON_EVENT_ALLOWLIST = Set.of(
            "event", "status", "node", "phase", "revisionRound", "turn", "provider", "model", "deterministicRepair");

    private final ExecutorService streamExecutor;

    /** Projects worker events to scalar operational fields and drops checkpoint/document content. */
    static Map<String, Object> projectPythonEvent(Map<String, Object> event) {
        if (event == null || event.get("event") == null) {
            return Map.of();
        }
        Map<String, Object> projected = new LinkedHashMap<>();
        for (String field : PYTHON_EVENT_ALLOWLIST) {
            Object value = event.get(field);
            if (value instanceof String || value instanceof Number || value instanceof Boolean) {
                projected.put(field, value);
            }
        }
        return Map.copyOf(projected);
    }

    /** Removes duplicate or out-of-order worker event ids before a projection is published. */
    static List<PythonEvent> deduplicatePythonEvents(List<PythonEvent> events, long afterId) {
        if (events == null || events.isEmpty()) return List.of();
        long cursor = Math.max(0, afterId);
        java.util.ArrayList<PythonEvent> result = new java.util.ArrayList<>();
        for (PythonEvent event : events) {
            if (event == null || event.eventId() <= cursor) continue;
            Map<String, Object> safe = projectPythonEvent(event.data());
            if (safe.isEmpty()) continue;
            result.add(new PythonEvent(event.eventId(), safe));
            cursor = event.eventId();
        }
        return List.copyOf(result);
    }

    record PythonEvent(long eventId, Map<String, Object> data) {}

    /** Creates the production event executor on Java 21 virtual threads. */
    public TeachingTaskEventStreamService() {
        this(Executors.newVirtualThreadPerTaskExecutor());
    }

    /** Visible for deterministic tests with a caller-owned executor. */
    TeachingTaskEventStreamService(ExecutorService streamExecutor) {
        this.streamExecutor = streamExecutor;
    }

    /** Reads a bounded SSE timeout without requiring a code change for slower production providers. */
    private static long readStreamTimeoutMillis() {
        String configured = System.getenv(STREAM_TIMEOUT_ENV);
        if (configured == null || configured.isBlank()) {
            return DEFAULT_STREAM_TIMEOUT_MILLIS;
        }
        try {
            return Math.max(60_000L, Long.parseLong(configured.strip()));
        } catch (NumberFormatException ignored) {
            return DEFAULT_STREAM_TIMEOUT_MILLIS;
        }
    }

    /**
     * Opens a stream, sends the latest durable state immediately, then sends only changes until a terminal state.
     *
     * @param snapshotSupplier owner-scoped task reload function
     * @return SSE emitter already scheduled for delivery
     */
    public SseEmitter stream(Supplier<Optional<TeachingTaskResponse>> snapshotSupplier) {
        SseEmitter emitter = new SseEmitter(STREAM_TIMEOUT.toMillis());
        AtomicBoolean open = new AtomicBoolean(true);
        emitter.onCompletion(() -> open.set(false));
        emitter.onTimeout(() -> open.set(false));
        emitter.onError(ignored -> open.set(false));
        streamExecutor.submit(() -> sendSnapshots(emitter, open, snapshotSupplier));
        return emitter;
    }

    private static void sendSnapshots(
            SseEmitter emitter,
            AtomicBoolean open,
            Supplier<Optional<TeachingTaskResponse>> snapshotSupplier) {
        String previousFingerprint = "";
        try {
            while (open.get()) {
                TeachingTaskResponse task = snapshotSupplier.get().orElse(null);
                if (task == null) {
                    emitter.complete();
                    return;
                }
                TeachingTaskProgressResponse progress = TeachingTaskProgressResponse.from(task);
                String fingerprint = fingerprint(progress);
                if (!fingerprint.equals(previousFingerprint)) {
                    String eventName = terminal(task.status()) ? terminalEventName(task.status()) : "progress";
                    emitter.send(SseEmitter.event().name(eventName).data(progress));
                    previousFingerprint = fingerprint;
                }
                if (terminal(task.status())) {
                    emitter.complete();
                    return;
                }
                Thread.sleep(SNAPSHOT_POLL_INTERVAL);
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            emitter.complete();
        } catch (Exception exception) {
            emitter.completeWithError(exception);
        }
    }

    /** Fingerprint includes every user-visible changing field and intentionally excludes hidden task payloads. */
    private static String fingerprint(TeachingTaskProgressResponse progress) {
        return progress.taskId()
                + "|" + progress.status()
                + "|" + progress.nodes()
                + "|" + progress.workflowEvents()
                + "|" + progress.evidence()
                + "|" + progress.stageTimings()
                + "|" + progress.versions()
                + "|" + progress.errorMessage();
    }

    private static boolean terminal(TeachingTaskStatus status) {
        return status == TeachingTaskStatus.COMPLETED
                || status == TeachingTaskStatus.FAILED
                || status == TeachingTaskStatus.WAITING_REVIEW
                || status == TeachingTaskStatus.DRAFT_ONLY;
    }

    private static String terminalEventName(TeachingTaskStatus status) {
        return switch (status) {
            case COMPLETED -> "completed";
            case WAITING_REVIEW -> "waiting_review";
            case DRAFT_ONLY -> "draft_only";
            case FAILED, CREATED, RUNNING, RETRYING -> "failed";
        };
    }

    /** Releases virtual-thread resources when the Spring context closes. */
    @PreDestroy
    void close() {
        streamExecutor.shutdownNow();
    }
}
