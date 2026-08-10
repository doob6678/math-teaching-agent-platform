package com.doob.mathagent.agent.service;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Converts an MCP batch-writing input into independently identifiable math questions.
 *
 * <p>Math expressions routinely contain spaces and blank lines, so neither is a valid question boundary. The parser
 * accepts a structured {@code questions} array first, then only explicit standalone separators or conventional
 * Chinese question headings from the fallback text. This makes the model prompt deterministic without guessing from
 * natural-language formatting.</p>
 */
public final class MultiQuestionTextParser {

    private static final Pattern STANDALONE_SEPARATOR = Pattern.compile("^\\s*(?:---+|#{3,}|<<<QUESTION>>>)\\s*$");
    private static final Pattern QUESTION_HEADING = Pattern.compile("^\\s*(?:第\\s*\\d+\\s*题|题目\\s*\\d+|[Qq]uestion\\s*\\d+)\\s*[:：]?.*$", Pattern.UNICODE_CASE);

    private MultiQuestionTextParser() {
    }

    /**
     * Returns nonblank questions from structured input or explicitly marked fallback text.
     *
     * @param structuredQuestions MCP {@code questions} array, which always takes precedence when present
     * @param fallbackText legacy {@code questionText} value
     * @return ordered question texts without delimiter lines
     */
    public static List<String> parse(List<String> structuredQuestions, String fallbackText) {
        List<String> normalizedStructured = normalizedStructuredQuestions(structuredQuestions);
        if (!normalizedStructured.isEmpty()) {
            return normalizedStructured;
        }
        return splitExplicitlyMarkedText(fallbackText);
    }

    /**
     * Builds the one prompt field consumed by the current durable writing workflow.
     *
     * <p>Each question receives a stable visual label, allowing every downstream writer to cite and lay out an
     * individual question while preserving the existing one-workflow ownership, trace, recovery, and export model.</p>
     *
     * @param structuredQuestions MCP {@code questions} array
     * @param fallbackText legacy {@code questionText} value
     * @return a prompt-safe, labeled question collection
     */
    public static String canonicalizeForWorkflow(List<String> structuredQuestions, String fallbackText) {
        List<String> questions = parse(structuredQuestions, fallbackText);
        if (questions.isEmpty()) {
            return "";
        }
        if (questions.size() == 1) {
            return questions.getFirst();
        }
        StringBuilder canonical = new StringBuilder();
        for (int index = 0; index < questions.size(); index++) {
            if (index > 0) {
                canonical.append("\n\n---\n\n");
            }
            canonical.append("【题目 ").append(index + 1).append("】\n").append(questions.get(index));
        }
        return canonical.toString();
    }

    private static List<String> normalizedStructuredQuestions(List<String> structuredQuestions) {
        if (structuredQuestions == null || structuredQuestions.isEmpty()) {
            return List.of();
        }
        return structuredQuestions.stream()
                .filter(question -> question != null && !question.isBlank())
                .map(String::strip)
                .toList();
    }

    private static List<String> splitExplicitlyMarkedText(String fallbackText) {
        if (fallbackText == null || fallbackText.isBlank()) {
            return List.of();
        }
        List<String> questions = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String line : fallbackText.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1)) {
            boolean startsNextQuestion = QUESTION_HEADING.matcher(line).matches() && !current.toString().strip().isBlank();
            if (STANDALONE_SEPARATOR.matcher(line).matches() || startsNextQuestion) {
                addQuestion(questions, current);
                if (startsNextQuestion) {
                    current.append(line.strip());
                }
                continue;
            }
            if (!current.isEmpty()) {
                current.append('\n');
            }
            current.append(line);
        }
        addQuestion(questions, current);
        return List.copyOf(questions);
    }

    private static void addQuestion(List<String> questions, StringBuilder current) {
        String question = current.toString().strip();
        if (!question.isBlank()) {
            questions.add(question);
        }
        current.setLength(0);
    }
}
