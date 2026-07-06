package com.doob.mathagent.infrastructure.text;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Normalizes model-generated math markup to the Feishu-supported delimiters used by the frontend preview.
 */
public final class FormulaMarkupSanitizer {

    private static final Pattern DISPLAY_BRACKET = Pattern.compile("\\\\\\[(.*?)\\\\\\]", Pattern.DOTALL);
    private static final Pattern INLINE_PAREN = Pattern.compile("\\\\\\((.*?)\\\\\\)", Pattern.DOTALL);
    private static final Pattern ALIGN_ENV = Pattern.compile(
            "\\\\begin\\{(align\\*?|aligned|equation\\*?|gather\\*?)\\}(.*?)\\\\end\\{\\1\\}",
            Pattern.DOTALL);
    private static final Pattern BARE_COORDINATE = Pattern.compile(
            "(?<![$A-Za-z0-9])\\((?:\\\\pm\\s*)?[A-Za-z](?:\\^[-+]?\\d+)?,\\s*-?\\d+\\)");
    private static final Pattern BARE_FORMULA = Pattern.compile(
            "(?<![$A-Za-z0-9])((?:\\\\pm\\s*)?[A-Za-z0-9]+(?:[_^][-+]?\\d+)?"
                    + "(?:\\s*[+\\-*/=]\\s*(?:\\\\pm\\s*)?[A-Za-z0-9]+(?:[_^][-+]?\\d+)?)+)(?![$A-Za-z0-9])");
    private static final Pattern BARE_FRACTION_FORMULA = Pattern.compile(
            "(?<![$A-Za-z0-9])([A-Za-z0-9]+(?:\\^[-+]?\\d+)?/[A-Za-z0-9]+(?:\\^[-+]?\\d+)?"
                    + "(?:\\s*[+\\-=]\\s*[A-Za-z0-9]+(?:\\^[-+]?\\d+)?/[A-Za-z0-9]+(?:\\^[-+]?\\d+)?)+"
                    + "(?:\\s*=\\s*-?\\d+)?)");

    private FormulaMarkupSanitizer() {
    }

    /**
     * Converts unsupported LaTeX wrappers such as \[...\], \(...\), and align environments to $ or $$.
     */
    public static String sanitizeFeishuMath(String value) {
        if (value == null || value.isBlank()) {
            return value == null ? "" : value.strip();
        }
        String normalized = replaceEnvironment(value);
        normalized = replaceAll(normalized, DISPLAY_BRACKET, "$$\n%s\n$$");
        normalized = replaceAll(normalized, INLINE_PAREN, "$%s$");
        normalized = normalizeUnicodeMathSymbols(normalized);
        normalized = wrapBareMathOutsideDelimiters(normalized);
        return normalized.strip();
    }

    private static String replaceEnvironment(String value) {
        Matcher matcher = ALIGN_ENV.matcher(value);
        StringBuffer buffer = new StringBuffer();
        while (matcher.find()) {
            String body = matcher.group(2)
                    .replace("&", "")
                    .replace("\\\\", "\n")
                    .strip();
            matcher.appendReplacement(buffer, Matcher.quoteReplacement("$$\n" + body + "\n$$"));
        }
        matcher.appendTail(buffer);
        return buffer.toString();
    }

    private static String replaceAll(String value, Pattern pattern, String replacementFormat) {
        Matcher matcher = pattern.matcher(value);
        StringBuffer buffer = new StringBuffer();
        while (matcher.find()) {
            String body = matcher.group(1).strip();
            matcher.appendReplacement(buffer, Matcher.quoteReplacement(replacementFormat.formatted(body)));
        }
        matcher.appendTail(buffer);
        return buffer.toString();
    }

    private static String normalizeUnicodeMathSymbols(String value) {
        return value
                .replace("⁰", "^0")
                .replace("¹", "^1")
                .replace("²", "^2")
                .replace("³", "^3")
                .replace("⁴", "^4")
                .replace("⁵", "^5")
                .replace("⁶", "^6")
                .replace("⁷", "^7")
                .replace("⁸", "^8")
                .replace("⁹", "^9")
                .replace("₀", "_0")
                .replace("₁", "_1")
                .replace("₂", "_2")
                .replace("₃", "_3")
                .replace("₄", "_4")
                .replace("₅", "_5")
                .replace("₆", "_6")
                .replace("₇", "_7")
                .replace("₈", "_8")
                .replace("₉", "_9")
                .replace("±", "\\pm ");
    }

    private static String wrapBareMathOutsideDelimiters(String value) {
        StringBuilder builder = new StringBuilder();
        StringBuilder segment = new StringBuilder();
        boolean math = false;
        for (int index = 0; index < value.length(); index += 1) {
            if (value.charAt(index) == '$') {
                String delimiter = value.startsWith("$$", index) ? "$$" : "$";
                builder.append(math ? segment : wrapBareMathText(segment.toString()));
                segment.setLength(0);
                builder.append(delimiter);
                math = !math;
                index += delimiter.length() - 1;
            } else {
                segment.append(value.charAt(index));
            }
        }
        builder.append(math ? segment : wrapBareMathText(segment.toString()));
        return builder.toString();
    }

    private static String wrapBareMathText(String value) {
        String withCoordinates = wrapMatches(value, BARE_COORDINATE);
        String withFractions = wrapMatches(withCoordinates, BARE_FRACTION_FORMULA);
        return wrapMatches(withFractions, BARE_FORMULA);
    }

    private static String wrapMatches(String value, Pattern pattern) {
        StringBuilder builder = new StringBuilder();
        StringBuilder segment = new StringBuilder();
        boolean math = false;
        for (int index = 0; index < value.length(); index += 1) {
            if (value.startsWith("$$", index)) {
                builder.append(math ? segment : wrapMatchesInSegment(segment.toString(), pattern));
                segment.setLength(0);
                builder.append("$$");
                math = !math;
                index += 1;
                continue;
            }
            char character = value.charAt(index);
            if (character == '$') {
                builder.append(math ? segment : wrapMatchesInSegment(segment.toString(), pattern));
                segment.setLength(0);
                builder.append(character);
                math = !math;
            } else {
                segment.append(character);
            }
        }
        builder.append(math ? segment : wrapMatchesInSegment(segment.toString(), pattern));
        return builder.toString();
    }

    private static String wrapMatchesInSegment(String value, Pattern pattern) {
        Matcher matcher = pattern.matcher(value);
        StringBuffer buffer = new StringBuffer();
        while (matcher.find()) {
            String formula = matcher.group().strip();
            matcher.appendReplacement(buffer, Matcher.quoteReplacement("$" + formula + "$"));
        }
        matcher.appendTail(buffer);
        return buffer.toString();
    }
}
