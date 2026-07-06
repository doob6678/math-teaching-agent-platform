package com.doob.mathagent.agent.service;

import com.doob.mathagent.agent.vo.MultiAgentWritingArtifactExportResponse;
import com.doob.mathagent.infrastructure.security.RequestSubject;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Creates short-lived export payloads for owner-visible multi-agent writing artifacts.
 */
@Service
public class MultiAgentWritingArtifactExportService {

    private static final Duration DEFAULT_TTL = Duration.ofMinutes(30);
    private static final float PDF_MARGIN = 54;
    private static final float PDF_BODY_FONT_SIZE = 10.5f;
    private static final float PDF_TITLE_FONT_SIZE = 16;
    private static final float PDF_HEADING_FONT_SIZE = 12;
    private static final float PDF_LEADING = 17;
    private static final int PDF_WRAP_UNITS = 74;

    private final MultiAgentWritingService writingService;
    private final Clock clock;
    private final Duration ttl;

    /**
     * Creates a production export service.
     *
     * @param writingService workflow service used for owner-visible artifact lookup
     * @param ttlMinutes temporary export lifetime in minutes
     */
    @Autowired
    public MultiAgentWritingArtifactExportService(
            MultiAgentWritingService writingService,
            @Value("${math-agent.agent.writing.artifact-export-ttl-minutes:30}") long ttlMinutes) {
        this(writingService, Clock.systemUTC(), Duration.ofMinutes(Math.max(1, ttlMinutes)));
    }

    /**
     * Creates a testable export service.
     *
     * @param writingService workflow service used for owner-visible artifact lookup
     * @param clock clock used to calculate expiration
     * @param ttl temporary export lifetime
     */
    public MultiAgentWritingArtifactExportService(
            MultiAgentWritingService writingService,
            Clock clock,
            Duration ttl) {
        this.writingService = writingService;
        this.clock = clock;
        this.ttl = ttl == null || ttl.isNegative() || ttl.isZero() ? DEFAULT_TTL : ttl;
    }

    /**
     * Exports a workflow artifact as Markdown, LaTeX, or ZIP bytes encoded for MCP transport.
     *
     * @param workflowId workflow id
     * @param format requested format: markdown, latex, or zip
     * @param subject backend-resolved owner subject
     * @return export response containing base64 bytes and checksum
     */
    public MultiAgentWritingArtifactExportResponse export(
            String workflowId,
            String format,
            RequestSubject subject) {
        MultiAgentWritingArtifact artifact = writingService.artifact(workflowId, subject);
        String normalizedFormat = normalizeFormat(format);
        ExportPayload payload = switch (normalizedFormat) {
            case "zip" -> zipPayload(artifact);
            case "pdf" -> pdfPayload(artifact);
            case "latex" -> latexPayload(artifact);
            case "markdown" -> markdownPayload(artifact);
            default -> throw new IllegalArgumentException("Unsupported artifact export format: " + normalizedFormat);
        };
        String exportId = UUID.randomUUID().toString();
        Instant expiresAt = Instant.now(clock).plus(ttl);
        return new MultiAgentWritingArtifactExportResponse(
                exportId,
                artifact.workflowId(),
                normalizedFormat,
                payload.fileName(),
                payload.mimeType(),
                payload.bytes().length,
                sha256(payload.bytes()),
                Base64.getEncoder().encodeToString(payload.bytes()),
                expiresAt);
    }

