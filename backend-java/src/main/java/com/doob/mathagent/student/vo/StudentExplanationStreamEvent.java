package com.doob.mathagent.student.vo;

import java.util.List;

/**
 * SSE event contract for the student explanation page.
 *
 * @param eventType accepted, progress, completed, or error
 * @param message short safe message for the UI
 * @param progress incremental snapshot for progress rendering
 * @param response final response once the explanation completes
 * @param errorCode stable backend error code when available
 * @param errorTraceId backend log correlation id for troubleshooting
 */
public record StudentExplanationStreamEvent(
        String eventType,
        String message,
        StudentExplanationStreamProgress progress,
        StudentExplanationResponse response,
        String errorCode,
        String errorTraceId,
        String aiContentDelta,
        String aiReasoningDelta,
        List<StudentExplanationResponse.ExplanationCard> cards) {
}
