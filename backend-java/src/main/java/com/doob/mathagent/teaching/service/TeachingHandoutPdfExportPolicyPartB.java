package com.doob.mathagent.teaching.service;

import com.doob.mathagent.teaching.vo.TeachingTaskResponse;
import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.PDPageContentStream.AppendMode;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import com.doob.mathagent.teaching.service.TeachingHandoutPdfExportService.LineType;
import com.doob.mathagent.teaching.service.TeachingHandoutPdfExportService.ReadableLine;
import com.doob.mathagent.teaching.service.TeachingHandoutPdfExportService.HandoutImage;
import com.doob.mathagent.teaching.service.TeachingHandoutPdfExportService.PdfStyle;
import com.doob.mathagent.teaching.service.TeachingHandoutPdfExportService.PdfWriter;
import static com.doob.mathagent.teaching.service.TeachingHandoutPdfExportService.*;

/**
 * TeachingHandoutPdfExportPolicyPartB isolates deterministic PDF export policy from process orchestration.
 * It contains no mutable request state; the facade remains responsible for renderer selection and cleanup.
 */
final class TeachingHandoutPdfExportPolicyPartB {
    private TeachingHandoutPdfExportPolicyPartB() {
        // Static policy component: construction would create state with no owner.
    }


    /** Repairs old task snapshots written as UTF-8 bytes decoded through a Latin-1 code page. */
    static String repairMojibake(String value) {
        if (value == null || value.isBlank()
                || !value.matches("(?s).*[ÃÂåæçèéêïðñã].*")) {
            return value == null ? "" : value;
        }
        try {
            String candidate = new String(value.getBytes(StandardCharsets.ISO_8859_1), StandardCharsets.UTF_8);
            long sourceNoise = mojibakeScore(value);
            long candidateNoise = mojibakeScore(candidate);
            long candidateHan = candidate.chars().filter(character -> character >= 0x4E00 && character <= 0x9FFF).count();
            return candidateHan > 0 && candidateNoise < sourceNoise ? candidate : value;
        } catch (RuntimeException ignored) {
            return value;
        }
    }


    static long mojibakeScore(String value) {
        return value == null ? 0 : value.chars()
                .filter(character -> character == 'Ã' || character == 'Â' || character == 'å'
                        || character == 'æ' || character == 'ç' || character == 'è'
                        || character == 'é' || character == 'ê' || character == 'ï'
                        || character == 'ð' || character == 'ñ' || character == 'ã')
                .count();
    }


    /**
     * Wraps only exponent/subscript atoms found outside existing dollar-delimited math. This keeps ordinary prose
     * untouched while guaranteeing that a persisted x^2 or P_1 renders as a real superscript/subscript.
     */
    static String normalizeBareMathFragments(String value) {
        String source = safeText(value);
        StringBuilder result = new StringBuilder(source.length() + 16);
        boolean inMath = false;
        int segmentStart = 0;
        for (int index = 0; index < source.length(); index += 1) {
            if (source.charAt(index) != '$') {
                continue;
            }
            // Display math uses a two-character delimiter. Treat it atomically; otherwise the two '$' characters
            // would toggle the inline state twice and the text inside would be incorrectly rewritten as prose.
            if (index + 1 < source.length() && source.charAt(index + 1) == '$') {
                if (!inMath) {
                    result.append(rewriteBareMathAtoms(source.substring(segmentStart, index)));
                    inMath = true;
                } else {
                    result.append(source, segmentStart, index);
                    inMath = false;
                }
                result.append("$$");
                index += 1;
                segmentStart = index + 1;
                continue;
            }
            if (!inMath) {
                result.append(rewriteBareMathAtoms(source.substring(segmentStart, index)));
                inMath = true;
            } else {
                result.append(source, segmentStart, index);
                inMath = false;
            }
            result.append('$');
            segmentStart = index + 1;
        }
        String tail = source.substring(segmentStart);
        result.append(inMath ? tail : rewriteBareMathAtoms(tail));
        return result.toString();
    }


    static String rewriteBareMathAtoms(String value) {
        Matcher matcher = BARE_MATH_ATOM.matcher(value);
        StringBuffer rewritten = new StringBuffer();
        while (matcher.find()) {
            matcher.appendReplacement(rewritten, Matcher.quoteReplacement("$" + matcher.group(1) + "$"));
        }
        matcher.appendTail(rewritten);
        return rewritten.toString();
    }


    static String removeVisibleWorkspaceLabels(String value) {
        String withoutReferences = VISIBLE_WORKSPACE_REFERENCE.matcher(value).replaceAll("独立完成");
        return VISIBLE_WORKSPACE_LABEL.matcher(withoutReferences).replaceAll("");
    }


    static boolean isBlankWorkspaceHeading(String value) {
        String compact = safeText(value)
                .replaceAll("[_＿\\s:：，。,.;；、-]+", "")
                .strip();
        return List.of("作答", "作答区", "课堂作答区", "我的解答", "解答", "推导区", "空白区",
                "留白区", "留白", "手写区", "教师手写区", "板书留白", "板书区", "教师板书区")
                .contains(compact);
    }


    static String removeEmptyTitledBlocks(String latex) {
        if (latex == null || latex.isBlank()) {
            return "";
        }
        String[] lines = latex.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        return renderNonEmptyTitleRange(lines, 0, lines.length).strip();
    }


    static String renderNonEmptyTitleRange(String[] lines, int start, int end) {
        StringBuilder builder = new StringBuilder();
        int index = start;
        while (index < end) {
            Matcher heading = LATEX_HEADING_LINE.matcher(lines[index].strip());
            if (!heading.matches()) {
                if (!isBlankWorkspaceLabelLine(lines[index])) {
                    builder.append(lines[index]).append('\n');
                }
                index += 1;
                continue;
            }
            int level = latexHeadingLevel(heading.group(1));
            int next = index + 1;
            while (next < end) {
                Matcher nextHeading = LATEX_HEADING_LINE.matcher(lines[next].strip());
                if (nextHeading.matches() && latexHeadingLevel(nextHeading.group(1)) <= level) {
                    break;
                }
                next += 1;
            }
            String body = renderNonEmptyTitleRange(lines, index + 1, next).strip();
            if (hasRealLatexContent(body)
                    || "来源索引".equals(cleanText(heading.group(2)))) {
                // Workspace-only headings stay hidden, while a real prompt beneath them remains printable.
                if (isBlankWorkspaceHeading(heading.group(2))) {
                    builder.append(body).append("\n\n");
                } else {
                    builder.append(lines[index].strip()).append('\n').append(body).append("\n\n");
                }
            }
            index = next;
        }
        return builder.toString();
    }


