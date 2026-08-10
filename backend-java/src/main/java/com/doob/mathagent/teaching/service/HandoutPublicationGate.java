package com.doob.mathagent.teaching.service;

import com.doob.mathagent.teaching.vo.TeachingTaskResponse;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Independent, deterministic publication gate for every protected handout version.
 *
 * <p>Python can complete an AI graph, but only this Java gate may allow a document to reach XeLaTeX. Keeping the
 * question-order and LaTeX-structure checks here makes a malformed or reordered response fail before rendering.</p>
 */
public final class HandoutPublicationGate {
    private static final Pattern QUESTION_HEADING = Pattern.compile(
            "^\\\\(?:section|subsection)\\*?\\{第\\s*(\\d+)\\s*题[^}]*}\\s*$", Pattern.MULTILINE);
    private static final Pattern MALFORMED_FRAC = Pattern.compile("\\\\frac(?!\\s*\\{)");
    /**
     * Student handouts may state questions and hints, but never contain teacher-only solution or scoring blocks.
     * The final LaTeX body is checked so a completed Python graph cannot bypass Java publication authority.
     */
    private static final Pattern STUDENT_ANSWER_LEAKAGE = Pattern.compile(
            "(?:\\\\paragraph\\s*\\{(?:答案与评分点|参考答案|参考解析|评分标准|完整解析|教师讲解|教师备注)[^}]*}|答案与评分点|参考答案|参考解析|评分标准|完整解析|教师讲解|教师备注)");
    private static final Pattern INTERNAL_METADATA = Pattern.compile(
            "(?i)(?:promptTokens|completionTokens|totalTokens|model_call_|json_parse_|system prompt|\u5185\u90e8\u63d0\u793a词)");

    /** Validates one task version, including the existing evidence/asset/answer policy. */
    public void validate(TeachingTaskResponse task, String version) {
        if (task == null) {
            throw new IllegalStateException("讲义任务不存在，禁止发布");
        }
        String source = task.handoutLatexFor(version);
        validateLatex(source, version);
        TeachingHandoutPdfExportPolicyPartA.validatePublicationSource(task, version);
    }

    /** Structural checks are exposed for focused tests and do not require a PDF engine or filesystem asset. */
    void validateLatex(String source, String version) {
        String value = source == null ? "" : source;
        if (value.isBlank()) {
            throw new IllegalStateException(version + " 版正文为空，禁止发布");
        }
        if (INTERNAL_METADATA.matcher(value).find()) {
            throw new IllegalStateException(version + " 版包含内部模型元数据，禁止发布");
        }
        if (MALFORMED_FRAC.matcher(value).find()) {
            throw new IllegalStateException(version + " 版包含未成组的 \\frac，禁止发布");
        }
        if ("student".equalsIgnoreCase(version) && STUDENT_ANSWER_LEAKAGE.matcher(value).find()) {
            throw new IllegalStateException("学生版包含教师答案或评分内容，禁止发布");
        }
        validateQuestionOrder(value, version);
    }

    private static void validateQuestionOrder(String source, String version) {
        Matcher matcher = QUESTION_HEADING.matcher(source.replace("\r\n", "\n"));
        int expected = 1;
        int seen = 0;
        while (matcher.find()) {
            int actual = Integer.parseInt(matcher.group(1));
            if (actual != expected) {
                throw new IllegalStateException(version + " 版题号不连续：期望第" + expected + "题，实际第" + actual + "题");
            }
            expected += 1;
            seen += 1;
        }
        // A document without numbered headings can be a topic-only lesson; once numbering starts it must be complete.
        if (seen > 0 && expected != seen + 1) {
            throw new IllegalStateException(version + " 版题号校验失败");
        }
    }
}