    /**
     * Creates a LaTeX payload from the merged artifact body for frontend preview or download.
     */
    private static ExportPayload latexPayload(MultiAgentWritingArtifact artifact) {
        String body = latexDocument(artifact);
        return new ExportPayload(
                safeFileStem(artifact.workflowId()) + ".tex",
                "application/x-tex; charset=UTF-8",
                body.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Creates a Markdown payload from the merged artifact body.
     */
    private static ExportPayload markdownPayload(MultiAgentWritingArtifact artifact) {
        String body = artifact.mergedMarkdown().isBlank()
                ? "# Multi-agent writing artifact\n\nNo generated content is available yet.\n"
                : artifact.mergedMarkdown() + "\n";
        return new ExportPayload(
                safeFileStem(artifact.workflowId()) + ".md",
                "text/markdown; charset=UTF-8",
                body.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Creates a readable PDF payload from the merged artifact body for frontend review and download.
     */
    private static ExportPayload pdfPayload(MultiAgentWritingArtifact artifact) {
        try (PDDocument document = new PDDocument()) {
            PDFont font = loadReadableFont(document);
            PdfWriter writer = new PdfWriter(document, font);
            writer.writeTitle("讲义协作成果");
            writer.writeMuted("流程编号：" + safeText(artifact.workflowId()));
            writer.writeMuted("状态：" + safeText(artifact.status()) + " / 用量：" + artifact.totalUsage().totalTokens() + " tokens");
            writer.writeBlank();
            for (ReadableLine line : readableMarkdownLines(artifact.mergedMarkdown())) {
                writer.write(line);
            }
            writer.close();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.save(out);
            return new ExportPayload(
                    safeFileStem(artifact.workflowId()) + ".pdf",
                    "application/pdf",
                    out.toByteArray());
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to create multi-agent writing PDF export", exception);
        }
    }

    /**
     * Creates a ZIP payload with merged, per-stage, and manifest files.
     */
    private static ExportPayload zipPayload(MultiAgentWritingArtifact artifact) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (ZipOutputStream zip = new ZipOutputStream(bytes, StandardCharsets.UTF_8)) {
                put(zip, "merged.md", (artifact.mergedMarkdown() + "\n").getBytes(StandardCharsets.UTF_8));
                for (MultiAgentWritingArtifact.StageArtifact stage : safeStages(artifact.stages())) {
                    put(zip,
                            "stages/" + safeFileStem(stage.stageCode()) + ".md",
                            (stage.generatedContent() + "\n").getBytes(StandardCharsets.UTF_8));
                }
                put(zip, "manifest.txt", manifest(artifact).getBytes(StandardCharsets.UTF_8));
            }
            return new ExportPayload(
                    safeFileStem(artifact.workflowId()) + ".zip",
                    "application/zip",
                    bytes.toByteArray());
        } catch (java.io.IOException exception) {
            throw new IllegalStateException("Failed to create multi-agent writing ZIP export", exception);
        }
    }

    /**
     * Writes one ZIP entry.
     */
    private static void put(ZipOutputStream zip, String name, byte[] bytes) throws java.io.IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(bytes);
        zip.closeEntry();
    }

    /**
     * Builds an offline manifest for exported artifacts.
     */
    private static String manifest(MultiAgentWritingArtifact artifact) {
        return """
                workflowId=%s
                tenantId=%s
                subjectType=%s
                subjectId=%s
                status=%s
                totalTokens=%d
                stageCount=%d
                """.formatted(
                artifact.workflowId(),
                artifact.tenantId(),
                artifact.subjectType(),
                artifact.subjectId(),
                artifact.status(),
                artifact.totalUsage().totalTokens(),
                safeStages(artifact.stages()).size());
    }

    /**
     * Converts the merged Markdown-like artifact into a conservative LaTeX document.
     */
    private static String latexDocument(MultiAgentWritingArtifact artifact) {
        String content = artifact.mergedMarkdown().isBlank()
                ? "No generated content is available yet."
                : artifact.mergedMarkdown();
        StringBuilder latex = new StringBuilder();
        latex.append("""
                \\documentclass[UTF8]{ctexart}
                \\usepackage{amsmath,amssymb,geometry}
                \\usepackage{enumitem}
                \\geometry{a4paper,margin=2cm}
                \\setlist{nosep,leftmargin=2em}
                \\title{Multi-agent Writing Artifact}
                \\date{}
                \\begin{document}
                \\maketitle

                """);
        latex.append("\\section*{Metadata}\n");
        latex.append("\\begin{itemize}\n");
        latex.append("\\item Workflow ID: ").append(escapeLatexText(artifact.workflowId())).append('\n');
        latex.append("\\item Status: ").append(escapeLatexText(artifact.status())).append('\n');
        latex.append("\\item Total tokens: ").append(artifact.totalUsage().totalTokens()).append('\n');
        latex.append("\\end{itemize}\n\n");
        appendMarkdownAsLatex(latex, content);
        latex.append("\n\\end{document}\n");
        return latex.toString();
    }

    /**
     * Appends a compact Markdown subset as LaTeX while preserving inline math delimiters.
     */
    private static void appendMarkdownAsLatex(StringBuilder latex, String markdown) {
        boolean inItemize = false;
        for (String rawLine : markdown.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1)) {
            String line = rawLine.stripTrailing();
            if (line.isBlank()) {
                if (inItemize) {
                    latex.append("\\end{itemize}\n");
                    inItemize = false;
                }
                latex.append('\n');
                continue;
            }
            if (line.startsWith("### ")) {
                if (inItemize) {
                    latex.append("\\end{itemize}\n");
                    inItemize = false;
                }
                latex.append("\\subsubsection*{").append(escapeLatexText(line.substring(4).strip())).append("}\n");
            } else if (line.startsWith("## ")) {
                if (inItemize) {
                    latex.append("\\end{itemize}\n");
                    inItemize = false;
                }
                latex.append("\\subsection*{").append(escapeLatexText(line.substring(3).strip())).append("}\n");
            } else if (line.startsWith("# ")) {
                if (inItemize) {
                    latex.append("\\end{itemize}\n");
                    inItemize = false;
                }
                latex.append("\\section*{").append(escapeLatexText(line.substring(2).strip())).append("}\n");
            } else if (line.startsWith("- ") || line.startsWith("* ")) {
                if (!inItemize) {
                    latex.append("\\begin{itemize}\n");
                    inItemize = true;
                }
                latex.append("\\item ").append(escapeLatexTextPreservingMath(line.substring(2).strip())).append('\n');
            } else {
                if (inItemize) {
                    latex.append("\\end{itemize}\n");
                    inItemize = false;
                }
                latex.append(escapeLatexTextPreservingMath(line)).append("\\par\n");
            }
        }
        if (inItemize) {
            latex.append("\\end{itemize}\n");
        }
    }

