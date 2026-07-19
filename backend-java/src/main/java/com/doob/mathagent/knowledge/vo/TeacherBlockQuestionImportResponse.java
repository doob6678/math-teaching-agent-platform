package com.doob.mathagent.knowledge.vo;

import java.util.List;

/**
 * Summary returned after importing real teacher resource blocks into the question bank.
 *
 * @param documentId teacher resource document id
 * @param processedBlockCount active parsed blocks inspected
 * @param importedQuestionCount newly created question count
 * @param skippedBlockCount blocks ignored because they did not look like a math question
 * @param duplicateBlockCount blocks skipped because the same source block/checksum was already imported
 * @param linkedKnowledgePointCount distinct knowledge points linked by newly imported questions
 * @param importedQuestions newly imported question rows
 */
public record TeacherBlockQuestionImportResponse(
        String documentId,
        int processedBlockCount,
        int importedQuestionCount,
        int skippedBlockCount,
        int duplicateBlockCount,
        int linkedKnowledgePointCount,
        List<QuestionBankItemResponse> importedQuestions) {
}
