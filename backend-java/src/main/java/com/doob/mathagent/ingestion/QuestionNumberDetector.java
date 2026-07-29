package com.doob.mathagent.ingestion;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Detects a printed top-level question number only at the beginning of a text line. It is a boundary hint, never the
 * sole question identity: layout regions and reviewer decisions remain authoritative for columns and cross-page work.
 */
public final class QuestionNumberDetector {
    private static final Pattern ARABIC_NUMBER = Pattern.compile(
            "^\\s*([1-9][0-9]*)\\s*(?:[.．、]|[.．]\\s*\\([0-9]+\\))");
    private static final Pattern CHINESE_PREFIX = Pattern.compile("^\\s*第\\s*([1-9][0-9]*)\\s*题(?:\\s*[:：])?");

    private QuestionNumberDetector() { }

    /**
     * Returns the leading top-level question number or empty when the line is a sub-question, year, or ordinary text.
     */
    public static Optional<String> topLevelNumber(String line) {
        if (line == null || line.isBlank()) {
            return Optional.empty();
        }
        Matcher chinese = CHINESE_PREFIX.matcher(line);
        if (chinese.find()) {
            return Optional.of(chinese.group(1));
        }
        Matcher arabic = ARABIC_NUMBER.matcher(line);
        return arabic.find() ? Optional.of(arabic.group(1)) : Optional.empty();
    }
}
