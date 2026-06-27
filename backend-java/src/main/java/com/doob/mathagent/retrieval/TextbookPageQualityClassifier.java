package com.doob.mathagent.retrieval;

import com.doob.mathagent.resources.TextbookChunk;
import java.util.List;

public class TextbookPageQualityClassifier {

    public String label(TextbookChunk chunk, int maxPageNoInBook) {
        String text = entityText(chunk);
        String visible = text.replaceAll("\\s+", "");
        int total = visible.length();
        if (total == 0) {
            return "empty";
        }

        long chineseCount = visible.chars().filter(TextbookPageQualityClassifier::isChinese).count();
        long digitCount = visible.chars().filter(Character::isDigit).count();
        long alphaCount = visible.chars().filter(TextbookPageQualityClassifier::isAsciiAlpha).count();
        double digitRatio = digitCount / (double) total;
        double chineseRatio = chineseCount / (double) total;
        double alphaRatio = alphaCount / (double) total;
        boolean edgePage = chunk.pageNo() <= 3 || (maxPageNoInBook > 0 && chunk.pageNo() >= maxPageNoInBook - 1);
        String head = text.length() <= 500 ? text : text.substring(0, 500);

        if (total >= 120 && digitRatio >= 0.55 && chineseRatio <= 0.18) {
            return "numeric_appendix";
        }
        if (edgePage && total <= 260 && (alphaRatio >= 0.20 || chineseCount <= 80)) {
            return "cover_or_backmatter";
        }
        if (chunk.pageNo() <= 10 && (head.contains("目录") || head.contains("前言"))) {
            return "toc_or_preface";
        }
        return "content_page";
    }

    public double scoreFactor(String label) {
        return switch (label) {
            case "cover_or_backmatter" -> 0.25;
            case "numeric_appendix" -> 0.35;
            case "toc_or_preface" -> 0.55;
            case "empty" -> 0.10;
            default -> 1.0;
        };
    }

    private static String entityText(TextbookChunk chunk) {
        return String.join("\n",
                safe(chunk.bookName()),
                String.join(" / ", nullToEmpty(chunk.chapterPath())),
                safe(chunk.sectionTitle()),
                safe(chunk.text()),
                safe(chunk.formulaText()));
    }

    private static boolean isChinese(int codePoint) {
        return codePoint >= '\u4e00' && codePoint <= '\u9fff';
    }

    private static boolean isAsciiAlpha(int codePoint) {
        int lower = Character.toLowerCase(codePoint);
        return lower >= 'a' && lower <= 'z';
    }

    private static List<String> nullToEmpty(List<String> values) {
        return values == null ? List.of() : values;
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
