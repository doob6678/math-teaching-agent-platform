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
import java.util.concurrent.TimeUnit;
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
    /** Bounds XeLaTeX so malformed generated source cannot retain a PDF request forever. */
    private static final Duration LATEX_TIMEOUT = Duration.ofSeconds(45);

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
                safeFileStem(handoutTitle(artifact)) + ".tex",
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
                safeFileStem(handoutTitle(artifact)) + ".md",
                "text/markdown; charset=UTF-8",
                body.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Compiles the UTF-8 LaTeX handout so fractions, roots, powers and Chinese text remain rendered notation.
     * A compiler failure is explicit: returning a PDFBox text fallback would silently corrupt the teaching material.
     */
    private static ExportPayload pdfPayload(MultiAgentWritingArtifact artifact) {
        Path workDir = null;
        try {
            Path engine = latexEnginePath().orElseThrow(() -> new IllegalStateException("未找到 XeLaTeX，拒绝生成未渲染公式的 PDF"));
            workDir = Files.createTempDirectory("math-agent-writing-pdf-");
            Path source = workDir.resolve("handout.tex");
            Files.writeString(source, latexDocument(artifact), StandardCharsets.UTF_8);
            runXeLaTeX(engine, workDir, source);
            runXeLaTeX(engine, workDir, source);
            Path pdf = workDir.resolve("handout.pdf");
            if (!Files.isRegularFile(pdf)) {
                throw new IllegalStateException("XeLaTeX 未生成讲义 PDF");
            }
            return new ExportPayload(
                    safeFileStem(handoutTitle(artifact)) + ".pdf",
                    "application/pdf",
                    Files.readAllBytes(pdf));
        } catch (IOException exception) {
            throw new IllegalStateException("生成公式讲义 PDF 失败", exception);
        } finally {
            if (workDir != null) {
                deleteRecursively(workDir);
            }
        }
    }

    /** Resolves the compiler inside Docker first, then allows the local Windows development installation. */
    private static Optional<Path> latexEnginePath() {
        String configured = System.getenv("MATH_AGENT_XELATEX_PATH");
        if (configured != null && !configured.isBlank() && Files.isRegularFile(Path.of(configured.strip()))) {
            return Optional.of(Path.of(configured.strip()));
        }
        for (Path candidate : List.of(
                Path.of("C:/Users/doob/AppData/Local/Programs/MiKTeX/miktex/bin/x64/xelatex.exe"),
                Path.of("C:/Program Files/MiKTeX/miktex/bin/x64/xelatex.exe"),
                Path.of("/usr/bin/xelatex"))) {
            if (Files.isRegularFile(candidate)) {
                return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }

    /** Runs one non-interactive compiler pass and includes its tail in the raised error for SQL/trace correlation. */
    private static void runXeLaTeX(Path engine, Path workDir, Path source) throws IOException {
        Path output = workDir.resolve("xelatex.out");
        try {
            Process process = new ProcessBuilder(engine.toString(), "-interaction=nonstopmode", "-halt-on-error", source.getFileName().toString())
                    .directory(workDir.toFile()).redirectErrorStream(true).redirectOutput(output.toFile()).start();
            if (!process.waitFor(LATEX_TIMEOUT.toSeconds(), TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IllegalStateException("XeLaTeX 编译超时");
            }
            if (process.exitValue() != 0) {
                String detail = Files.isRegularFile(output) ? Files.readString(output, StandardCharsets.UTF_8) : "";
                throw new IllegalStateException("XeLaTeX 编译失败：" + detail.substring(Math.max(0, detail.length() - 800)));
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("XeLaTeX 编译被中断", exception);
        }
    }

    /** Deletes the isolated compiler directory after bytes have been copied into the export response. */
    private static void deleteRecursively(Path root) {
        try (var paths = Files.walk(root)) {
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // A temporary compiler artifact is never allowed to fail the already-created export.
                }
            });
        } catch (IOException ignored) {
            // See above: cleanup cannot hide a valid export result.
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
        String content = finalHandoutMarkdown(artifact);
        StringBuilder latex = new StringBuilder();
        latex.append("""
                \\documentclass[UTF8]{ctexart}
                \\usepackage{amsmath,amssymb,geometry,fontspec}
                \\usepackage{enumitem}
                \\setCJKmainfont{Noto Serif CJK SC}
                \\setCJKsansfont{Noto Sans CJK SC}
                \\setmainfont{Noto Serif CJK SC}
                \\geometry{a4paper,margin=2cm}
                \\setlist{nosep,leftmargin=2em}
                \\title{%s}
                \\date{}
                \\begin{document}
                \\maketitle

                """.formatted(escapeLatexText(handoutTitle(artifact))));
        appendMarkdownAsLatex(latex, content);
        latex.append("\n\\end{document}\n");
        return latex.toString();
    }

    /** Returns only the merge coordinator's classroom-facing result, never agent traces or review JSON. */
    private static String finalHandoutMarkdown(MultiAgentWritingArtifact artifact) {
        for (MultiAgentWritingArtifact.StructuredSection section : artifact.sections()) {
            if ("final-handout".equals(section.sectionCode()) && !section.content().isBlank()) {
                return section.content().strip();
            }
        }
        String merged = safeText(artifact.mergedMarkdown());
        int finalStart = merged.lastIndexOf("\n## 一、");
        if (finalStart >= 0) {
            return merged.substring(finalStart + 1).strip();
        }
        throw new IllegalStateException("讲义缺少最终审校正文，拒绝导出 Agent 过程日志");
    }

    /**
     * Uses Luna's first Markdown H1 as the human-facing document identity.  This ties every export format to the
     * actual lesson topic instead of leaking an opaque workflow UUID into a teacher's downloaded file name.
     */
    private static String handoutTitle(MultiAgentWritingArtifact artifact) {
        for (String rawLine : safeText(artifact.mergedMarkdown()).replace("\r\n", "\n").split("\n")) {
            String line = rawLine.strip();
            if (line.startsWith("# ")) {
                String title = line.substring(2).replaceAll("[\\p{Cntrl}\\r\\n]+", " ").strip();
                if (!title.isBlank() && title.codePointCount(0, title.length()) <= 80) {
                    return title;
                }
            }
        }
        for (MultiAgentWritingArtifact.StructuredSection section : artifact.sections()) {
            if (!safeText(section.title()).isBlank()) {
                return safeText(section.title());
            }
        }
        throw new IllegalStateException("讲义缺少 AI 生成标题，拒绝导出无标题 PDF");
    }

    /**
     * Appends a compact Markdown subset as LaTeX while preserving inline math delimiters.
     */
    private static void appendMarkdownAsLatex(StringBuilder latex, String markdown) {
        boolean inItemize = false;
        boolean inDisplayMath = false;
        boolean inTable = false;
        for (String rawLine : markdown.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1)) {
            String line = rawLine.stripTrailing();
            boolean tableRow = line.strip().startsWith("|") && line.strip().endsWith("|");
            if (inTable && !tableRow) {
                latex.append("\\hline\n\\end{tabular}\n\\end{center}\n");
                inTable = false;
            }
            if (inDisplayMath) {
                latex.append(line).append('\n');
                if (line.strip().equals("\\]")) {
                    inDisplayMath = false;
                }
                continue;
            }
            if (line.strip().equals("\\[")) {
                if (inItemize) {
                    latex.append("\\end{itemize}\n");
                    inItemize = false;
                }
                latex.append("\\[\n");
                inDisplayMath = true;
                continue;
            }
            if (line.strip().startsWith("\\[") && line.strip().endsWith("\\]")) {
                if (inItemize) {
                    latex.append("\\end{itemize}\n");
                    inItemize = false;
                }
                latex.append(line.strip()).append('\n');
                continue;
            }
            if (tableRow) {
                if (inItemize) {
                    latex.append("\\end{itemize}\n");
                    inItemize = false;
                }
                String tableLine = line.strip();
                if (tableLine.matches("^\\|(?:\\s*:?-{3,}:?\\s*\\|)+$")) {
                    latex.append("\\hline\n");
                    continue;
                }
                if (!inTable) {
                    latex.append("\\begin{center}\n\\begin{tabular}{|p{0.28\\linewidth}|p{0.60\\linewidth}|}\n\\hline\n");
                    inTable = true;
                }
                String[] cells = tableLine.substring(1, tableLine.length() - 1).split("\\|", -1);
                for (int index = 0; index < cells.length; index++) {
                    if (index > 0) {
                        latex.append(" & " );
                    }
                    latex.append(escapeLatexTextPreservingMath(cells[index].strip()));
                }
                latex.append(" \\\\ \n");
                continue;
            }
            if (line.isBlank()) {
                if (inItemize) {
                    latex.append("\\end{itemize}\n");
                    inItemize = false;
                }
                latex.append('\n');
                continue;
            }
            if (line.startsWith("#### ")) {
                if (inItemize) {
                    latex.append("\\end{itemize}\n");
                    inItemize = false;
                }
                latex.append("\\paragraph{").append(escapeLatexText(line.substring(5).strip())).append("}\n");
            } else if (line.startsWith("### ")) {
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
        if (inTable) {
            latex.append("\\hline\n\\end{tabular}\n\\end{center}\n");
        }
        if (inDisplayMath) {
            throw new IllegalArgumentException("讲义包含未闭合的显示公式");
        }
    }

    /**
     * Escapes normal text segments while leaving $...$ and $$...$$ math untouched.
     */
    private static String escapeLatexTextPreservingMath(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        // Markdown uses \( ... \) for inline math. A literal replacement is deterministic and avoids the
        // double-escaping ambiguity of a Java regular expression around LaTeX backslashes.
        value = value.replace("\\(", "$").replace("\\)", "$").replace("**", "");
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
