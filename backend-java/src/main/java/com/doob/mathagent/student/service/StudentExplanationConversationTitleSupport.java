package com.doob.mathagent.student.service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 讲题会话标题统一规则。
 * 这里集中处理 AI 标题清洗和兜底标题，保证保存、回放、历史列表都走同一套逻辑。
 */
public final class StudentExplanationConversationTitleSupport {

    private static final int MAX_TITLE_LENGTH = 15;
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("MM月dd日");

    private StudentExplanationConversationTitleSupport() {
    }

    /**
     * 优先使用 AI 生成标题；没有可用标题时退回到日期 + 题目前缀。
     */
    public static String resolve(String aiTitle, String questionText, LocalDateTime createdAt) {
        String normalizedAiTitle = normalizeAiTitle(aiTitle);
        if (!normalizedAiTitle.isBlank()) {
            return normalizedAiTitle;
        }
        return fallbackTitle(questionText, createdAt);
    }

    /**
     * 历史读取时优先读数据库标题；老数据没有 title 时仍然能稳定回退。
     */
    public static String resolvePersisted(String persistedTitle, String questionText, LocalDateTime createdAt) {
        String normalizedPersistedTitle = normalizeAiTitle(persistedTitle);
        if (!normalizedPersistedTitle.isBlank()) {
            return normalizedPersistedTitle;
        }
        return fallbackTitle(questionText, createdAt);
    }

    /**
     * 清洗模型返回标题，只保留能直接展示给用户的短标题。
     */
    public static String normalizeAiTitle(String value) {
        String normalized = sanitize(value);
        if (normalized.isBlank() || isGeneric(normalized)) {
            return "";
        }
        return limit(normalized, MAX_TITLE_LENGTH);
    }

    private static String fallbackTitle(String questionText, LocalDateTime createdAt) {
        String datePrefix = DATE_FORMATTER.format(createdAt == null ? LocalDateTime.now() : createdAt);
        String snippet = limit(sanitize(questionText)
                .replaceAll("[$`#*_~|]+", " ")
                .replaceAll("\\s+", " ")
                .strip(), 8);
        if (snippet.isBlank()) {
            return datePrefix + " 讲题";
        }
        return limit(datePrefix + " " + snippet, MAX_TITLE_LENGTH);
    }

    private static String sanitize(String value) {
        if (value == null) {
            return "";
        }
        return value
                .replace('\r', ' ')
                .replace('\n', ' ')
                .replaceAll("\\s+", " ")
                .replaceAll("^[：:，,。.;；、\\-\\s]+", "")
                .replaceAll("[：:，,。.;；、\\-\\s]+$", "")
                .strip();
    }

    private static boolean isGeneric(String value) {
        return "AI讲题".equals(value)
                || "讲题".equals(value)
                || "讲解".equals(value)
                || "解析".equals(value)
                || "说明".equals(value)
                || "解题".equals(value)
                || "数学讲题".equals(value);
    }

    private static String limit(String value, int maxLength) {
        if (value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength).strip();
    }
}
