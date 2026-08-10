package com.doob.mathagent.student.service;

import com.doob.mathagent.agent.service.AiChatStreamDelta;
import com.doob.mathagent.student.vo.StudentExplanationResponse;
import com.doob.mathagent.student.vo.StudentExplanationStreamProgress;
import java.util.List;

/**
 * Streams real student-explanation progress snapshots to optional callers such as SSE controllers.
 *
 * <p>The default implementation is a no-op so existing synchronous callers keep the same behavior.
 */
public interface StudentExplanationProgressListener {

    StudentExplanationProgressListener NOOP = new StudentExplanationProgressListener() {
    };

    /**
     * Called when one real backend step updates the visible explanation progress.
     *
     * @param progress current snapshot shown to the frontend
     * @param message short safe message for the current transition
     */
    default void onProgress(StudentExplanationStreamProgress progress, String message) {
    }

    /** Forwards actual provider text and any complete cards parsed from that same provider stream. */
    default void onAiDelta(AiChatStreamDelta delta, List<StudentExplanationResponse.ExplanationCard> cards) {
    }

    /**
     * Called once the final response is fully assembled and persisted.
     *
     * @param response final explanation response
     */
    default void onCompleted(StudentExplanationResponse response) {
    }

    /**
     * Reports whether the downstream caller has gone away. Long-running retrieval/model stages use this hook to
     * stop before starting another expensive operation after an SSE disconnect.
     */
    default boolean isCancelled() {
        return false;
    }

    /** Fails fast at an orchestration boundary when the client no longer owns a live stream. */
    default void throwIfCancelled() {
        if (isCancelled()) {
            throw new java.util.concurrent.CancellationException("Student explanation stream was cancelled");
        }
    }
}
