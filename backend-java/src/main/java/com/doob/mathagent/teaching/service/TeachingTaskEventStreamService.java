package com.doob.mathagent.teaching.service;

import com.doob.mathagent.teaching.TeachingTaskStatus;
import com.doob.mathagent.teaching.vo.TeachingTaskProgressResponse;
import com.doob.mathagent.teaching.vo.TeachingTaskResponse;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.Optional;
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
    /** The browser reconnects through the normal task endpoint after this bounded stream window. */
    private static final Duration STREAM_TIMEOUT = Duration.ofMinutes(5);

    private final ExecutorService streamExecutor;

    /** Creates the production event executor on Java 21 virtual threads. */
    public TeachingTaskEventStreamService() {
        this(Executors.newVirtualThreadPerTaskExecutor());
    }

    /** Visible for deterministic tests with a caller-owned executor. */
    TeachingTaskEventStreamService(ExecutorService streamExecutor) {
        this.streamExecutor = streamExecutor;
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
        return status == TeachingTaskStatus.COMPLETED || status == TeachingTaskStatus.FAILED;
    }

    private static String terminalEventName(TeachingTaskStatus status) {
        return status == TeachingTaskStatus.COMPLETED ? "completed" : "failed";
    }

    /** Releases virtual-thread resources when the Spring context closes. */
    @PreDestroy
    void close() {
        streamExecutor.shutdownNow();
    }
}