    static int latexHeadingLevel(String command) {
        String normalized = command == null ? "" : command.replace("*", "");
        return switch (normalized) {
            case "section" -> 1;
            case "subsection" -> 2;
            case "subsubsection", "paragraph" -> 3;
            default -> 4;
        };
    }


    static boolean hasRealLatexContent(String body) {
        if (body == null || body.isBlank()) {
            return false;
        }
        for (String rawLine : body.replace("\r\n", "\n").replace('\r', '\n').split("\n")) {
            if (!isBlankOnlyLatexLine(rawLine)) {
                return true;

            }
        }
        return false;
    }


    static boolean isBlankWorkspaceLabelLine(String line) {
        String text = line == null ? "" : line.strip();
        if (text.isBlank()) {
            return false;
        }
        String compact = text
                .replaceAll("[_＿\\s:：，。,.;；、-]+", "")
                .strip();
        return List.of("作答", "留白区", "留白", "手写区", "教师手写区", "板书留白", "板书区", "教师板书区").contains(compact);
    }


    static boolean isBlankOnlyLatexLine(String line) {
        String text = line == null ? "" : line.strip();
        if (text.isBlank()) {
            return true;
        }
        if (text.matches("^\\\\vspace\\{[0-9.]+em}\\s*$")
                || text.matches("^\\\\(?:smallskip|medskip|bigskip|par)\\s*$")
                || text.matches("^\\\\underline\\{\\\\hspace\\{[0-9.]+em}}\\s*$")
                || text.matches("^\\\\(?:begin|end)\\{(?:itemize|enumerate|center)}\\s*$")) {
            return true;
        }
        String compact = text
                .replaceAll("\\\\vspace\\{[^}]+}", "")
                .replaceAll("\\\\underline\\{\\\\hspace\\{[^}]+}}", "")
                .replaceAll("\\\\hspace\\{[^}]+}", "")
                .replaceAll("\\\\par", "")
                .replaceAll("[_＿\\s:：，。,.;；、-]+", "")
                .strip();

        return compact.isBlank()
                || isBlankWorkspaceLabelLine(text)
                || List.of("作答", "作答区", "课堂作答区", "我的解答", "解答", "推导区", "订正", "订正记录",
                        "错因", "错因记录", "订正与错因", "空白区", "留白区", "留白", "手写区",
                        "教师手写区", "板书留白", "板书区", "教师板书区").contains(compact);
    }


    static String renderLatexBody(String sanitizedBody) {
        StringBuilder builder = new StringBuilder();
        // Normalize before splitting: persisted JSON may contain one or two transport slashes before "n". If this
        // happens after the split, XeLaTeX receives an undefined \n command instead of a document line boundary.
        String normalizedBody = normalizeLegacyLatexForExport(safeText(sanitizedBody));
        String[] lines = normalizedBody.replace("\r\n", "\n").replace('\r', '\n').split("\n");
        for (int index = 0; index < lines.length; index += 1) {
            Optional<HandoutImage> image = parseImageMarker(lines[index].strip());
            if (image.isPresent()) {
                List<HandoutImage> block = new ArrayList<>();
                while (index < lines.length) {
                    Optional<HandoutImage> candidate = parseImageMarker(lines[index].strip());
                    if (candidate.isEmpty()) {
                        break;
                    }
                    block.add(candidate.get());
                    index += 1;
                }
                index -= 1;
                builder.append(renderLatexImageBlock(block));
                continue;
            }
            builder.append(lines[index]).append('\n');
        }
        return builder.toString().strip();
    }


