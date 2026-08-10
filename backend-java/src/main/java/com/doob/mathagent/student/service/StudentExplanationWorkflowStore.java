package com.doob.mathagent.student.service;

import com.doob.mathagent.infrastructure.security.RequestSubject;
import com.doob.mathagent.student.dto.StudentExplanationRequest;
import com.doob.mathagent.student.vo.StudentExplanationResponse;
import com.doob.mathagent.student.vo.StudentExplanationStreamEvent;
import java.util.List;

/** Durable Java-owned identity, terminal result, and public SSE cursor for one explanation request. */
public interface StudentExplanationWorkflowStore {

    WorkflowRun createOrLoad(RequestSubject subject, StudentExplanationRequest request);

    WorkflowEvent append(String runId, String eventName, StudentExplanationStreamEvent event);

    List<WorkflowEvent> eventsAfter(String runId, long afterEventId, int limit);

    void complete(String runId, StudentExplanationResponse response);

    void fail(String runId, String errorCode, String errorMessage);

    record WorkflowRun(
            String runId,
            String requestFingerprint,
            String status,
            StudentExplanationResponse response,
            String errorCode,
            String errorMessage,
            boolean created) {
    }

    record WorkflowEvent(long eventId, String eventName, StudentExplanationStreamEvent event) {
    }
}
