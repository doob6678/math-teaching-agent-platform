package com.doob.mathagent.knowledge.dto;

import java.util.List;

/**
 * Request body for creating a question bank item.
 *
 * @param questionTitle compact title
 * @param questionText full question text
 * @param answerJson structured answer JSON
 * @param difficulty difficulty label
 * @param permissionScope requested permission scope; backend downgrades unsafe scopes
 * @param knowledgePointIds linked knowledge point ids
 */
public record QuestionBankItemCreateRequest(
        String questionTitle,
        String questionText,
        String answerJson,
        String difficulty,
        String permissionScope,
        List<String> knowledgePointIds) {
}
