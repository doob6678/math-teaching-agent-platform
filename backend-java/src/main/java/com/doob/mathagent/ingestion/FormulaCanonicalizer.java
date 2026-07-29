package com.doob.mathagent.ingestion;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Conservative formula normalization for candidate comparison. It preserves all symbols and values; unsupported
 * constructs stay visibly represented instead of being guessed into a false exact duplicate.
 */
public final class FormulaCanonicalizer {
    private static final Pattern FRACTION = Pattern.compile("\\\\frac\\s*\\{\\s*([^{}]+?)\\s*}\\s*\\{\\s*([^{}]+?)\\s*}");
    private static final Pattern SUBSCRIPT = Pattern.compile("_\\s*\\{\\s*([^{}]+?)\\s*}");

    private FormulaCanonicalizer() { }

    /** Canonicalizes formatting-only variation while retaining the mathematical argument values. */
    public static String canonicalize(String latex) {
        if (latex == null || latex.isBlank()) {
            return "";
        }
        String normalized = latex.replaceAll("\\s+", "");
        normalized = replace(FRACTION, normalized, "frac($1,$2)");
        normalized = replace(SUBSCRIPT, normalized, "_($1)");
        return normalized;
    }

    private static String replace(Pattern pattern, String input, String replacement) {
        Matcher matcher = pattern.matcher(input);
        StringBuffer output = new StringBuffer();
        while (matcher.find()) {
            matcher.appendReplacement(output, Matcher.quoteReplacement(replacement
                    .replace("$1", matcher.group(1).strip())
                    .replace("$2", matcher.groupCount() >= 2 ? matcher.group(2).strip() : "")));
        }
        matcher.appendTail(output);
        return output.toString();
    }
}
