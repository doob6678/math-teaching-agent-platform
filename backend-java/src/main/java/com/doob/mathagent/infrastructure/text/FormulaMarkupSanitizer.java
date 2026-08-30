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
    private static final Pattern BARE_FRACTION_COMMAND = Pattern.compile(
            "(?<![$\\\\A-Za-z0-9_])(\\\\frac\\{[^{}]+}\\{[^{}]+})(?![$A-Za-z0-9_])");
    /** Source DOCX math often retains these commands without surrounding dollar delimiters. */
    private static final Pattern BARE_VECTOR_OR_OPERATOR_COMMAND = Pattern.compile(
            "(?<![$\\\\A-Za-z0-9_])(\\\\(?:times|cdot|vec|overrightarrow|sin|cos|tan|ln|sqrt)"
                    + "(?:\\{[^{}]+})?)(?![$A-Za-z0-9_])");
    /** A vector equal to a coordinate tuple must stay in one math span; splitting it makes the tuple render as prose. */
    private static final Pattern VECTOR_COORDINATE_EQUATION = Pattern.compile(
            "(?<![$\\\\A-Za-z0-9_])(\\\\(?:vec|overrightarrow)\\{[^{}]+}\\s*=\\s*\\([^()\\n]+\\))");
    private static final String MATH_ATOM = "(?:[+\\-]?\\s*(?:\\\\frac\\{[^{}]+}\\{[^{}]+}"
            + "|\\\\sqrt\\{[^{}]+}"
            + "|\\\\(?:vec|overrightarrow)\\{[^{}]+}"
            + "|(?:\\\\pm\\s*)?[A-Za-z0-9]+(?:[_^]\\{?[-+]?\\d+}?)?"
            + "|\\d+(?:\\.\\d+)?))";
    private static final Pattern BARE_FORMULA = Pattern.compile(
            "(?<![$\\\\A-Za-z0-9_])(" + MATH_ATOM + "(?:\\s*[+\\-*/=]\\s*" + MATH_ATOM + ")+)(?![$A-Za-z0-9_])");
    private static final Pattern SIMPLE_FRACTION_LEFT_HEAVY = Pattern.compile(
            "(?<![\\^_])(\\([^)]+\\)|\\{[^}]+}|(?:[A-Za-z][A-Za-z0-9]*|\\d+))\\s*/\\s*(\\([^)]+\\)|\\{[^}]+}|[A-Za-z][A-Za-z0-9]*)");
    private static final Pattern SIMPLE_FRACTION_RIGHT_HEAVY = Pattern.compile(
            "(?<![\\^_])(\\([^)]+\\)|\\{[^}]+}|[A-Za-z][A-Za-z0-9]*)\\s*/\\s*(\\([^)]+\\)|\\{[^}]+}|(?:[A-Za-z][A-Za-z0-9]*|\\d+))");
    /** A power followed by a denominator is one fraction, not an exponent whose tail is a fraction. */
    private static final Pattern POWER_OVER_INTEGER = Pattern.compile(
            "(?<![A-Za-z0-9_])([A-Za-z](?:\\^\\{?[-+]?\\d+}?)?)\\s*/\\s*([1-9]\\d*)");
    private static final Pattern SHORT_NUMERIC_FRACTION = Pattern.compile(
            "(?<![A-Za-z0-9/^_])([1-9]\\d?)\\s*/\\s*([1-9]\\d?)(?![A-Za-z0-9/])");
    private static final Pattern FRACTION_POWER = Pattern.compile("\\\\frac\\{([^{}]+)}\\{([^{}]+)}\\^([A-Za-z0-9]+)");
    /** Model output occasionally omits the first brace in a valid TeX fraction command. */
    private static final Pattern UNBRACED_FRACTION_NUMERATOR = Pattern.compile(
            "\\\\frac\\s+([A-Za-z0-9])\\s*(\\{[^{}]+})");
    /** A parenthesised or braced radical has an unambiguous radicand and can be made canonical safely. */
    private static final Pattern UNICODE_RADICAL_GROUP = Pattern.compile("√\\s*(\\([^()]+\\)|\\{[^{}]+})");
    /** A one-symbol radical is unambiguous; longer bare forms are rejected by the PDF export gate. */
    private static final Pattern UNICODE_RADICAL_SINGLE_ATOM = Pattern.compile("√\\s*([0-9A-Za-z])(?![A-Za-z0-9])");
    private static final Pattern ALL_UPPERCASE_LATIN = Pattern.compile("[A-Z]{2,}");
    /**
     * Some model/JSON adapters append an empty display-math pair to an otherwise complete line, for example
     * {@code $$a=2.$$$$}.  Only a trailing empty pair is removed; adjacent non-empty formulas and mixed delimiters
     * remain untouched so the export validator can still reject genuinely ambiguous markup.
     */
    private static final Pattern TRAILING_EMPTY_DISPLAY_PAIR = Pattern.compile(
            "(?m)(?<!\\\\)(\\$\\$[^$\\r\\n]*?\\$\\$)(?:\\$\\$)+\\s*$");
    /** Model output can duplicate the opening delimiter between two enumerated atoms, for example `$a、$b$`. */
    private static final Pattern BROKEN_ADJACENT_INLINE_SPANS = Pattern.compile(
            "\\$([^$\\r\\n]+)(、|，|,)\\$([^$\\r\\n]+)\\$");
    /** A closing/opening inline delimiter can lose its separating space before a standalone TeX operator. */
    private static final Pattern ADJACENT_INLINE_OPERATOR_SPANS = Pattern.compile(
            "(\\$[^$\\r\\n]+)\\$\\$(\\\\(?:times|cdot))\\$");
    /** CJK words are valid prose, but raw CJK inside TeX math is an undefined control/input in XeLaTeX. */
    private static final Pattern CJK_TEXT_RUN = Pattern.compile("[\\u3400-\\u4DBF\\u4E00-\\u9FFF\\uF900-\\uFAFF]+");
    private FormulaMarkupSanitizer() {
    }

    /**
     * Converts unsupported LaTeX wrappers such as \[...\], \(...\), and align environments to $ or $$.
     */
    public static String sanitizeFeishuMath(String value) {
        if (value == null || value.isBlank()) {
            return value == null ? "" : value.strip();
        }
        String normalized = normalizeLegacyLatexEscapes(value);
        normalized = normalizeAdjacentInlineOperatorSpans(normalized);
        // A complete model-authored line can contain several independent inline formulas. Once every formula is
        // already delimited and no prose segment carries a bare math transport form, the later heuristic wrappers
        // have no legitimate work and can only risk crossing a closed delimiter boundary.
        if (hasOnlyCanonicalDelimitedMath(normalized)) {
            return normalized.strip();
        }
        normalized = normalizeBrokenAdjacentInlineSpans(normalized);
        normalized = normalizeUnpairedInlineDollarSigns(normalized);
        normalized = normalizeTrailingEmptyDisplayPair(normalized);
        normalized = normalizeFractionAndRadicalCommands(normalized);
        normalized = replaceEnvironment(normalized);
        normalized = replaceAll(normalized, DISPLAY_BRACKET, "$$\n%s\n$$");
        normalized = replaceAll(normalized, INLINE_PAREN, "$%s$");
        normalized = normalizeUnicodeMathSymbols(normalized);
        normalized = normalizeSlashFractions(normalized);
        normalized = wrapBareMathOutsideDelimiters(normalized);
        normalized = cleanupFractionMathDelimiters(normalized);
        normalized = wrapMatches(normalized, VECTOR_COORDINATE_EQUATION);
        normalized = wrapMatches(normalized, BARE_FRACTION_COMMAND);
        normalized = wrapMatches(normalized, BARE_VECTOR_OR_OPERATOR_COMMAND);
        // Operator wrapping can run inside an already recovered fraction denominator; clean it once more before
        // returning the shared canonical representation to browser and XeLaTeX exporters.
        normalized = cleanupFractionMathDelimiters(normalized);
        // The preceding cleanup may expose a complete command that was temporarily interrupted by a nested token;
        // wrap that recovered command so it cannot later be escaped as prose by the PDF renderer.
        normalized = wrapMatches(normalized, BARE_FRACTION_COMMAND);
        normalized = wrapMatches(normalized, VECTOR_COORDINATE_EQUATION);
        normalized = wrapMatches(normalized, BARE_VECTOR_OR_OPERATOR_COMMAND);
        return cleanupFractionMathDelimiters(normalized).strip();
    }

    private static boolean hasOnlyCanonicalDelimitedMath(String value) {
        if (value.contains("\\[") || value.contains("\\]") || value.contains("\\(") || value.contains("\\)")
                || value.indexOf('√') >= 0 || value.indexOf('⊥') >= 0 || value.indexOf('∥') >= 0
                || value.indexOf('‖') >= 0 || value.indexOf('∠') >= 0) {
            return false;
        }
        boolean math = false;
        boolean hasDelimiter = false;
        boolean cjkInsideMath = false;
        StringBuilder prose = new StringBuilder();
        for (int index = 0; index < value.length(); index += 1) {
            if (value.startsWith("$$", index)) {
                math = !math;
                hasDelimiter = true;
                index += 1;
                continue;
            }
            char character = value.charAt(index);
            if (character == '$' && (index == 0 || value.charAt(index - 1) != '\\')) {
                math = !math;
                hasDelimiter = true;
                continue;
            }
            if (math && CJK_TEXT_RUN.matcher(String.valueOf(character)).matches()) {
                cjkInsideMath = true;
            }
            if (!math) {
                prose.append(character);
            }
        }
        if (!hasDelimiter || math || cjkInsideMath || value.contains("\\perp") || value.contains("\\parallel")
                || value.chars().anyMatch(character -> "⁰¹²³⁴⁵⁶⁷⁸⁹₀₁₂₃₄₅₆₇₈₉±×÷".indexOf(character) >= 0)) {
            return false;
        }
        String outsideMath = prose.toString();
        return !BARE_COORDINATE.matcher(outsideMath).find()
                && !BARE_FRACTION_COMMAND.matcher(outsideMath).find()
                && !BARE_VECTOR_OR_OPERATOR_COMMAND.matcher(outsideMath).find()
                && !VECTOR_COORDINATE_EQUATION.matcher(outsideMath).find()
                && !BARE_FORMULA.matcher(outsideMath).find();
    }

    private static String normalizeUnpairedInlineDollarSigns(String value) {
        StringBuilder normalized = new StringBuilder(value.length());
        boolean inlineMathOpen = false;
        for (int index = 0; index < value.length(); index += 1) {
            char current = value.charAt(index);
            boolean escaped = index > 0 && value.charAt(index - 1) == '\\';
            boolean displayDelimiter = current == '$' && index + 1 < value.length()
                    && value.charAt(index + 1) == '$' && !escaped;
            if (displayDelimiter) {
                normalized.append("$$");
                index += 1;
                continue;
            }
            if (current == '$' && !escaped) {
                inlineMathOpen = !inlineMathOpen;
            }
            normalized.append(current);
        }
        if (inlineMathOpen) {
            int lastInlineDollar = normalized.lastIndexOf("$");
            normalized.replace(lastInlineDollar, lastInlineDollar + 1, "\\$");
        }
        return normalized.toString();
    }

    private static String normalizeBrokenAdjacentInlineSpans(String value) {
        Matcher matcher = BROKEN_ADJACENT_INLINE_SPANS.matcher(value);
        StringBuffer buffer = new StringBuffer();
        while (matcher.find()) {
            matcher.appendReplacement(buffer, Matcher.quoteReplacement(
                    "$" + matcher.group(1) + matcher.group(2) + matcher.group(3) + "$"));
        }
        matcher.appendTail(buffer);
        return buffer.toString();
    }

    /**
     * Repairs the unambiguous transport form {@code $a$$\times$} as {@code $a$ $\times$}. The two original inline
     * math spans remain independent, so this does not combine or reinterpret the teacher-authored expression.
     */
    private static String normalizeAdjacentInlineOperatorSpans(String value) {
        Matcher matcher = ADJACENT_INLINE_OPERATOR_SPANS.matcher(value);
        StringBuffer buffer = new StringBuffer();
        while (matcher.find()) {
            matcher.appendReplacement(buffer, Matcher.quoteReplacement(matcher.group(1) + "$ $" + matcher.group(2) + "$"));
        }
        matcher.appendTail(buffer);
        return buffer.toString();
    }

    /**
     * Repairs only the observed empty trailing display block.  This is deterministic transport cleanup, not a
     * semantic formula rewrite: the completed expression inside the first pair is retained byte-for-byte.
     */
    private static String normalizeTrailingEmptyDisplayPair(String value) {
        Matcher matcher = TRAILING_EMPTY_DISPLAY_PAIR.matcher(value);
        StringBuffer buffer = new StringBuffer();
        while (matcher.find()) {
            matcher.appendReplacement(buffer, Matcher.quoteReplacement(matcher.group(1)));
        }
        matcher.appendTail(buffer);
        return buffer.toString();
    }

    /**
     * Canonicalizes only mathematically unambiguous transport forms before formula detection.  This deliberately
     * does not consume {@code √3a}: an exporter must reject that ambiguous source rather than silently deciding
     * whether the radicand is {@code 3} or {@code 3a}.
     */
    private static String normalizeFractionAndRadicalCommands(String value) {
        Matcher fraction = UNBRACED_FRACTION_NUMERATOR.matcher(value);
        StringBuffer fractionBuffer = new StringBuffer();
        while (fraction.find()) {
            fraction.appendReplacement(fractionBuffer,
                    Matcher.quoteReplacement("\\\\frac{" + fraction.group(1) + "}" + fraction.group(2)));
        }
        fraction.appendTail(fractionBuffer);
        Matcher groupedRadical = UNICODE_RADICAL_GROUP.matcher(fractionBuffer.toString());
        StringBuffer radicalBuffer = new StringBuffer();
        while (groupedRadical.find()) {
            String radicand = groupedRadical.group(1);
            groupedRadical.appendReplacement(radicalBuffer,
                    Matcher.quoteReplacement("\\\\sqrt{" + radicand.substring(1, radicand.length() - 1) + "}"));
        }
        groupedRadical.appendTail(radicalBuffer);
        return UNICODE_RADICAL_SINGLE_ATOM.matcher(radicalBuffer.toString())
                .replaceAll("\\\\sqrt{$1}");
    }

    /**
     * Older workflow stages sometimes stored already-escaped LaTeX text such as \textasciicircum{} or
     * \textbackslash{}frac in plain content fields. Convert those forms back before detecting bare formulas so
     * teacher/student handouts can still render standard math.
     */
    private static String normalizeLegacyLatexEscapes(String value) {
        String normalized = value
                .replace("\\textasciicircum{}", "^")
                .replace("\\textasciitilde{}", "~")
                .replace("\\textbackslash{}frac", "\\frac")
                .replace("\\textbackslash{}sqrt", "\\sqrt")
                .replace("\\textbackslash{}sin", "\\sin")
                .replace("\\textbackslash{}cos", "\\cos")
                .replace("\\textbackslash{}tan", "\\tan")
                .replace("\\textbackslash{}ln", "\\ln")
                .replace("\\textbackslash{}log", "\\log");
        // OCR commonly splits a compact fraction or radical with spaces. Recover the mathematical structure
        // before the generic wrapping pass so the PDF and browser receive the same canonical TeX.
        normalized = normalized.replaceAll("(?<![A-Za-z])1\\s*\\+\\s*k\\s*/\\s*1\\s*-\\s*k(?![A-Za-z])",
                "\\\\frac{1+k}{1-k}");
        normalized = normalized.replaceAll("(?<![A-Za-z])\\\\sqrt\\s+([A-Za-z0-9]+)", "\\\\sqrt{$1}");
        // JSON/model boundaries may encode the lost transport whitespace as a literal \n, \r, or \t before `rac`.
        normalized = normalized.replaceAll("\\\\[nrt]\\s*rac\\b", "\\\\frac");
        normalized = normalized.replaceAll("\\\\\\s+rac\\b", "\\\\frac");
        normalized = normalized.replaceAll("(?<![A-Za-z])\\s+rac\\b", "\\\\frac");
        // Keep the compact exponent form expected by the shared frontend renderer; both `^2` and `^{2}`
        // are mathematically valid, but the compact form avoids changing already verified output contracts.
        normalized = normalized.replaceAll("(?<![A-Za-z])([A-Za-z])\\s*\\^\\s*([0-9]+)", "$1^$2");
        // Restore the standard hyperbola form when OCR emits the characteristic `C x y m m- = >` sequence.
        normalized = normalized.replaceAll("C\\s*:?\\s*x\\s*y\\s*m\\s*m\\s*[−-]\\s*=\\s*>",
                "C: x^2/a^2-y^2/b^2=1 (a,b>0)");
        return normalized;
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
        return normalizeCjkTextInsideMath(normalizeGeometryRelations(value))
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
                .replace("±", "\\pm ")
                .replace("×", "\\times ")
                .replace("÷", "/");
    }

    /**
     * Keeps model-authored Chinese labels such as “平面” in the mathematical expression without asking XeLaTeX to
     * interpret them as control sequences.  This matters for geometry: the model often writes
     * {@code $$AC_1\\perp平面A_1BD.$$}, which otherwise makes a real PDF compile fail even though the formula is
     * understandable to a teacher.  Wrapping only CJK runs preserves the surrounding Latin/TeX expression and is
     * deterministic, so it does not spend another model call or alter the mathematical relation.
     */
    private static String normalizeCjkTextInsideMath(String value) {
        StringBuilder builder = new StringBuilder(value.length());
        StringBuilder mathSegment = new StringBuilder();
        boolean math = false;
        for (int index = 0; index < value.length(); index += 1) {
            if (value.startsWith("$$", index)) {
                builder.append(math ? wrapCjkTextRuns(mathSegment.toString()) : mathSegment);
                mathSegment.setLength(0);
                builder.append("$$");
                math = !math;
                index += 1;
                continue;
            }
            char character = value.charAt(index);
            if (character == '$') {
                builder.append(math ? wrapCjkTextRuns(mathSegment.toString()) : mathSegment);
                mathSegment.setLength(0);
                builder.append('$');
                math = !math;
                continue;
            }
            mathSegment.append(character);
        }
        builder.append(math ? wrapCjkTextRuns(mathSegment.toString()) : mathSegment);
        return builder.toString();
    }

    private static String wrapCjkTextRuns(String value) {
        Matcher matcher = CJK_TEXT_RUN.matcher(value);
        StringBuffer buffer = new StringBuffer();
        while (matcher.find()) {
            // The Java literal below emits one TeX backslash. Two backslashes would mean a line break followed by
            // literal `text`, which compiled successfully but visibly corrupted labels such as “结构识别”.
            matcher.appendReplacement(buffer, Matcher.quoteReplacement("\\text{" + matcher.group() + "}"));
        }
        matcher.appendTail(buffer);
        return buffer.toString();
    }

    /**
     * Moves geometry relation glyphs into TeX math mode before printable handout escaping.  CJK body fonts do not
     * consistently contain glyphs such as {@code ⊥}; leaving them as plain text caused visible square boxes in real
     * teacher PDFs.  The small state machine preserves an already-delimited formula instead of nesting dollar signs.
     */
    private static String normalizeGeometryRelations(String value) {
        StringBuilder builder = new StringBuilder();
        boolean math = false;
        for (int index = 0; index < value.length(); index += 1) {
            if (value.startsWith("$$", index)) {
                builder.append("$$");
                math = !math;
                index += 1;
                continue;
            }
            char character = value.charAt(index);
            if (character == '$') {
                builder.append(character);
                math = !math;
                continue;
            }
            String command = switch (character) {
                case '⊥' -> "\\perp";
                case '∥', '‖' -> "\\parallel";
                case '∠' -> "\\angle";
                default -> "";
            };
            if (command.isEmpty()) {
                builder.append(character);
            } else if (math) {
                builder.append(command).append(' ');
            } else {
                builder.append('$').append(command).append('$');
            }
        }
        return builder.toString();
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
        String withFormulas = wrapMatches(withCoordinates, BARE_FORMULA);
        return wrapMatches(withFormulas, BARE_FRACTION_COMMAND);
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

    /**
     * Converts simple slash fractions such as k/x or (a+b)/c to \frac{...}{...} while avoiding exponent ratios like x^2/a^2.
     */
    private static String normalizeSlashFractions(String value) {
        String normalized = replacePowerOverInteger(value);
        normalized = replaceSimpleFractions(normalized, SIMPLE_FRACTION_LEFT_HEAVY);
        normalized = replaceSimpleFractions(normalized, SIMPLE_FRACTION_RIGHT_HEAVY);
        normalized = replaceShortNumericFractions(normalized);
        Matcher matcher = FRACTION_POWER.matcher(normalized);
        StringBuffer buffer = new StringBuffer();
        while (matcher.find()) {
            matcher.appendReplacement(
                    buffer,
                    Matcher.quoteReplacement("\\left(\\frac{" + matcher.group(1) + "}{" + matcher.group(2) + "}\\right)^" + matcher.group(3)));
        }
        matcher.appendTail(buffer);
        return buffer.toString();
    }

    /** Preserves the numerator boundary in source text such as {@code x^2/16}. */
    private static String replacePowerOverInteger(String value) {
        Matcher matcher = POWER_OVER_INTEGER.matcher(value);
        StringBuffer buffer = new StringBuffer();
        while (matcher.find()) {
            matcher.appendReplacement(buffer, Matcher.quoteReplacement("\\frac{" + matcher.group(1) + "}{" + matcher.group(2) + "}"));
        }
        matcher.appendTail(buffer);
        return buffer.toString();
    }

    private static String replaceShortNumericFractions(String value) {
        Matcher matcher = SHORT_NUMERIC_FRACTION.matcher(value);
        StringBuffer buffer = new StringBuffer();
        while (matcher.find()) {
            String left = matcher.group(1);
            String right = matcher.group(2);
            matcher.appendReplacement(buffer, Matcher.quoteReplacement("\\frac{" + left + "}{" + right + "}"));
        }
        matcher.appendTail(buffer);
        return buffer.toString();
    }

    private static String replaceSimpleFractions(String value, Pattern pattern) {
        Matcher matcher = pattern.matcher(value);
        StringBuffer buffer = new StringBuffer();
        while (matcher.find()) {
            String left = stripMathWrappers(matcher.group(1));
            String right = stripMathWrappers(matcher.group(2));
            if (!looksLikeMathFraction(left, right)) {
                matcher.appendReplacement(buffer, Matcher.quoteReplacement(matcher.group()));
                continue;
            }
            matcher.appendReplacement(buffer, Matcher.quoteReplacement("\\frac{" + left + "}{" + right + "}"));
        }
        matcher.appendTail(buffer);
        return buffer.toString();
    }

    private static String cleanupFractionMathDelimiters(String value) {
        return value
                .replaceAll("\\\\frac\\{\\$([^$]+)\\$}", "\\\\frac{$1}")
                .replaceAll("}\\{\\$([^$]+)\\$}", "}{$1}")
                .replaceAll("\\{\\$(\\\\[A-Za-z]+)\\$\\s*([^}]*)}", "{$1 $2}")
                // Formula wrapping happens before command wrapping. Remove those intermediate delimiters before
                // the final pass, otherwise a radical could become \\sqrt{$a+b$} and render incorrectly.
                .replaceAll("\\\\sqrt\\{\\$([^$]+)\\$}", "\\\\sqrt{$1}");
    }

    private static boolean looksLikeMathFraction(String left, String right) {
        if (left.isBlank() || right.isBlank()) {
            return false;
        }
        if (isReservedPlainToken(left) || isReservedPlainToken(right)) {
            return false;
        }
        return isMathToken(left) && isMathToken(right);
    }

    private static boolean isMathToken(String value) {
        String token = value.strip();
        if (token.isBlank()) {
            return false;
        }
        if (token.matches("[-+]?\\d+(?:\\.\\d+)?")) {
            return true;
        }
        if (token.startsWith("\\") || token.contains("^") || token.contains("_")) {
            return true;
        }
        if (token.matches("[A-Za-z]")) {
            return true;
        }
        if (token.matches("[A-Za-z][A-Za-z0-9]?") && !ALL_UPPERCASE_LATIN.matcher(token).matches()) {
            return true;
        }
        return token.matches("[A-Za-z0-9]+[+\\-][A-Za-z0-9]+");
    }

    private static boolean isReservedPlainToken(String value) {
        String token = value.strip();
        return ALL_UPPERCASE_LATIN.matcher(token).matches()
                || "PDF".equals(token)
                || "OCR".equals(token)
                || "JSON".equals(token);
    }

    private static String stripMathWrappers(String value) {
        if (value == null) {
            return "";
        }
        return value.strip()
                .replaceAll("^\\((.+)\\)$", "$1")
                .replaceAll("^\\{(.+)\\}$", "$1")
                .strip();
    }
}
