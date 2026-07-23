package com.doob.mathagent.teaching.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 教学任务提交请求。
 *
 * @param clientRequestId 前端生成的幂等请求号；同一用户重复提交时用于恢复已有任务，避免失败后从头开始。
 * @param questionText 用户输入的题目或学习问题。
 * @param learningGoal 用户想学什么，例如“理解函数新定义题”。
 * @param evidenceLimit 本次检索希望保留的证据数量；后端不会因旧版界面默认值而拒绝合法请求。
 * @param handoutTemplateCode optional backend-owned handout template code selected by the frontend
 * @param watermarkText PDF 页眉、页脚和首页署名。该字段只参与排版，绝不发送给模型。
 * @param aiProviderName 用户从后端允许目录中选择的 AI 提供商；空值使用系统默认。
 * @param aiModelCode 用户从该提供商允许目录中选择的模型；空值使用系统默认。
 * @param supplementaryRequirements 仅用于编排和审校的补充要求，不属于可打印题干。
 */
public record TeachingTaskRequest(
        @NotBlank String clientRequestId,
        @Size(max = 4000) String questionText,
        @NotBlank String learningGoal,
        @Min(1) int evidenceLimit,
        @Size(max = 128) String handoutTemplateCode,
        @Size(max = 32) String watermarkText,
        @Size(max = 32) String aiProviderName,
        @Size(max = 96) String aiModelCode,
        @Size(max = 4000) String supplementaryRequirements) {

    /** Default attribution for every handout when the user does not provide a personal watermark. */
    public static final String DEFAULT_WATERMARK_TEXT = "数学讲义";

    /**
     * Backward-compatible constructor for callers that do not select a template explicitly.
     */
    public TeachingTaskRequest(
            String clientRequestId,
            String questionText,
            String learningGoal,
            int evidenceLimit) {
        this(clientRequestId, questionText, learningGoal, evidenceLimit, null, null, null, null, null);
    }

    /** Preserves callers that still provide the backend-owned layout identifier but no watermark. */
    public TeachingTaskRequest(
            String clientRequestId,
            String questionText,
            String learningGoal,
            int evidenceLimit,
            String handoutTemplateCode) {
        this(clientRequestId, questionText, learningGoal, evidenceLimit, handoutTemplateCode, null, null, null, null);
    }

    /** Preserves task replays that set a watermark but leave model routing to the default provider. */
    public TeachingTaskRequest(
            String clientRequestId,
            String questionText,
            String learningGoal,
            int evidenceLimit,
            String handoutTemplateCode,
            String watermarkText) {
        this(clientRequestId, questionText, learningGoal, evidenceLimit, handoutTemplateCode, watermarkText, null, null, null);
    }

    /**
     * Preserves the pre-supplementary-requirements constructor used by older clients and persisted replay tests.
     * The omitted field is intentionally null so legacy requests keep the same prompt and layout behavior.
     */
    public TeachingTaskRequest(
            String clientRequestId,
            String questionText,
            String learningGoal,
            int evidenceLimit,
            String handoutTemplateCode,
            String watermarkText,
            String aiProviderName,
            String aiModelCode) {
        this(clientRequestId, questionText, learningGoal, evidenceLimit, handoutTemplateCode, watermarkText,
                aiProviderName, aiModelCode, null);
    }

    /**
     * Normalizes a handout request. A handout can be created from only a teaching goal/topic; in that case the backend
     * uses the goal as the searchable question text instead of forcing the frontend to duplicate fields.
     */
    public TeachingTaskRequest normalize() {
        String normalizedGoal = requireText(learningGoal, "learningGoal is required");
        String submittedQuestion = questionText == null || questionText.isBlank()
                ? normalizedGoal
                : questionText.strip();
        // The handout composer labels its second field as supplementary requirements.  Older clients still send
        // that field as questionText; keep the request contract backward compatible but never let layout directives
        // become a printable exercise or an AI-generated question.  Real math questions remain untouched.
        boolean legacySupplement = looksLikeSupplementaryRequirements(submittedQuestion);
        String normalizedQuestion = legacySupplement ? normalizedGoal : submittedQuestion;
        String normalizedSupplement = optionalText(legacySupplement ? submittedQuestion : supplementaryRequirements);
        return new TeachingTaskRequest(
                requireText(clientRequestId, "clientRequestId is required"),
                normalizedQuestion,
                normalizedGoal,
                evidenceLimit,
                handoutTemplateCode == null || handoutTemplateCode.isBlank() ? null : handoutTemplateCode.strip(),
                normalizeWatermark(watermarkText),
                optionalText(aiProviderName),
                optionalText(aiModelCode),
                normalizedSupplement);
    }

    private static boolean looksLikeSupplementaryRequirements(String value) {
        String normalized = value == null ? "" : value.replaceAll("\\s+", "").toLowerCase();
        if (normalized.isBlank()) {
            return false;
        }
        int directiveCount = 0;
        for (String marker : new String[]{"教师版", "学生版", "16:10", "一题一页", "latex", "占位符", "资料页码", "补充要求"}) {
            if (normalized.contains(marker)) {
                directiveCount++;
            }
        }
        boolean containsMathQuestionCue = normalized.matches(".*(已知|求|证明|解:\\s*|f\\(|x\\^|[=＝$]).*");
        return directiveCount >= 2 && !containsMathQuestionCue;
    }

    /**
     * Keeps user attribution printable and deterministic. Control characters cannot reach either XeLaTeX or PDFBox,
     * and collapsing whitespace means preview, download, and a later task resume use exactly the same label.
     */
    private static String normalizeWatermark(String value) {
        if (value == null || value.isBlank()) {
            return DEFAULT_WATERMARK_TEXT;
        }
        String normalized = value.replaceAll("[\\p{Cntrl}]", "").replaceAll("\\s+", " ").strip();
        return normalized.isEmpty() ? DEFAULT_WATERMARK_TEXT : normalized;
    }

    /** Normalizes optional model routing without making blank controls part of the persisted request contract. */
    private static String optionalText(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private static String requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value.strip();
    }
}
