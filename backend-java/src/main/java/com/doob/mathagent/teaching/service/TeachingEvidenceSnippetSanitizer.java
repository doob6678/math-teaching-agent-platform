package com.doob.mathagent.teaching.service;

import java.util.ArrayList;
import java.util.List;

/**
 * Cleans textbook and question-bank snippets before they are shown in handouts or sent into prompts.
 */
final class TeachingEvidenceSnippetSanitizer {
    static final String LOW_QUALITY_SNIPPET = "已命中资料页，片段质量较低，建议以 PDF 原页复核。";

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
            cleanedLine = stripSuspiciousFragments(cleanedLine);
            if (!cleanedLine.isBlank() && !isLikelyOcrGarbage(cleanedLine)) {
                kept.add(cleanedLine);
            }
        }
        String cleaned = String.join(" ", kept).replaceAll("\\s+", " ").strip();
        if (cleaned.isBlank()) {
            return LOW_QUALITY_SNIPPET;
        }
        if (isLikelyOcrGarbage(cleaned)) {
            return LOW_QUALITY_SNIPPET;
        }
        return cleaned.length() <= 120 ? cleaned : cleaned.substring(0, 120) + "...";
    }

    private static String stripSuspiciousFragments(String value) {
        StringBuilder builder = new StringBuilder();
        boolean previousWasSpace = false;
        for (int offset = 0; offset < value.length(); ) {
            int codePoint = value.codePointAt(offset);
            offset += Character.charCount(codePoint);
            boolean keep = Character.isWhitespace(codePoint)
                    || isChinese(codePoint)
                    || isAsciiReadable(codePoint)
                    || isCommonMathSymbol(codePoint);
            if (keep) {
                if (Character.isWhitespace(codePoint)) {
                    if (!previousWasSpace) {
                        builder.append(' ');
                    }
                    previousWasSpace = true;
                } else {
                    builder.appendCodePoint(codePoint);
                    previousWasSpace = false;
                }
            } else if (!previousWasSpace) {
                builder.append(' ');
                previousWasSpace = true;
            }
        }
        return builder.toString().replaceAll("\\s+", " ").strip();
    }

    private static boolean isLikelyOcrGarbage(String value) {
        String compact = value == null ? "" : value.replaceAll("\\s+", "");
        if (compact.length() < 6) {
            return false;
        }
        int suspicious = 0;
        int readable = 0;
        int chinese = 0;
        int replacement = 0;
        for (int offset = 0; offset < compact.length(); ) {
            int codePoint = compact.codePointAt(offset);
            offset += Character.charCount(codePoint);
            if (codePoint == 0xFFFD) {
                replacement += 1;
                suspicious += 1;
                continue;
            }
            if (isChinese(codePoint)) {
                chinese += 1;
                readable += 1;
                continue;
            }
            if (isAsciiReadable(codePoint) || isCommonMathSymbol(codePoint)) {
                readable += 1;
                continue;
            }
            if (isSuspiciousUnicodeBlock(codePoint)) {
                suspicious += 1;
            }
        }
        if (replacement > 0) {
            return true;
        }
        double suspiciousRatio = suspicious / (double) compact.codePointCount(0, compact.length());
        double readableRatio = readable / (double) compact.codePointCount(0, compact.length());
        return suspiciousRatio >= 0.18 || (chinese == 0 && suspiciousRatio >= 0.10 && readableRatio < 0.82);
    }

    private static boolean isChinese(int codePoint) {
        Character.UnicodeBlock block = Character.UnicodeBlock.of(codePoint);
        return block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS
                || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A
                || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_B
                || block == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS;
    }

    private static boolean isAsciiReadable(int codePoint) {
        return (codePoint >= '0' && codePoint <= '9')
                || (codePoint >= 'A' && codePoint <= 'Z')
                || (codePoint >= 'a' && codePoint <= 'z')
                || ".,;:!?+-=*/^_()[]{}<>|\\$%&'\"#".indexOf(codePoint) >= 0;
    }

    private static boolean isCommonMathSymbol(int codePoint) {
        return "，。；：！？、（）【】《》“”‘’·≈≠≤≥±×÷∈∉⊂⊃∩∪∞√∑∏∠△°αβγθλμπΔΩ".indexOf(codePoint) >= 0;
    }

    private static boolean isSuspiciousUnicodeBlock(int codePoint) {
        Character.UnicodeBlock block = Character.UnicodeBlock.of(codePoint);
        return block == Character.UnicodeBlock.CYRILLIC
                || block == Character.UnicodeBlock.CYRILLIC_SUPPLEMENTARY
                || block == Character.UnicodeBlock.CYRILLIC_EXTENDED_A
                || block == Character.UnicodeBlock.CYRILLIC_EXTENDED_B
                || block == Character.UnicodeBlock.RUNIC
                || block == Character.UnicodeBlock.ARMENIAN
                || block == Character.UnicodeBlock.GEORGIAN
                || block == Character.UnicodeBlock.SYRIAC
                || block == Character.UnicodeBlock.UNIFIED_CANADIAN_ABORIGINAL_SYLLABICS
                || block == Character.UnicodeBlock.LATIN_EXTENDED_ADDITIONAL;
    }
}
