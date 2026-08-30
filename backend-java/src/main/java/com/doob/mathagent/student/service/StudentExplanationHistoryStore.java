package com.doob.mathagent.student.service;

import com.doob.mathagent.infrastructure.security.RequestSubject;
import com.doob.mathagent.student.dto.StudentExplanationRequest;
import com.doob.mathagent.student.vo.StudentExplanationResponse;
import java.util.List;

/**
 * Durable store for explanation conversations and generated cards.
 */
public interface StudentExplanationHistoryStore {

    /**
     * Returns whether this store writes durable shared storage.
     */
    default boolean durable() {
        return true;
    }

    /**
     * Saves one explanation response with all model, evidence, and card metadata needed for recovery.
     */
    void save(
            StudentExplanationRequest request,
            RequestSubject subject,
            StudentExplanationImageRecord imageRecord,
            StudentExplanationResponse response);

    /**
     * Finds recent messages visible to the current backend-resolved subject.
     */
    List<StudentExplanationHistorySummary> findRecent(
            String tenantId,
            String subjectType,
            String subjectId,
            String conversationId,
            int limit);

    /**
     * Returns the confirmed compact summary for one subject-authorized conversation.
     */
    default StudentExplanationContextSummary findContextSummary(
            String tenantId,
            String subjectType,
            String subjectId,
            String conversationId) {
        return null;
    }

    /**
     * Persists a new summary only when its version still follows the durable conversation state.
     */
    default boolean updateContextSummary(
            String tenantId,
            String subjectType,
            String subjectId,
            String conversationId,
            StudentExplanationContextSummary summary) {
        return false;
    }

    /**
     * Lists durable conversation shells so the frontend can render a ChatGPT-like sidebar.
     * page 从 1 开始：侧边栏“加载更多”按页向后翻，避免一次性返回全部历史。
     */
    List<StudentExplanationConversationSummary> listConversations(
            String tenantId,
            String subjectType,
            String subjectId,
            int limit,
            int page);

    /**
     * Loads one full conversation thread with persisted question/response pairs.
     */
    StudentExplanationConversationDetail loadConversation(
            String tenantId,
            String subjectType,
            String subjectId,
            String conversationId,
            int limit);
}