    static String renderLatexImageBlock(List<HandoutImage> images) {
        if (images.isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        for (int index = 0; index < images.size(); index += 2) {
            List<HandoutImage> row = images.subList(index, Math.min(index + 2, images.size()));
            builder.append("\\begin{center}\n");
            if (row.size() == 1) {
                builder.append(renderLatexImageCell(row.get(0), "0.78\\linewidth", "0.32\\textheight"));
            } else {
                builder.append(renderLatexImageRowCell(row.get(0)));
                builder.append("\\hfill\n");
                builder.append(renderLatexImageRowCell(row.get(1)));
            }
            builder.append("\\end{center}\n");
            builder.append("\\vspace{0.35em}\n");
        }
        return builder.toString();
    }


    static String renderLatexImageRowCell(HandoutImage image) {
        return """
                \\begin{minipage}[t]{0.48\\linewidth}
                \\centering
                %s
                \\end{minipage}
                """.formatted(renderLatexImageCell(image, "\\linewidth", "0.24\\textheight"));
    }


    static String renderLatexImageCell(HandoutImage image, String width, String maxHeight) {
        Optional<Path> localPath = existingLocalImagePath(image.path());
        String caption = INLINE_FIGURE_TRANSPORT_ALT.equals(safeText(image.alt())) ? "" : safeText(image.alt());
        StringBuilder builder = new StringBuilder();
        if (localPath.isEmpty()) {
            return "";
        }
        builder.append("\\includegraphics[width=")
                .append(width)
                .append(",height=")
                .append(maxHeight)
                .append("]{")
                .append(latexImagePath(localPath.get()))
                .append("}\n");
        if (!caption.isBlank()) {
            builder.append("{\\small ").append(latexText(caption)).append("\\par}\n");
        }
        return builder.toString();
    }


    static String latexImagePath(Path path) {
        return "\\detokenize{" + path.toAbsolutePath().normalize().toString().replace('\\', '/') + "}";
    }


    static String escapeLooseTextSpecials(String value) {
        // This method runs immediately before XeLaTeX.  The browser-oriented FormulaMarkupSanitizer may wrap bare
        // fragments, but re-wrapping an already authored LaTeX body corrupts structural $...$ ranges into $$$.
        // Export therefore recovers only known legacy transport escapes and then preserves every valid delimiter.
        value = normalizeLegacyLatexForExport(safeText(value));
        StringBuilder builder = new StringBuilder();
        boolean math = false;
        for (int index = 0; index < value.length(); index += 1) {
            if (value.startsWith("$$", index)) {
                math = !math;
                builder.append("$$");
                index += 1;
                continue;
            }
            char character = value.charAt(index);
            char previous = index > 0 ? value.charAt(index - 1) : '\0';
            if (character == '$') {
                math = !math;
                builder.append(character);
                continue;
            }
            if (!math && previous != '\\') {
                if (character == '_') {
                    builder.append("\\_");
                    continue;
                }
                if (character == '&') {
                    builder.append("\\&");
                    continue;
                }
                if (character == '#') {
                    builder.append("\\#");
                    continue;
                }
                if (character == '%') {
                    builder.append("\\%");
                    continue;
                }
                if (character == '^') {
                    builder.append("\\textasciicircum{}");
                    continue;
                }
                if (character == '~') {
                    builder.append("\\textasciitilde{}");
                    continue;
                }
            }
            builder.append(character);
        }
        return builder.toString();
    }


    /**
     * Recovers old persisted handout text without applying browser markup rules to a TeX document.
     *
     * <p>Legacy snapshots can contain commands serialized as {@code \textbackslash\{\}frac} and a quadratic
     * expression fragmented as {@code x\textasciicircum{}$\frac{2}{16}$}.  Both are transport defects, not
     * lesson content.  The repair is deliberately narrow and idempotent: valid {@code $...$} formulas pass through
     * unchanged, while the recovered forms are made into one well-scoped inline expression.</p>
     */
    static String normalizeLegacyLatexForExport(String value) {
        // The body renderer is reached by preview, download, and PDF export. Reuse the facade's control-word-aware
        // transport repair here so this later legacy pass cannot turn \node or \neq into a newline plus prose.
        String normalized = TeachingHandoutPdfExportService.restoreTransportNewlines(value)
                .replace("\\textbackslash\\{\\}", "\\")
                .replace("\\textbackslash{}", "\\")
                .replace("\\textbackslash", "\\")
                // A transport layer escaped the braces of LaTeX command arguments along with the command slash.
                // These forms cannot denote literal set braces in a source stem, so recover their command argument.
                .replace("\\vec\\{", "\\vec{")
                .replace("\\overrightarrow\\{", "\\overrightarrow{")
                .replace("\\frac\\{", "\\frac{")
                .replace("\\sqrt\\{", "\\sqrt{")
                .replace("\\triangle\\{", "\\triangle{")
                // The same legacy writer escaped every argument brace independently (for example
                // \frac\{1\}\{2\}); after command recovery both braces are structural, not printable sets.
                // 现役 Writer 的可伸缩集合定界符 \left\{ … \right\} 是合法输出，不能被这次全局反转义破坏，
                // 否则 XeLaTeX 以 "Missing delimiter" 终止整份讲义；先用占位符屏蔽再还原。
                .replace("\\left\\{", "\u0001LEFTSET\u0001")
                .replace("\\right\\}", "\u0001RIGHTSET\u0001")
                .replace("\\{", "{")
                .replace("\\}", "}")
                .replace("\u0001LEFTSET\u0001", "\\left\\{")
                .replace("\u0001RIGHTSET\u0001", "\\right\\}");
        Matcher quadratic = SPLIT_QUADRATIC_FRACTION.matcher(normalized);
        StringBuffer rebuilt = new StringBuffer();
        while (quadratic.find()) {
            String replacement = "$\\frac{%s^{%s}}{%s}%s\\frac{%s^{%s}}{%s}=%s$".formatted(
                    quadratic.group(1), quadratic.group(2), quadratic.group(3), quadratic.group(4),
                    quadratic.group(5), quadratic.group(6), quadratic.group(7), quadratic.group(8));
            quadratic.appendReplacement(rebuilt, Matcher.quoteReplacement(replacement));
        }
        quadratic.appendTail(rebuilt);
        Matcher exponent = SPLIT_EXPONENT_MATH.matcher(rebuilt.toString());
        StringBuffer exponentRebuilt = new StringBuffer();
        while (exponent.find()) {
            exponent.appendReplacement(exponentRebuilt,
                    Matcher.quoteReplacement("$" + exponent.group(1) + "^" + exponent.group(2) + "$"));
        }
        exponent.appendTail(exponentRebuilt);
        return BARE_TIMES.matcher(exponentRebuilt.toString()).replaceAll("\\$$1\\\\times $2\\$");
    }


    /**
     * Escapes free-form LaTeX while protecting opaque image markers from text escaping.
     *
     * <p>The marker contains Base64 payload and square brackets.  It must remain a complete line until
     * {@link #renderLatexBody(String)} expands it to a validated {@code \includegraphics} command.
     * Replacing markers with alphanumeric sentinels avoids accidental escaping of their payload.</p>
     */
    static String escapeLooseTextSpecialsPreservingImageMarkers(String value) {
        String source = safeText(value);
        List<String> markers = new ArrayList<>();
        Matcher matcher = IMAGE_MARKER.matcher(source);
        StringBuffer masked = new StringBuffer();
        int markerIndex = 0;
        while (matcher.find()) {
            String marker = matcher.group();
            markers.add(marker);
            matcher.appendReplacement(masked, Matcher.quoteReplacement("HANDOUTIMAGETOKEN" + markerIndex));

            markerIndex += 1;
        }
        matcher.appendTail(masked);
        String escaped = escapeLooseTextSpecials(masked.toString());
        for (int index = 0; index < markers.size(); index += 1) {
            escaped = escaped.replace("HANDOUTIMAGETOKEN" + index, markers.get(index));
        }
        return escaped;
    }


    static String latexText(String value) {
        return normalizeLegacyLatexText(safeText(value))
                .replace("\\", "\\textbackslash{}")
                .replace("&", "\\&")
                .replace("%", "\\%")
                .replace("#", "\\#")
                .replace("_", "\\_")
                .replace("{", "\\{")
                .replace("}", "\\}")
                .replace("^", "\\textasciicircum{}")
                .replace("~", "\\textasciitilde{}");
    }


    static String normalizeLegacyLatexText(String value) {
        return safeText(value)
                .replace("\\textasciicircum{}", "^")
                .replace("\\textasciitilde{}", "~")
                .replace("\\textbackslash{}frac", "\\frac")
                .replace("\\textbackslash{}sqrt", "\\sqrt");
    }


    static String hex(Color color) {

        return "%02X%02X%02X".formatted(color.getRed(), color.getGreen(), color.getBlue());
    }


    static String tail(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value == null ? "" : value;
        }
        return value.substring(value.length() - maxLength);
    }


