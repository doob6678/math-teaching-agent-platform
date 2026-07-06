package com.doob.mathagent.teaching.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Formats question-bank answer metadata into teacher-readable Chinese text without exposing raw JSON keys.
 */
final class QuestionBankAnswerFormatter {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final Pattern INLINE_ALGEBRA = Pattern.compile(
            "(?<![A-Za-z0-9_$\\\\])((?:\\d+[A-Za-z]|[A-Za-z](?:\\^\\d+)?)(?:\\s*(?:=|≤|≥|<|>)\\s*-?\\d+(?:\\.\\d+)?)|[A-Za-z]\\^\\d+)(?![A-Za-z0-9_$])");

    private QuestionBankAnswerFormatter() {
    }

    static String format(String rawAnswer) {
        if (rawAnswer == null || rawAnswer.isBlank()) {
            return "";
        }
        String answer = rawAnswer.strip();
        if (answer.startsWith("{") || answer.startsWith("[")) {
            try {
                JsonNode node = OBJECT_MAPPER.readTree(answer);
                String formatted = formatNode(node, "");
                if (!formatted.isBlank()) {
                    return formatted;
                }
            } catch (JsonProcessingException ignored) {
                // Fall through to tolerant text cleanup for partially malformed question-bank metadata.
            }
        }
        return cleanupPlainText(answer);
    }

    private static String formatNode(JsonNode node, String parentKey) {
        if (node == null || node.isNull()) {
            return "";
        }
        if (node.isTextual() || node.isNumber() || node.isBoolean()) {
            return cleanupPlainText(node.asText());
        }
        if (node.isArray()) {
            List<String> items = new ArrayList<>();
            int index = 1;
            for (JsonNode item : node) {
                String text = formatNode(item, parentKey);
                if (!text.isBlank()) {
                    items.add(index + ". " + text);
                    index += 1;
                }
            }
            return String.join("；", items);
        }
        if (!node.isObject()) {
            return cleanupPlainText(node.toString());
        }
        List<String> parts = new ArrayList<>();
        int unknownIndex = 1;
        Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            String label = labelFor(field.getKey(), unknownIndex);
            if (label.startsWith("补充")) {
                unknownIndex += 1;
            }
            String value = formatNode(field.getValue(), field.getKey());
            if (!value.isBlank()) {
                parts.add(label + "：" + value);
            }
        }
        return String.join("；", parts);
    }

    private static String labelFor(String key, int unknownIndex) {
        String normalized = key == null ? "" : key.strip().toLowerCase();
        return switch (normalized) {
            case "answer", "final_answer", "finalanswer", "result", "答案" -> "答案";
            case "solution", "analysis", "explanation", "解析", "解法" -> "解析";
            case "steps", "step", "process", "reasoning", "过程", "步骤" -> "步骤";
            case "scoring", "score", "points", "rubric", "评分", "评分点" -> "评分点";
            case "method", "methods", "strategy", "方法" -> "方法";
            case "hint", "hints", "提示" -> "提示";
            case "difficulty", "难度" -> "难度";
            default -> "补充" + unknownIndex;
        };
    }

    private static String cleanupPlainText(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String cleaned = value
                .replace("\"answer\"", "答案")
                .replace("\"final_answer\"", "答案")
                .replace("\"solution\"", "解析")
                .replace("\"analysis\"", "解析")
                .replace("\"explanation\"", "解析")
                .replace("\"steps\"", "步骤")
                .replace("\"scoring\"", "评分点")
                .replace("\"score\"", "评分点")
                .replace("\"points\"", "评分点")
                .replace("\"method\"", "方法")
                .replace("\"hint\"", "提示")
                .replace("\"", "")
                .replace("{", "")
                .replace("}", "")
                .replace("[", "")
                .replace("]", "")
                .replace(":", "：")
                .replace(",", "，")
                .replaceAll("\\s+", " ")
                .strip();
        return normalizeInlineMath(cleaned);
    }

    private static String normalizeInlineMath(String value) {
        if (value.isBlank()) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        StringBuilder segment = new StringBuilder();
        boolean math = false;
        for (int index = 0; index < value.length(); index += 1) {
            char character = value.charAt(index);
            if (character == '$') {
                builder.append(math ? segment : wrapAlgebraInText(segment.toString()));
                segment.setLength(0);
                builder.append(character);
                math = !math;
            } else {
                segment.append(character);
            }
        }
        builder.append(math ? segment : wrapAlgebraInText(segment.toString()));
        return builder.toString();
    }

    private static String wrapAlgebraInText(String value) {
        Matcher matcher = INLINE_ALGEBRA.matcher(value);
        StringBuilder builder = new StringBuilder();
        while (matcher.find()) {
            matcher.appendReplacement(builder, Matcher.quoteReplacement("$" + matcher.group(1).replaceAll("\\s+", "") + "$"));
        }
        matcher.appendTail(builder);
        return builder.toString();
    }
}
