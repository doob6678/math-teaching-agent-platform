package com.doob.mathagent.teaching.vo;

import java.util.List;

/**
 * Teaching handout template metadata exposed to the frontend and task history.
 *
 * @param templateCode stable backend template code
 * @param displayName human-readable template name
 * @param sourceType template source type, such as builtin, pdf, or latex
 * @param audience template audience, such as teacher, student, or mixed
 * @param description concise layout/usage summary
 * @param category frontend shelf category, such as 基础讲义、专题训练、教师详解
 * @param visualStyle concise visual layout name
 * @param difficultyBands supported difficulty bands
 * @param tags short searchable tags shown on the frontend shelf
 * @param referenceTitle optional local reference material title
 * @param referencePath optional local reference path kept for teacher/admin review
 * @param referencePreview optional short text extracted from the reference material
 * @param blankSpaceEm default printable blank space in em units for student exercises
 * @param questionGapEm default spacing in em units between continuous questions
 */
public record TeachingHandoutTemplateResponse(
        String templateCode,
        String displayName,
        String sourceType,
        String audience,
        String description,
        String category,
        String visualStyle,
        List<String> difficultyBands,
        List<String> tags,
        String referenceTitle,
        String referencePath,
        String referencePreview,
        Integer blankSpaceEm,
        Integer questionGapEm) {
}