    static int countPages(byte[] pdfBytes) {
        try (PDDocument document = org.apache.pdfbox.Loader.loadPDF(pdfBytes)) {
            return document.getNumberOfPages();
        } catch (IOException exception) {
            return 0;
        }
    }


    static void deleteRecursively(Path root) {
        try (java.util.stream.Stream<Path> stream = Files.walk(root)) {
            stream.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                }
            });
        } catch (IOException ignored) {
        }
    }


    /**
     * Loads a local Unicode font so Chinese handouts are not converted to question marks.
     */
    static PDFont loadReadableFont(PDDocument document) throws IOException {
        String configuredValue = configuredFontValue();
        if (!configuredValue.isBlank()) {
            Path configuredFont = Path.of(configuredValue);
            if (!Files.isRegularFile(configuredFont)) {
                throw new IOException("配置的中文 PDF 字体不存在或不可读：" + configuredFont);
            }
            try {
                return PDType0Font.load(document, configuredFont.toFile());
            } catch (IOException exception) {
                // 不能退回 Helvetica：它不包含中文字形，会把整份讲义静默导出为问号。
                throw new IOException("配置的中文 PDF 字体加载失败：" + configuredFont, exception);
            }
        }
        for (Path path : commonFontPaths()) {
            if (Files.isRegularFile(path)) {
                try {
                    return PDType0Font.load(document, path.toFile());
                } catch (IOException ignored) {
                    // Try the next local font candidate.
                }
            }
        }
        // PDFBox 的标准 14 字体没有中文字形。宁可明确拒绝导出，也不能生成看似成功的乱码文件。
        throw new IOException("未找到可用的本地中文 PDF 字体；请配置 MATH_AGENT_PDF_FONT_PATH");
    }


    /**
     * Returns an operator-provided font path when available.
     */
    static String configuredFontValue() {
        String value = System.getenv("MATH_AGENT_PDF_FONT_PATH");
        if (value == null || value.isBlank()) {
            value = System.getProperty("math.agent.pdf.font.path", "");
        }
        return value == null ? "" : value.strip();
    }


    /**
     * Known font files available on Windows and common Linux developer machines.
     */
    static List<Path> commonFontPaths() {
        return List.of(
                Path.of("C:/Windows/Fonts/simhei.ttf"),
                Path.of("C:/Windows/Fonts/simkai.ttf"),
                Path.of("C:/Windows/Fonts/simfang.ttf"),
                Path.of("C:/Windows/Fonts/msyh.ttf"),
                Path.of("/usr/share/fonts/truetype/noto/NotoSansCJK-Regular.ttc"),
                Path.of("/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc"),
                Path.of("/usr/share/fonts/truetype/wqy/wqy-microhei.ttc"));
    }


    /**
     * Converts a small LaTeX subset into human-readable text lines.
     */
    static List<ReadableLine> readableLines(String latex) {
        List<ReadableLine> lines = new ArrayList<>();
        boolean inEvidenceSection = false;
        boolean skippingTextbookBody = false;
        boolean skippingLegacyMetadataSection = false;
        int evidenceLineCount = 0;
        String[] sourceLines = safeText(latex).replace("\r\n", "\n").replace('\r', '\n').split("\n");
        for (int index = 0; index < sourceLines.length; index += 1) {
            String line = sourceLines[index].strip();
            if (line.isBlank()) {
                if (!skippingTextbookBody && !skippingLegacyMetadataSection) {
                    addBlank(lines);
                }
                continue;
            }
            if (isDiagnosticLine(line)) {
                continue;
            }
            Optional<HandoutImage> markerImage = parseImageMarker(line);
            if (markerImage.isPresent()) {
                List<HandoutImage> block = new ArrayList<>();
                while (index < sourceLines.length) {
                    Optional<HandoutImage> candidate = parseImageMarker(sourceLines[index].strip());
                    if (candidate.isEmpty()) {
                        break;
                    }
                    block.add(candidate.get());
                    index += 1;
                }
                index -= 1;
                lines.add(new ReadableLine(LineType.IMAGE, "", List.copyOf(block)));
                continue;
            }
            if (line.startsWith("%")
                    || line.startsWith("\\begin{document}")
                    || line.startsWith("\\end{document}")
                    || line.startsWith("\\begin{itemize}")
                    || line.startsWith("\\end{itemize}")
                    || line.startsWith("\\begin{enumerate}")
                    || line.startsWith("\\end{enumerate}")) {
                continue;
            }
            if (isLatexDocumentScaffoldLine(line)) {
                continue;
            }
            if (isInternalLayoutInstruction(line)) {
                continue;
            }
            if ("\\newpage".equals(line) || "\\clearpage".equals(line)) {
                lines.add(new ReadableLine(LineType.PAGE_BREAK, ""));
                continue;
            }
            Matcher vspace = VSPACE_COMMAND.matcher(line);
            if (vspace.matches()) {
                int blankRows = Math.max(2, Math.min(12, Math.round(Float.parseFloat(vspace.group(1)) / 1.6f)));
                for (int row = 0; row < blankRows; row += 1) {
                    lines.add(new ReadableLine(LineType.WRITING_SPACE, ""));
                }
                continue;
            }
            Matcher section = SECTION_COMMAND.matcher(line);
            if (section.matches()) {
                String heading = cleanText(section.group(1));
                if (isVersionOnlyHeading(heading)) {
                    skippingLegacyMetadataSection = false;
                    inEvidenceSection = false;
                    skippingTextbookBody = false;
                    continue;
                }
                skippingLegacyMetadataSection = isLegacyMetadataHeading(heading);
                if (skippingLegacyMetadataSection) {
                    continue;
                }
                inEvidenceSection = isEvidenceHeading(heading);
                if (inEvidenceSection) {
                    heading = "来源索引";
                    evidenceLineCount = 0;
                }
                skippingTextbookBody = false;
                lines.add(new ReadableLine(LineType.HEADING, heading));
                continue;
            }
            Matcher markdownHeading = MARKDOWN_HEADING.matcher(line);
            if (markdownHeading.matches()) {
                String heading = cleanText(markdownHeading.group(1));
                if (inEvidenceSection && isTextbookBodyHeading(heading)) {
                    skippingTextbookBody = true;
                    continue;
                }
                if (skippingTextbookBody) {
                    skippingTextbookBody = false;
                }
                if (!heading.isBlank() && !heading.matches("p\\d+")) {
                    lines.add(new ReadableLine(LineType.HEADING, heading));
                }
                continue;
            }
            if (skippingTextbookBody) {
                continue;
            }
            if (skippingLegacyMetadataSection) {
                continue;
            }
            List<HandoutImage> inlineImages = extractMarkdownImages(line);
            if (!inlineImages.isEmpty()) {
                String textOnly = cleanText(MARKDOWN_IMAGE.matcher(line).replaceAll(""));
                if (!textOnly.isBlank()) {
                    lines.add(new ReadableLine(inEvidenceSection ? LineType.BULLET : LineType.PARAGRAPH, textOnly));
                }
                lines.add(new ReadableLine(LineType.IMAGE, "", List.copyOf(inlineImages)));
                continue;
            }
            if (line.startsWith("\\item")) {
                lines.add(new ReadableLine(LineType.BULLET, cleanText(line.substring("\\item".length()))));
                continue;
            }

            if (line.startsWith("- ") || line.startsWith("* ")) {
                String bullet = cleanText(line.substring(2));
                if (!bullet.isBlank()) {
                    lines.add(new ReadableLine(LineType.BULLET, bullet));
                }
                continue;
            }
            String cleaned = cleanText(line);
            if (inEvidenceSection) {
                cleaned = compactEvidenceReference(cleaned);
                if (cleaned.isBlank() || evidenceLineCount >= 4) {
                    continue;
                }
                evidenceLineCount += 1;
            }
            if (!cleaned.isBlank()) {
                lines.add(new ReadableLine(inEvidenceSection ? LineType.BULLET : LineType.PARAGRAPH, cleaned));
            }
        }
        return lines.isEmpty() ? List.of(new ReadableLine(LineType.PARAGRAPH, "暂无可展示讲义内容。")) : compactBlanks(lines);
    }


    static boolean isDiagnosticLine(String line) {
        String normalized = line.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").strip();
        // Model output can contain protocol/debug fragments in the middle of an otherwise valid paragraph. Drop the
        // whole line before either XeLaTeX or the PDFBox fallback sees it, so previews never expose internal prompts.
        if (normalized.contains("内部提示词") || normalized.contains("内部提示") || normalized.contains("系统提示")
                || normalized.contains("提示词") || normalized.contains("方法标题")
                || normalized.contains("策略标题") || normalized.contains("{{")
                || normalized.contains("ocr原文")
                || normalized.contains("ocr 原文")
                || normalized.contains("题目入口") || normalized.contains("讲评入口")
                || normalized.contains("题型入口") || normalized.contains("知识入口")
                || normalized.contains("审题提醒")
                || normalized.matches("^}}$")
                || normalized.contains("synthetic-natural-math-benchmark")
                || normalized.contains("benchmark-high-school-math")

                || normalized.contains("/output/benchmarks/")
                || normalized.contains("\\output\\benchmarks\\")
                || normalized.contains("benchmark-math-resources")) {
            return true;
        }
        if (normalized.contains("tokens=") || normalized.contains(" tokens")) {
            return normalized.startsWith("模型")
                    || normalized.startsWith("model")
                    || normalized.contains("gpt-")
                    || normalized.contains("qwen")
                    || normalized.contains("deepseek")
                    || normalized.contains("openai/")
                    || normalized.contains("dashscope/");
        }
        return normalized.startsWith("ai model")
                || normalized.startsWith("retry ")
                || normalized.startsWith("parse ")
                || normalized.startsWith("model_call_")
                || normalized.startsWith("json_parse_");
    }


    static boolean isEvidenceHeading(String heading) {
        // “图片证据” is often part of a real mathematical topic (for example a map-colouring
        // question), not a source appendix.  Only the small, explicit source-heading vocabulary
        // gets compacted into the protected evidence section.
        String text = safeText(heading).replaceAll("\\s+", "");
        return text.equals("来源索引")
                || text.equals("资料来源")
                || text.equals("教材与资料")
                // The workflow emits this explicit heading for the retrieved source bundle. Treat it as the same
                // protected evidence appendix: raw OCR is searchable and inspectable in the evidence panel, but it
                // must never be mistaken for student-facing handout prose.
                || text.equals("教材与资料证据")
                || text.equals("资料证据")
                || text.equals("证据来源");
    }


    static boolean isLegacyMetadataHeading(String heading) {
        String text = safeText(heading).replaceAll("\\s+", "");
        return text.contains("讲义模板") || text.contains("模板与版式");
    }


    static boolean isForbiddenWorkflowHeading(String heading) {
        String text = safeText(heading).replaceAll("\\s+", "");
        return text.equals("题目入口")
                || text.equals("讲评入口")
                || text.equals("题目/任务")
                || text.equals("题型入口")
                || text.equals("知识入口")
                || text.equals("审题提醒")
                || text.equals("本讲题干");
    }


    static boolean isVersionOnlyHeading(String heading) {
        String text = safeText(heading).replaceAll("\\s+", "");
        return "教师版".equals(text) || "学生版".equals(text);
    }


    static boolean isTextbookBodyHeading(String heading) {
        String text = safeText(heading).replaceAll("\\s+", "");
        return "正文".equals(text)
                || "原文".equals(text)
                || "OCR正文".equalsIgnoreCase(text)
                || "教材正文".equals(text);
    }


    static boolean isInternalLayoutInstruction(String line) {
        String text = safeText(line).replaceAll("\\s+", "");
        return text.contains("PDF版式要求")
                || text.contains("PDF排版说明")
                || text.contains("PDF排版")
                || text.contains("页眉展示主题和版本")
                || text.contains("页脚展示页码")
                || text.contains("教师版使用讲评色")
                || text.contains("学生版使用练习色")
                || text.contains("页边距")
                || text.contains("虚线折叠")
                || text.contains("版式由系统渲染负责")
                || text.contains("正文不要写页眉")
                || text.contains("不要写页眉页脚")
                || text.contains("渲染规则");
    }


    /** Removes model-emitted template/version metadata; the exporter owns the canonical version header. */
    static boolean isTemplateMetadataLine(String line) {
        String text = safeText(line).replaceAll("\\s+", "");
        return text.startsWith("模板：")
                || text.startsWith("模板:")
                || text.startsWith("版本：")
                || text.startsWith("版本:");
    }


    static boolean isUnreadablePlaceholderLine(String line) {
        String text = safeText(line).replaceAll("\\s+", "");
        if (text.contains("????") || text.contains("？？？？")) {
            return true;
        }
        long replacementChars = text.codePoints().filter(ch -> ch == 0xFFFD).count();
        if (replacementChars >= 2) {
            return true;
        }
        long questionMarks = text.chars().filter(ch -> ch == '?').count();
        return questionMarks >= 6 && questionMarks >= Math.max(6, text.length() / 2);
    }


    static boolean isLatexDocumentScaffoldLine(String line) {
        String text = safeText(line).replaceAll("\\s+", "");
        String lower = text.toLowerCase(Locale.ROOT);
        return lower.startsWith("\\documentclass")
                || lower.startsWith("\\usepackage")
                || lower.startsWith("\\iffontexiststf")
                || lower.startsWith("\\setcjkmainfont")
                || lower.startsWith("\\setmainfont")
                || lower.startsWith("\\setlength")
                || lower.startsWith("\\setlist")
                || lower.startsWith("\\definecolor")
                || lower.startsWith("\\pagestyle")
                || lower.startsWith("\\fancyhf")
                || lower.startsWith("\\lhead")
                || lower.startsWith("\\rhead")
                || lower.startsWith("\\lfoot")
                || lower.startsWith("\\rfoot")
                || lower.startsWith("\\renewcommand")
                || lower.startsWith("\\titleformat")
                || lower.startsWith("\\titlespacing")
                || lower.startsWith("\\begin{document}")
                || lower.startsWith("\\end{document}")
                || lower.startsWith("\\begin{center}")
                || lower.startsWith("\\end{center}")
                || lower.startsWith("\\begin{titlepage}")
                || lower.startsWith("\\end{titlepage}")
                || lower.startsWith("\\color{");
    }


    static List<HandoutImage> extractMarkdownImages(String line) {
        List<HandoutImage> images = new ArrayList<>();
        Matcher matcher = MARKDOWN_IMAGE.matcher(safeText(line));
        while (matcher.find()) {
            String alt = cleanText(matcher.group(1));
            String path = normalizeImageReference(matcher.group(2));
            if (!path.isBlank()) {
                images.add(new HandoutImage(alt, path));
            }
        }
        return images;
    }


    static String normalizeImageReference(String rawPath) {
        String candidate = safeText(rawPath);
        if (candidate.startsWith("<") && candidate.endsWith(">") && candidate.length() > 2) {
            candidate = candidate.substring(1, candidate.length() - 1).strip();
        }
        if (candidate.startsWith("http://") || candidate.startsWith("https://")) {
            return candidate;
        }
        try {
            Path path = Path.of(candidate);
            if (!path.isAbsolute()) {
                path = Path.of("").toAbsolutePath().resolve(path).normalize();
            }
            return path.toString();
        } catch (InvalidPathException exception) {
            return candidate;
        }
    }


    static String toImageMarker(HandoutImage image) {
        return "[[HANDOUTIMAGE:"
                + Base64.getEncoder().withoutPadding().encodeToString(safeText(image.alt()).getBytes(StandardCharsets.UTF_8))
                + ":"
                + Base64.getEncoder().withoutPadding().encodeToString(safeText(image.path()).getBytes(StandardCharsets.UTF_8))
                + "]]";
    }


    static Optional<HandoutImage> parseImageMarker(String line) {
        Matcher matcher = IMAGE_MARKER.matcher(safeText(line));
        if (!matcher.matches()) {
            return Optional.empty();
        }
        try {
            byte[] altBytes;
            byte[] pathBytes;
            try {
                altBytes = Base64.getDecoder().decode(matcher.group(1));
                pathBytes = Base64.getDecoder().decode(matcher.group(2));
            } catch (IllegalArgumentException standardDecoderFailure) {
                altBytes = Base64.getUrlDecoder().decode(matcher.group(1));
                pathBytes = Base64.getUrlDecoder().decode(matcher.group(2));
            }
            return Optional.of(new HandoutImage(
                    new String(altBytes, StandardCharsets.UTF_8),
                    new String(pathBytes, StandardCharsets.UTF_8)));
        } catch (IllegalArgumentException exception) {
            return Optional.empty();
        }
    }


    static Optional<Path> existingLocalImagePath(String reference) {
        String text = safeText(reference);
        if (text.isBlank() || text.startsWith("http://") || text.startsWith("https://")) {
            return Optional.empty();
        }
        try {

            Path path = Path.of(text);
            return Files.isRegularFile(path) ? Optional.of(path) : Optional.empty();
        } catch (InvalidPathException exception) {
            return Optional.empty();
        }
    }


    static boolean isMarkdownImageOnlyLine(String line) {
        if (!MARKDOWN_IMAGE.matcher(line).find()) {
            return false;
        }
        String withoutImage = MARKDOWN_IMAGE.matcher(line).replaceAll("")
                .replace("-", "")
                .replace("*", "")
                .replace("：", "")
                .replace(":", "")
                .strip();
        return withoutImage.isBlank() || withoutImage.equals("页图") || withoutImage.equals("图片");
    }


    static String compactEvidenceReference(String value) {
        String text = TeachingEvidenceSnippetSanitizer.sanitizeCompact(cleanText(value))
                .replaceAll("\\s+", " ")
                .strip();
        text = compactLegacyPdfEvidenceLine(text);
        if (text.isBlank()
                || "已命中资料片段。".equals(text)
                || text.equals("正文")
                || text.equals("原文")
                || text.matches("(?i)^#?\\s*p\\d+.*")

                || text.contains("页图")
                || text.contains("OCR")
                || text.contains("[[HANDOUTIMAGE:")
                || text.contains("![")
                || text.contains("## 正文")) {
            return "";
        }
        text = text
                .replace("PUBLIC_TEXTBOOK", "公开教材")
                .replace("QUESTION_BANK", "题库")
                .replace("TEACHER_PRIVATE", "教师资料");
        if (text.length() > 88) {
            text = text.substring(0, 88).strip() + "...";
        }
        return text;
    }


    /**
     * Old saved tasks sometimes stored the whole OCR snippet after a source/page reference.
     * Exported handouts should cite the source, not replay noisy OCR text or broken glyphs.
     */
    static String compactLegacyPdfEvidenceLine(String value) {
        String text = safeText(value).strip();
        if (text.isBlank()) {
            return "";
        }
        Matcher matcher = Pattern.compile("(?i)(.+?PDF\\$?\\s*\\d+)(?:[:：].*)?$").matcher(text);
        if (matcher.matches()) {
            return matcher.group(1)
                    .replace("$", "")
                    .replaceAll("\\s+", " ")
                    .strip();
        }
        matcher = Pattern.compile("(.+?)(?:PDF页码|PDF\\s*页码)[:：]?\\s*(\\d+).*").matcher(text);
        if (matcher.matches()) {
            return (matcher.group(1).strip() + " PDF " + matcher.group(2)).replaceAll("\\s+", " ").strip();
        }
        return text;
    }


    /**
     * Removes repeated blank lines.
     */
    static List<ReadableLine> compactBlanks(List<ReadableLine> lines) {
        List<ReadableLine> compact = new ArrayList<>();
        for (ReadableLine line : lines) {
            if (line.type() == LineType.BLANK && (compact.isEmpty() || compact.get(compact.size() - 1).type() == LineType.BLANK)) {
                continue;
            }
            compact.add(line);
        }
        return compact;
    }


    /**
     * Adds one paragraph break unless the previous line is already blank.
     */
    static void addBlank(List<ReadableLine> lines) {
        if (!lines.isEmpty() && lines.get(lines.size() - 1).type() != LineType.BLANK) {
            lines.add(new ReadableLine(LineType.BLANK, ""));
        }
    }


    /**
     * Produces readable text from common LaTeX commands without exposing raw source syntax.
     */
    static String cleanText(String value) {
        String cleaned = safeText(value);
        // \left begins with the same prefix as \le. Remove scalable delimiters first so it cannot become “≤ft”.
        String latexSlash = String.valueOf((char) 92);
        cleaned = cleaned.replace(latexSlash + "left", "").replace(latexSlash + "right", "");
        cleaned = cleaned
                .replace("AI教师讲解草稿", "教师讲解稿")
                .replace("AI 讲义草稿", "讲义内容生成")
                .replace("AI生成状态", "生成状态")
                .replace("\\textbackslash{}", "\\")
                .replace("\\textbackslash", "\\")
                .replace("\\_", "_");
        // Model JSON snapshots can persist LaTeX with doubled backslashes. Normalize that transport escape first;
        // otherwise the fallback sees a literal slash plus an unrecognized command and leaks raw LaTeX source.
        cleaned = cleaned.replace("\\\\", "\\");
        // XeLaTeX consumes these commands structurally. PDFBox must remove them before plain-text layout so
        // minipage widths and spacing commands never appear as student-facing words.
        cleaned = FALLBACK_LAYOUT_COMMAND.matcher(cleaned).replaceAll("");
        cleaned = MARKDOWN_IMAGE.matcher(cleaned).replaceAll("");
        cleaned = IMAGE_MARKER.matcher(cleaned).replaceAll("");
        cleaned = UNDERLINE_HSPACE_COMMAND.matcher(cleaned).replaceAll("________");
        cleaned = replaceRepeated(FRAC_COMMAND, cleaned, "($1)/($2)");
        cleaned = replaceRepeated(SQRT_COMMAND, cleaned, "√($1)");
        cleaned = replaceRepeated(WRAPPED_TEXT_COMMAND, cleaned, "$1");
        cleaned = normalizeMathScripts(cleaned);
        cleaned = cleaned
                .replace("$$", "")
                .replace("$", "")
                .replace("\\(", "")
                .replace("\\)", "")
                .replace("\\[", "")
                .replace("\\]", "")
                .replace("\\cdot", "·")
                .replace("\\times", "×")
                .replace("\\leq", "≤")
                .replace("\\le", "≤")
                .replace("\\geq", "≥")
                .replace("\\ge", "≥")
                .replace("\\neq", "≠")
                .replace("\\ne", "≠")
                .replace("\\infty", "∞")
                .replace("\\pi", "π")
                .replace("\\theta", "θ")
                .replace("\\alpha", "α")
                .replace("\\beta", "β")
                .replace("\\gamma", "γ")
                .replace("\\Delta", "Δ")
                .replace("\\partial", "∂")
                .replace("\\nabla", "∇")
                .replace("\\lambda", "λ")
                .replace("\\mu", "μ")
                .replace("\\sin", "sin")
                .replace("\\cos", "cos")
                .replace("\\tan", "tan")
                .replace("\\ln", "ln")
                .replace("\\log", "log")
                .replace("\\lim", "lim")
                .replace("\\to", "→")
                .replace("\\Rightarrow", "⇒")
                .replace("\\Longrightarrow", "⇒")
                .replace("\\Leftrightarrow", "⇔")
                .replace("\\left", "")
                .replace("\\right", "")
                .replace("\\Big|", "|")
                .replace("\\big|", "|")
                .replace("\\,", " ")
                .replace("\\;", " ")
                .replace("\\quad", " ")
                .replace("\\qquad", " ")
                .replace("\\%", "%")
                .replace("\\&", "&")
                .replace("\\_", "_")
                .replace("\\#", "#")
                .replaceAll("\\\\[a-zA-Z]+", "")
                .replace("{", "")
                .replace("}", "")
                .replaceAll("\\s+", " ")
                .strip();
        // Leftover backslashes are TeX line-break/control residue after all supported commands were converted.
        cleaned = cleaned.replace(latexSlash, " ").replaceAll("\\s+", " ").strip();
        return cleaned;
    }


    /**
     * Converts common LaTeX scripts to readable Unicode so formulas do not leak raw ^/_ syntax in PDFs.
     */
    static String normalizeMathScripts(String value) {
        String cleaned = value;
        cleaned = replaceScript(SUPERSCRIPT_BRACED, cleaned, true);
        cleaned = replaceScript(SUPERSCRIPT_SIMPLE, cleaned, true);
        cleaned = replaceScript(SUBSCRIPT_BRACED, cleaned, false);
        cleaned = replaceScript(SUBSCRIPT_SIMPLE, cleaned, false);
        return cleaned;
    }


    static String replaceScript(Pattern pattern, String value, boolean superscript) {
        Matcher matcher = pattern.matcher(value);
        StringBuffer buffer = new StringBuffer();
        while (matcher.find()) {
            String script = safeText(matcher.group(1));
            // SimHei and several other CJK fonts omit Unicode superscript/subscript glyphs. Keep a compact
            // ASCII notation in PDFBox output so x^2 and a_{10} remain readable instead of becoming question marks.
            String replacement = (superscript ? "^" : "_") + (script.length() == 1 ? script : "{" + script + "}");
            matcher.appendReplacement(buffer, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(buffer);
        return buffer.toString();
    }


    /**
     * Applies one regex replacement until nested simple commands no longer match.
     */
    static String replaceRepeated(Pattern pattern, String value, String replacement) {
        String current = value;
        for (int index = 0; index < 8; index += 1) {
            String next = pattern.matcher(current).replaceAll(replacement);
            if (next.equals(current)) {
                return next;
            }
            current = next;
        }
        return current;
    }


    /**
     * Returns a localized handout version title.
     */
    static String versionTitle(String version) {
        if ("lecture".equalsIgnoreCase(version)) {
            return "横版讲解稿";
        }
        return "student".equalsIgnoreCase(version) ? "学生版讲义" : "教师版讲义";
    }


    /**
     * Builds an internal rendering identity from the immutable template code and its display name.
     *
     * <p>The display name is audience-facing metadata and may legitimately be replaced by labels such as
     * “学生版讲义”.  It must therefore never be the sole source of a rendering decision: otherwise a Zhao
     * template silently falls back to the generic red/A4 wrapper for student and lecture exports.  This value
     * is only used inside the renderer; no template code is printed in the PDF.</p>
     */
    static String templateNameForVersion(TeachingTaskResponse task, String version) {
        if (task == null || task.selectedTemplate() == null) {

            return "标准讲义";
        }
        String templateCode = safeText(task.selectedTemplate().templateCode());
        String displayName = safeText(task.selectedTemplate().displayName());
        String identity = (templateCode + " " + displayName).strip();
        return identity.isBlank() ? "标准讲义" : identity;
    }


    /**
     * Returns stripped text or an empty string.
     */
    static String safeText(String value) {
        return value == null ? "" : value.strip();
    }


    /**
     * Returns fallback text for blank values.
     */
    static String nonBlank(String value, String fallback) {
        String text = safeText(value);
        return text.isBlank() ? fallback : text;
    }


    static String safeHeaderTopic(String value) {
        String text = safeText(value);
        return isUnreadablePlaceholderLine(text) ? "历史讲义" : nonBlank(text, "历史讲义");
    }



    /**
     * Defends both renderers from legacy snapshots and unsafe text. The request layer performs the same normalization;
     * retaining this boundary means old persisted records cannot bypass safe LaTeX escaping by direct export.
     */
    static String normalizedWatermark(String value) {
        String normalized = safeText(value).replaceAll("[\\p{Cntrl}]", "").replaceAll("\\s+", " ").strip();
        return normalized.isEmpty() || "飞猪数学".equals(normalized) ? "数学讲义" : normalized;
    }


    /**
     * Adds page numbers and footer metadata after the document page count is known.
     */
    static void addPageFooters(
            PDDocument document,
            PDFont font,
            PdfStyle style,
            String title,
            String watermark,
            String templateName) throws IOException {
        int totalPages = document.getNumberOfPages();
        boolean zhaoTemplate = PdfStyle.isZhaoLixianTemplate(templateName);
        for (int index = 0; index < totalPages; index += 1) {
            PDPage page = document.getPage(index);
            PDRectangle box = page.getMediaBox();
            float pageWidth = box.getWidth();
            float margin = footerMargin(style, zhaoTemplate);
            try (PDPageContentStream footer = new PDPageContentStream(document, page, AppendMode.APPEND, true, true)) {
                footer.setStrokingColor(style.border());
                footer.setLineWidth(0.4f);
                if (zhaoTemplate) {
                    // The master uses a short bottom rule with a tab page number that switches sides every page.
                    boolean oddPage = (index + 1) % 2 == 1;
                    float tabWidth = 31f;
                    float tabX = oddPage ? margin : pageWidth - margin - tabWidth;
                    float ruleStart = oddPage ? tabX + tabWidth : margin;
                    float ruleEnd = oddPage ? pageWidth - margin : tabX;
                    footer.moveTo(ruleStart, 38);
                    footer.lineTo(ruleEnd, 38);
                    footer.stroke();
                    footer.addRect(tabX, 33, tabWidth, 15);
                    footer.stroke();
                    String pageNo = Integer.toString(index + 1);
                    footer.beginText();
                    footer.setFont(font, 8.8f);
                    footer.setNonStrokingColor(style.bodyText());
                    float width = textWidth(font, pageNo, 8.8f);
                    footer.newLineAtOffset(tabX + (tabWidth - width) / 2f, 37);
                    footer.showText(supportedText(font, pageNo));
                    footer.endText();
                    continue;
                }
                footer.moveTo(margin, 38);
                footer.lineTo(pageWidth - margin, 38);
                footer.stroke();

                footer.beginText();
                footer.setFont(font, 8.8f);
                footer.setNonStrokingColor(style.mutedText());
                footer.newLineAtOffset(margin, 24);
                footer.showText(supportedText(font, normalizedWatermark(watermark) + " · " + title));
                footer.endText();

                String pageNo = "第 " + (index + 1) + " / " + totalPages + " 页";
                footer.beginText();
                footer.setFont(font, 8.8f);
                footer.setNonStrokingColor(style.mutedText());
                float width = textWidth(font, pageNo, 8.8f);
                footer.newLineAtOffset(pageWidth - margin - width, 24);
                footer.showText(supportedText(font, pageNo));
                footer.endText();
            }
        }
    }


    static float footerMargin(PdfStyle style, boolean zhaoTemplate) {
        if (style.isLecture()) {
            return LECTURE_MARGIN;
        }
        return zhaoTemplate ? ZHAO_CONTENT_MARGIN : MARGIN;
    }


    /**
     * Shared safe text encoding helper for footer/header overlays.
     */
    static String supportedText(PDFont font, String value) {
        String normalized = safeText(value).replace('\t', ' ');
        try {
            font.encode(normalized);
            return normalized;
        } catch (IllegalArgumentException | IOException exception) {
            StringBuilder builder = new StringBuilder();
            normalized.codePoints().forEach(codePoint -> {
                String character = new String(Character.toChars(codePoint));
                try {
                    font.encode(character);
                    builder.append(character);
                } catch (IllegalArgumentException | IOException ignored) {
                    builder.append('?');
                }
            });
            return builder.toString();
        }
    }


    static float textWidth(PDFont font, String text, float fontSize) {
        try {
            return font.getStringWidth(supportedText(font, text)) / 1000f * fontSize;
        } catch (IOException exception) {
            return safeText(text).length() * fontSize * 0.55f;
        }
    }
}