    /**
     * Escapes normal text segments while leaving $...$ and $$...$$ math untouched.
     */
    private static String escapeLatexTextPreservingMath(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        StringBuilder escaped = new StringBuilder();
        int index = 0;
        while (index < value.length()) {
            int mathStart = value.indexOf('$', index);
            if (mathStart < 0) {
                escaped.append(escapeLatexText(value.substring(index)));
                break;
            }
            escaped.append(escapeLatexText(value.substring(index, mathStart)));
            boolean displayMath = mathStart + 1 < value.length() && value.charAt(mathStart + 1) == '$';
            String delimiter = displayMath ? "$$" : "$";
            int contentStart = mathStart + delimiter.length();
            int mathEnd = value.indexOf(delimiter, contentStart);
            if (mathEnd < 0) {
                escaped.append(escapeLatexText(value.substring(mathStart)));
                break;
            }
            escaped.append(value, mathStart, mathEnd + delimiter.length());
            index = mathEnd + delimiter.length();
        }
        return escaped.toString();
    }

    /**
     * Escapes text for LaTeX outside math mode.
     */
    private static String escapeLatexText(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        StringBuilder escaped = new StringBuilder(value.length());
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '\\' -> escaped.append("\\textbackslash{}");
                case '{' -> escaped.append("\\{");
                case '}' -> escaped.append("\\}");
                case '&' -> escaped.append("\\&");
                case '%' -> escaped.append("\\%");
                case '#' -> escaped.append("\\#");
                case '_' -> escaped.append("\\_");
                case '^' -> escaped.append("\\textasciicircum{}");
                case '~' -> escaped.append("\\textasciitilde{}");
                case '$' -> escaped.append("\\$");
                default -> escaped.append(character);
            }
        }
        return escaped.toString();
    }

    /**
     * Normalizes external format aliases.
     */
    private static String normalizeFormat(String format) {
        if (format == null || format.isBlank()) {
            return "markdown";
        }
        return switch (format.strip().toLowerCase()) {
            case "md", "markdown" -> "markdown";
            case "tex", "latex" -> "latex";
            case "pdf" -> "pdf";
            case "zip" -> "zip";
            default -> format.strip().toLowerCase();
        };
    }

    /**
     * Loads a local Unicode font so Chinese PDF exports are readable.
     */
    private static PDFont loadReadableFont(PDDocument document) throws IOException {
        Optional<Path> configuredFont = configuredFontPath();
        if (configuredFont.isPresent()) {
            return PDType0Font.load(document, configuredFont.get().toFile());
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
        return new PDType1Font(Standard14Fonts.FontName.HELVETICA);
    }

    /**
     * Returns an operator-provided font path when available.
     */
    private static Optional<Path> configuredFontPath() {
        String value = System.getenv("MATH_AGENT_PDF_FONT_PATH");
        if (value == null || value.isBlank()) {
            value = System.getProperty("math.agent.pdf.font.path", "");
        }
        if (value.isBlank()) {
            return Optional.empty();
        }
        Path path = Path.of(value.strip());
        return Files.isRegularFile(path) ? Optional.of(path) : Optional.empty();
    }

    /**
     * Known font files available on Windows and common Linux developer machines.
     */
    private static List<Path> commonFontPaths() {
        return List.of(
                Path.of("C:/Windows/Fonts/simhei.ttf"),
                Path.of("C:/Windows/Fonts/simkai.ttf"),
                Path.of("C:/Windows/Fonts/simfang.ttf"),
                Path.of("C:/Windows/Fonts/msyh.ttf"),
                Path.of("/usr/share/fonts/truetype/noto/NotoSansCJK-Regular.ttc"),
                Path.of("/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc"),
                Path.of("/usr/share/fonts/truetype/wqy/wqy-microhei.ttc"),
                Path.of("/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf"));
    }

    /**
     * Converts Markdown-like generated content into readable PDF lines.
     */
    private static List<ReadableLine> readableMarkdownLines(String markdown) {
        if (markdown == null || markdown.isBlank()) {
            return List.of(new ReadableLine(LineType.PARAGRAPH, "暂无可展示讲义内容。"));
        }
        java.util.ArrayList<ReadableLine> lines = new java.util.ArrayList<>();
        for (String rawLine : markdown.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1)) {
            String line = rawLine.strip();
            if (line.isBlank()) {
                addBlank(lines);
            } else if (line.startsWith("### ")) {
                lines.add(new ReadableLine(LineType.HEADING, cleanMarkdownText(line.substring(4))));
            } else if (line.startsWith("## ")) {
                lines.add(new ReadableLine(LineType.HEADING, cleanMarkdownText(line.substring(3))));
            } else if (line.startsWith("# ")) {
                lines.add(new ReadableLine(LineType.HEADING, cleanMarkdownText(line.substring(2))));
            } else if (line.startsWith("- ") || line.startsWith("* ")) {
                lines.add(new ReadableLine(LineType.BULLET, cleanMarkdownText(line.substring(2))));
            } else {
                lines.add(new ReadableLine(LineType.PARAGRAPH, cleanMarkdownText(line)));
            }
        }
        return lines;
    }

    /**
     * Adds one paragraph break unless the previous line is already blank.
     */
    private static void addBlank(List<ReadableLine> lines) {
        if (!lines.isEmpty() && lines.get(lines.size() - 1).type() != LineType.BLANK) {
            lines.add(new ReadableLine(LineType.BLANK, ""));
        }
    }

    /**
     * Removes common Markdown markers while preserving readable math text.
     */
    private static String cleanMarkdownText(String value) {
        return safeText(value)
                .replace("**", "")
                .replace("__", "")
                .replace("`", "")
                .replace("$$", "")
                .replace("$", "")
                .replace("\\leq", "≤")
                .replace("\\geq", "≥")
                .replace("\\neq", "≠")
                .replace("\\times", "×")
                .replace("\\cdot", "·")
                .replace("\\infty", "∞")
                .replace("\\pi", "π")
                .replace("\\theta", "θ")
                .replace("\\alpha", "α")
                .replace("\\beta", "β")
                .replace("\\gamma", "γ")
                .replaceAll("\\s+", " ")
                .strip();
    }

    /**
     * Returns stripped text or an empty string.
     */
    private static String safeText(String value) {
        return value == null ? "" : value.strip();
    }

    /**
     * Returns stage list or an empty list.
     */
    private static List<MultiAgentWritingArtifact.StageArtifact> safeStages(
            List<MultiAgentWritingArtifact.StageArtifact> stages) {
        return stages == null ? List.of() : stages;
    }

    /**
     * Converts ids into deterministic file names without path traversal.
     */
    private static String safeFileStem(String value) {
        String safe = value == null || value.isBlank() ? "artifact" : value.strip();
        safe = safe.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]+", "_")
                .replaceAll("^\\.+", "")
                .strip();
        return safe.isBlank() ? "artifact" : safe;
    }

    /**
     * Computes SHA-256 for exported bytes.
     */
    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 digest is not available", exception);
        }
    }

    /**
     * Internal export payload before it is wrapped for MCP transport.
     */
    private record ExportPayload(String fileName, String mimeType, byte[] bytes) {
    }

    private enum LineType {
        TITLE,
        HEADING,
        PARAGRAPH,
        BULLET,
        MUTED,
        BLANK
    }

    private record ReadableLine(LineType type, String text) {
    }

    /**
     * Small paginated PDF writer for generated handout artifacts.
     */
    private static final class PdfWriter {
        private final PDDocument document;
        private final PDFont font;
        private PDPageContentStream stream;
        private float y;

        private PdfWriter(PDDocument document, PDFont font) throws IOException {
            this.document = document;
            this.font = font;
            newPage();
        }

        private void writeTitle(String text) throws IOException {
            write(new ReadableLine(LineType.TITLE, text));
        }

        private void writeMuted(String text) throws IOException {
            write(new ReadableLine(LineType.MUTED, text));
        }

        private void writeBlank() throws IOException {
            write(new ReadableLine(LineType.BLANK, ""));
        }

        private void write(ReadableLine line) throws IOException {
            if (line.type() == LineType.BLANK) {
                y -= PDF_LEADING * 0.6f;
                ensureSpace(PDF_LEADING);
                return;
            }
            float fontSize = switch (line.type()) {
                case TITLE -> PDF_TITLE_FONT_SIZE;
                case HEADING -> PDF_HEADING_FONT_SIZE;
                case MUTED -> PDF_BODY_FONT_SIZE - 1;
                default -> PDF_BODY_FONT_SIZE;
            };
            float left = line.type() == LineType.BULLET ? PDF_MARGIN + 14 : PDF_MARGIN;
            String prefix = line.type() == LineType.BULLET ? "• " : "";
            for (String wrapped : wrap(prefix + line.text(), line.type() == LineType.BULLET ? PDF_WRAP_UNITS - 4 : PDF_WRAP_UNITS)) {
                ensureSpace(PDF_LEADING);
                stream.beginText();
                stream.setFont(font, fontSize);
                stream.newLineAtOffset(left, y);
                stream.showText(supportedText(wrapped));
                stream.endText();
                y -= line.type() == LineType.TITLE ? PDF_LEADING * 1.25f : PDF_LEADING;
            }
            if (line.type() == LineType.TITLE || line.type() == LineType.HEADING) {
                y -= 4;
            }
        }

        private void ensureSpace(float required) throws IOException {
            if (y - required < PDF_MARGIN) {
                newPage();
            }
        }

        private void newPage() throws IOException {
            if (stream != null) {
                stream.close();
            }
            PDPage page = new PDPage(PDRectangle.A4);
            document.addPage(page);
            stream = new PDPageContentStream(document, page);
            y = PDRectangle.A4.getHeight() - PDF_MARGIN;
        }

        private void close() throws IOException {
            if (stream != null) {
                stream.close();
                stream = null;
            }
        }

        private String supportedText(String value) {
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

        private static List<String> wrap(String text, int maxUnits) {
            java.util.ArrayList<String> lines = new java.util.ArrayList<>();
            StringBuilder current = new StringBuilder();
            int units = 0;
            for (int offset = 0; offset < text.length(); ) {
                int codePoint = text.codePointAt(offset);
                String character = new String(Character.toChars(codePoint));
                int characterUnits = displayUnits(codePoint);
                if (units + characterUnits > maxUnits && !current.isEmpty()) {
                    lines.add(current.toString().stripTrailing());
                    current.setLength(0);
                    units = 0;
                }
                current.append(character);
                units += characterUnits;
                offset += Character.charCount(codePoint);
            }
            if (!current.isEmpty()) {
                lines.add(current.toString().stripTrailing());
            }
            return lines.isEmpty() ? List.of("") : lines;
        }

        private static int displayUnits(int codePoint) {
            Character.UnicodeScript script = Character.UnicodeScript.of(codePoint);
            if (script == Character.UnicodeScript.HAN
                    || script == Character.UnicodeScript.HIRAGANA
                    || script == Character.UnicodeScript.KATAKANA
                    || Character.UnicodeBlock.of(codePoint).toString().toLowerCase(Locale.ROOT).contains("cjk")) {
                return 2;
            }
            return 1;
        }
    }
}
