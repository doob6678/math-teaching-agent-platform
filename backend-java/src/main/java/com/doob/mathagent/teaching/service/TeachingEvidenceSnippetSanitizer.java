package com.doob.mathagent.teaching.service;

import java.util.ArrayList;
import java.util.List;

/**
 * Cleans textbook and question-bank snippets before they are shown in handouts or sent into prompts.
 */
final class TeachingEvidenceSnippetSanitizer {

    private TeachingEvidenceSnippetSanitizer() {
    }

    static String sanitizeCompact(String snippet) {
        if (snippet == null || snippet.isBlank()) {
            return "已命中资料片段。";
        }
        List<String> kept = new ArrayList<>();
        for (String rawLine : snippet.replace("\r", "\n").split("\n")) {
            String line = rawLine.strip();
            if (line.isBlank()
                    || line.startsWith("#")
                    || line.startsWith("![")
                    || line.matches("^-\\s*(书名|章节|PDF页码|印刷页码|页图).*?$")
                    || line.matches("^p\\d+$")
                    || line.matches("^\\d+$")
                    || "$$".equals(line)) {
                continue;
            }
            String cleanedLine = line
                    .replaceAll("!\\[[^\\]]*]\\([^)]*\\)", " ")
                    .replaceAll("\\[[^\\]]*]\\([^)]*\\)", " ")
                    .replaceAll("#\\s*p\\d+\\s*-\\s*", " ")
                    .replaceAll("\\bPDF\\s*\\d+\\s*[:：]", " ")
                    .replaceAll("\\s*书名[:：]\\s*", " ")
                    .replaceAll("\\s*-\\s*(书名|章节|PDF页码|印刷页码|页图)[:：][^-#，。；;]*", " ")
                    .replace("$$", " ")
                    .replace("###", " ")
                    .replace("##", " ")
                    .replace("正文", " ")
                    .replaceAll("\\s+", " ")
                    .strip();
            if (cleanedLine.contains("页图")
                    || cleanedLine.contains("PDF页码")
                    || cleanedLine.contains("印刷页码")
                    || cleanedLine.contains("书名:")
                    || cleanedLine.contains("书名：")
                    || cleanedLine.matches(".*\\.\\./\\.\\./pages/.*")) {
                cleanedLine = cleanedLine
                        .replaceAll("页图[:：]?\\s*", " ")
                        .replaceAll("PDF页码[:：]?\\s*\\d*", " ")
                        .replaceAll("印刷页码[:：]?\\s*[^\\s，。；;]*", " ")
                        .replaceAll("书名[:：]?\\s*", " ")
                        .replaceAll("\\.\\./\\.\\./pages/[^\\s，。；;]*", " ")
                        .replaceAll("\\s+", " ")
                        .strip();
            }
            if (!cleanedLine.isBlank()) {
                kept.add(cleanedLine);
            }
        }
        String cleaned = String.join(" ", kept).replaceAll("\\s+", " ").strip();
        if (cleaned.isBlank()) {
            return "已命中资料片段。";
        }
        return cleaned.length() <= 120 ? cleaned : cleaned.substring(0, 120) + "...";
    }
}
