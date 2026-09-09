package com.doob.mathagent.student.service;

import java.util.List;
import java.util.Set;

/**
 * Redis buffer port for the student-explanation SSE event write-behind path.
 *
 * <p>The store only needs five atomic-ish primitives (buffered append with sequence allocation, newest reads,
 * oldest peek, flush confirmation, active-run enumeration). Keeping them behind an interface lets the store's
 * degradation logic be unit-tested with an in-memory fake, while the real Lua-backed implementation talks to the
 * same Redis the conversation-context cache already uses.</p>
 */
public interface StudentExplanationWorkflowEventRedisGateway {

    /**
     * Atomically allocates the next event id from the shared sequence and appends the event to the run's list.
     *
     * @param seed high-water mark the sequence must never fall below (covers ids issued by direct MySQL fallbacks)
     * @return the allocated event id, or {@link #BUFFER_FULL} when the run reached its configured buffer ceiling
     * @throws RuntimeException when Redis is unreachable; callers must fall back to a direct MySQL write
     */
    long append(String runId, String eventName, String eventJson, long seed);

    /** Returned by {@link #append} when the per-run buffer is at capacity and the caller must write through. */
    long BUFFER_FULL = -1L;

    /** Events buffered for the run with ids strictly greater than {@code afterEventId}, ascending, capped at limit. */
    List<BufferedEvent> readAfter(String runId, long afterEventId, int limit);

    /** The oldest up-to-{@code limit} buffered events of the run, ascending; used by the flusher batch drain. */
    List<BufferedEvent> peekOldest(String runId, int limit);

    /**
     * Confirms that the {@code flushedCount} oldest peeked events are now durable in MySQL: drops them from the list
     * and removes the run from the active index when the list became empty. Returns the remaining list length.
     */
    long confirmFlushed(String runId, int flushedCount);

    /** Run ids that currently hold (or recently held) a non-empty buffer; stale entries self-prune on flush. */
    Set<String> activeRuns();

    /** One buffered public stream event already serialized to its durable JSON form. */
    record BufferedEvent(long eventId, String eventName, String eventJson) {
    }
}
