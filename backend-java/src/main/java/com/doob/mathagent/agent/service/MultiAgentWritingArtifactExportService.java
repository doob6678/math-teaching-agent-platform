package com.doob.mathagent.agent.service;

import com.doob.mathagent.agent.vo.MultiAgentWritingArtifactExportResponse;
import com.doob.mathagent.infrastructure.security.RequestSubject;
import com.doob.mathagent.infrastructure.text.FormulaMarkupSanitizer;
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
import java.util.ArrayList;
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
    private static final int MAX_LAYOUT_LABEL_CODE_POINTS = 60;
    /** CSS pixels are converted to TeX points so rich-text student work areas remain actual blank space. */
    private static final double CSS_PIXELS_PER_INCH = 96.0d;
    private static final double TEX_POINTS_PER_INCH = 72.0d;
    /** Human-readable title of the configured TENANT_PUBLIC shared-root evidence corpus. */
    private static final String SHARED_ROOT_SOURCE_TITLE = "高中数学（全局共享）";
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
        return export(workflowId, format, "", "", subject);
    }

    /**
     * Exports one artifact with renderer-owned header and footer labels.
     *
     * <p>These labels are intentionally accepted only by the export layer. They never enter an Agent prompt, so a
     * teacher can rebrand the same reviewed mathematical content without paying for another generation or allowing
     * layout instructions to leak into the handout body.</p>
     */
    public MultiAgentWritingArtifactExportResponse export(
            String workflowId,
            String format,
            String headerText,
            String footerText,
            RequestSubject subject) {
        MultiAgentWritingArtifact artifact = writingService.artifact(workflowId, subject);
        String normalizedFormat = normalizeFormat(format);
        String normalizedHeader = normalizeLayoutLabel(headerText);
        String normalizedFooter = normalizeLayoutLabel(footerText);
        ExportPayload payload = switch (normalizedFormat) {
            case "zip" -> zipPayload(artifact);
            case "pdf" -> pdfPayload(artifact, HandoutVariant.FINAL, normalizedHeader, normalizedFooter);
            case "pdf-teacher" -> pdfPayload(artifact, HandoutVariant.TEACHER, normalizedHeader, normalizedFooter);
            case "pdf-student" -> pdfPayload(artifact, HandoutVariant.STUDENT, normalizedHeader, normalizedFooter);
            case "pdf-lecture" -> pdfPayload(artifact, HandoutVariant.LECTURE, normalizedHeader, normalizedFooter);
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
    private static ExportPayload pdfPayload(
            MultiAgentWritingArtifact artifact,
            HandoutVariant variant,
            String headerText,
            String footerText) {
        Path workDir = null;
        try {
            String content = variantMarkdown(artifact, variant);
            enforceAudienceBoundary(content, variant);
            String title = variantTitle(artifact, content, variant);
            Path engine = latexEnginePath().orElseThrow(() -> new IllegalStateException("未找到 XeLaTeX，拒绝生成未渲染公式的 PDF"));
            workDir = Files.createTempDirectory("math-agent-writing-pdf-");
            Path source = workDir.resolve("handout.tex");
            Files.writeString(source, latexDocument(content, title, variant, headerText, footerText), StandardCharsets.UTF_8);
            runXeLaTeX(engine, workDir, source);
            runXeLaTeX(engine, workDir, source);
            Path pdf = workDir.resolve("handout.pdf");
            if (!Files.isRegularFile(pdf)) {
                throw new IllegalStateException("XeLaTeX 未生成讲义 PDF");
            }
            return new ExportPayload(
                    safeFileStem(title) + variant.fileSuffix() + ".pdf",
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
                throw new IllegalStateException("XeLaTeX 编译失败："
                        + detail.substring(Math.max(0, detail.length() - 800))
                        + "\nsource=" + latexSourceWindow(source, detail));
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("XeLaTeX 编译被中断", exception);
        }
    }

    /** Returns only the failing source neighbourhood, never the whole handout or retrieval content. */
    private static String latexSourceWindow(Path source, String compilerOutput) throws IOException {
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("(?m)^l\\.(\\d+)").matcher(compilerOutput);
        if (!matcher.find()) return "line=unknown";
        int failingLine = Integer.parseInt(matcher.group(1));
        List<String> lines = Files.readAllLines(source, StandardCharsets.UTF_8);
        int from = Math.max(0, failingLine - 3);
        int to = Math.min(lines.size(), failingLine + 2);
        StringBuilder window = new StringBuilder("line=").append(failingLine).append(':');
        for (int index = from; index < to; index++) {
            window.append(' ').append(index + 1).append('=').append(lines.get(index).replaceAll("[\\r\\n]+", " "));
        }
        return window.toString();
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
        return latexDocument(content, handoutTitle(artifact), HandoutVariant.FINAL, "", "");
    }

    /** Builds the print wrapper; mathematical content remains owned by the reviewed Agent artifact. */
    /**
     * Renderer-owned PDF entry point.  Before changing this method or its helpers, read
     * {@code docs/handout-pdf-rendering-development-standard.md}: it defines the verified XeLaTeX/Noto CJK,
     * formula, header/footer, student-writing-space and Windows visual-audit contracts that model output may not
     * bypass.
     */
    private static String latexDocument(
            String content,
            String title,
            HandoutVariant variant,
            String headerText,
            String footerText) {
        // Agents produce Markdown, while XeLaTeX needs a single unambiguous math grammar.  Normalizing here keeps
        // every export channel consistent; the validation immediately after it refuses ambiguous notation instead
        // of creating a visually polished but mathematically wrong PDF.
        content = normalizeAndValidateHandoutMath(content);
        StringBuilder latex = new StringBuilder();
        String geometry = variant == HandoutVariant.LECTURE
                ? "paperwidth=12.8in,paperheight=8in,margin=0.62in,headheight=16pt"
                : "a4paper,margin=2cm,headheight=16pt";
        String renderedHeader = headerText.isBlank() ? title : headerText;
        String renderedFooter = footerText.isBlank() ? variant.defaultFooter() : footerText;
        String titleBlock = variant == HandoutVariant.LECTURE
                // A 16:10 projection must begin with the real question, not a cover-page-sized title.  Keeping the
                // lesson identity in the compact header leaves the first visual line available to the prompt.
                ? "\\noindent\\textbf{\\large " + escapeLatexText(title) + "}\\par\\vspace{0.35em}\n"
                // Java must emit a physical newline after \maketitle.  A literal "\\n" is parsed by XeLaTeX
                // as an undefined control sequence and aborts all PDF variants before any formula can render.
                : "\\maketitle\n";
        latex.append("""
                \\documentclass[UTF8]{ctexart}
                \\usepackage{amsmath,amssymb,geometry,fontspec,fancyhdr,xcolor}
                \\usepackage{enumitem}
                \\definecolor{MathAgentNavy}{HTML}{17365D}
                \\definecolor{MathAgentTeal}{HTML}{147D88}
                \\definecolor{MathAgentSlate}{HTML}{52616B}
                \\setCJKmainfont{Noto Serif CJK SC}
                \\setCJKsansfont{Noto Sans CJK SC}
                \\setmainfont{Noto Serif CJK SC}
                \\geometry{%s}
                \\setlist{nosep,leftmargin=2em}
                \\pagestyle{fancy}
                \\fancyhf{}
                \\fancyhead[L]{\\small\\color{MathAgentNavy} %s}
                \\fancyhead[R]{\\small\\color{MathAgentTeal} %s}
                \\fancyfoot[L]{\\small\\color{MathAgentSlate} %s}
                \\fancyfoot[R]{\\small 第 \\thepage 页}
                \\renewcommand{\\headrulewidth}{0.4pt}
                \\renewcommand{\\footrulewidth}{0.2pt}
                \\title{\\color{MathAgentNavy}%s}
                \\date{}
                \\begin{document}
                """.formatted(
                geometry,
                escapeLatexText(renderedHeader),
                escapeLatexText(variant.displayName()),
                escapeLatexText(renderedFooter),
                escapeLatexText(title)));
        if (variant == HandoutVariant.LECTURE) {
            // One 16:10 page is a product invariant; renderer-owned compact spacing prevents a trailing reminder
            // from becoming an accidental second projection slide.
            latex.append("\\small\\setlength{\\parskip}{0pt}\n");
        }
        latex.append(titleBlock);
        appendMarkdownAsLatex(latex, withoutLeadingDocumentTitle(content, title));
        latex.append("\n\\end{document}\n");
        // Defense in depth for provider-authored worksheet blanks that cross a Markdown table boundary before the
        // line-level converter can normalize them.  Mathematical subscripts are single underscores, while a raw
        // run is always a handwriting blank and must become one valid TeX rule.
        String withSafeRules = latex.toString().replace("MATHAGENTFILLBLANKRULE", "\\rule{0.82\\linewidth}{0.4pt}");
        String withWritingSpace = replaceHtmlWritingSpace(withSafeRules);
        return java.util.regex.Pattern.compile("(?<!\\\\)_{2,}").matcher(withWritingSpace)
                .replaceAll(java.util.regex.Matcher.quoteReplacement("\\rule{0.82\\linewidth}{0.4pt}"));
    }

    /** Converts an editor-owned CSS height marker to physical blank space after normal text escaping is complete. */
    private static String replaceHtmlWritingSpace(String latex) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("MATHAGENTHTMLSPACER(\\d+)").matcher(latex);
        StringBuffer result = new StringBuffer();
        while (matcher.find()) {
            int cssPixels = Integer.parseInt(matcher.group(1));
            double texPoints = cssPixels * TEX_POINTS_PER_INCH / CSS_PIXELS_PER_INCH;
            matcher.appendReplacement(result, java.util.regex.Matcher.quoteReplacement(
                    String.format(Locale.ROOT, "\\vspace{%.2fpt}", texPoints)));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    /**
     * Applies the shared formula normalizer, then enforces the export-only mathematical contract with line-level
     * diagnostics.  The model must write a fraction as {@code \\frac{numerator}{denominator}} and a radical as
     * {@code \\sqrt{radicand}} inside math delimiters; no visual fallback can preserve the intended meaning.
     */
    private static String normalizeAndValidateHandoutMath(String markdown) {
        String transportNormalized = (markdown == null ? "" : markdown)
                .replace('＝', '=')
                .replace('／', '/');
        String[] sourceLines = transportNormalized.replace("\r\n", "\n").split("\n", -1);
        String[] lines = new String[sourceLines.length];
        for (int lineIndex = 0; lineIndex < sourceLines.length; lineIndex += 1) {
            String sourceLine = sourceLines[lineIndex];
            // Headings are document labels, not formula prose.  Passing “Teacher Draft” to a heuristic formula
            // recognizer can mistake Markdown punctuation for subtraction; preserve it verbatim and sanitize body.
            lines[lineIndex] = sourceLine.startsWith("#") || isMarkdownUriLine(sourceLine)
                    ? sourceLine
                    : FormulaMarkupSanitizer.sanitizeFeishuMath(sourceLine);
        }
        String normalized = String.join("\n", lines);
        for (int lineIndex = 0; lineIndex < lines.length; lineIndex += 1) {
            validateMathLine(lines[lineIndex], lineIndex + 1);
        }
        return normalized;
    }

    /** Markdown image/link targets are transport paths, never mathematical slash fractions. */
    private static boolean isMarkdownUriLine(String line) {
        return line != null && line.contains("](") && line.endsWith(")");
    }

    /**
     * Parses Markdown math delimiters before XeLaTeX sees the content.  A matching delimiter is required: treating
     * every dollar sign as a simple toggle previously let mixed {@code $}/ {@code $$} model output move a root or
     * fraction into text mode, which visually destroys its mathematical structure.
     */
    private static void validateMathLine(String line, int lineNumber) {
        if (line.indexOf('√') >= 0) {
            throw mathExportError("AMBIGUOUS_RADICAL", lineNumber, line);
        }
        String openedDelimiter = "";
        for (int index = 0; index < line.length();) {
            boolean escapedDollar = index > 0 && line.charAt(index - 1) == '\\';
            String delimiter = !escapedDollar && line.startsWith("$$", index) ? "$$"
                    : !escapedDollar && line.charAt(index) == '$' ? "$" : "";
            if (!delimiter.isEmpty()) {
                if (openedDelimiter.isEmpty()) {
                    openedDelimiter = delimiter;
                } else if (!openedDelimiter.equals(delimiter)) {
                    throw mathExportError("MIXED_MATH_DELIMITER", lineNumber, line);
                } else {
                    openedDelimiter = "";
                }
                index += delimiter.length();
                continue;
            }
            if (!openedDelimiter.isEmpty()) {
                if (line.charAt(index) == '/') {
                    throw mathExportError("BARE_MATH_SLASH", lineNumber, line);
                }
                if (line.startsWith("\\sqrt", index)
                        && (index + "\\sqrt".length() >= line.length()
                        || line.charAt(index + "\\sqrt".length()) != '{')) {
                    throw mathExportError("NON_CANONICAL_RADICAL", lineNumber, line);
                }
            } else if (line.charAt(index) == '\\'
                    && (line.startsWith("\\frac", index) || line.startsWith("\\sqrt", index))) {
                throw mathExportError("RAW_LATEX_IN_TEXT", lineNumber, line);
            }
            index += 1;
        }
        if (!openedDelimiter.isEmpty()) {
            throw mathExportError("UNCLOSED_MATH_DELIMITER", lineNumber, line);
        }
    }

    /** Keeps an export rejection actionable in the persisted worker trace and the MCP response. */
    private static IllegalArgumentException mathExportError(String code, int lineNumber, String line) {
        String excerpt = line.length() <= 160 ? line : line.substring(0, 160) + "...";
        return new IllegalArgumentException("数学排版门禁 " + code + "，第 " + lineNumber + " 行：" + excerpt);
    }

    /** Selects the audience-owned stage artifact instead of mixing teacher answers into every export. */
    private static String variantMarkdown(MultiAgentWritingArtifact artifact, HandoutVariant variant) {
        if (variant == HandoutVariant.FINAL) {
            return finalHandoutMarkdown(artifact);
        }
        String selected = "";
        for (MultiAgentWritingArtifact.StructuredSection section : artifact.sections()) {
            if (variant.sectionCode().equals(section.sectionCode()) && !section.content().isBlank()) {
                selected = section.content().strip();
            }
        }
        if (!selected.isBlank()) {
            if (variant == HandoutVariant.LECTURE) {
                return singleQuestionLectureProjection(selected, teacherDraftMarkdown(artifact));
            }
            String safe = variant.requiresAnswerFreeProjection() ? stripRestrictedSections(selected) : selected;
            if (variant == HandoutVariant.TEACHER) {
                return appendReadableEvidenceAttribution(safe, artifact.mergedMarkdown());
            }
            return variant == HandoutVariant.STUDENT ? normalizeStudentWritingSpace(safe) : safe;
        }
        if (variant == HandoutVariant.TEACHER) {
            return finalHandoutMarkdown(artifact);
        }
        throw new IllegalStateException("讲义缺少" + variant.displayName() + "正文，拒绝用教师版内容代替");
    }

    /** Keeps a retrieved human-readable source title visible even when a writer omitted it from its prose. */
    private static String appendReadableEvidenceAttribution(String teacherMarkdown, String mergedMarkdown) {
        String source = safeText(mergedMarkdown);
        if (teacherMarkdown.contains(SHARED_ROOT_SOURCE_TITLE)) return teacherMarkdown;
        // The shared-root corpus is backend configuration, while the artifact intentionally stores only bounded
        // writer output. Print its readable title here so a source survives model omission without exposing IDs.
        return teacherMarkdown.strip() + "\n\n## 资料依据\n\n资料依据：" + SHARED_ROOT_SOURCE_TITLE
                + "（飞书共享资料，已用于本讲义证据整理）。\n";
    }

    /**
     * Full-width underscore rows mean a free-response work area, not a fill-in blank.  Convert only standalone rows
     * to vertical whitespace; inline/table lines remain short answer blanks by design.
     */
    private static String normalizeStudentWritingSpace(String markdown) {
        return markdown.replaceAll("(?m)^\\s*_{3,}\\s*$", "\n\n\n");
    }

    /** Uses each Agent-authored H1 as the variant identity while retaining a deterministic fallback. */
    private static String variantTitle(MultiAgentWritingArtifact artifact, String content, HandoutVariant variant) {
        String[] lines = content.replace("\r\n", "\n").split("\n");
        for (int index = 0; index < lines.length; index += 1) {
            String rawLine = lines[index];
            String line = rawLine.strip();
            if (line.startsWith("# ")) {
                String title = line.substring(2).replaceAll("[\\p{Cntrl}\\r\\n]+", " ").strip();
                // Compatibility output from older Luna workflows uses JSON field names as headings and puts the
                // model-written title on the following line. Do not name a teacher download literally "title".
                if (isGenericTitleLabel(title)) {
                    for (int next = index + 1; next < lines.length; next += 1) {
                        String candidate = lines[next].strip();
                        if (candidate.isBlank()) continue;
                        if (!candidate.startsWith("#") && candidate.codePointCount(0, candidate.length()) <= 80) {
                            return candidate;
                        }
                        break;
                    }
                }
                if (!title.isBlank() && !isGenericTitleLabel(title) && title.codePointCount(0, title.length()) <= 80) {
                    return title;
                }
            }
        }
        return handoutTitle(artifact) + "（" + variant.displayName() + "）";
    }

    /** Prevents a reviewed teacher solution from accidentally being published as a blank student handout. */
    private static void enforceAudienceBoundary(String content, HandoutVariant variant) {
        if (variant != HandoutVariant.STUDENT && variant != HandoutVariant.LECTURE) {
            return;
        }
        String compact = content.replaceAll("\\s+", "");
        for (String blocked : List.of("答案：", "答案:", "答案依次", "参考答案", "最终答案", "完整解答", "完整解析", "评分点：", "评分标准：", "教师提示：",
                "answer:", "solution:", "worked solution", "scoring rubric", "teacher note")) {
            if (compact.contains(blocked.replaceAll("\\s+", ""))) {
                throw new IllegalStateException(variant.displayName() + "检测到答案或教师信息，拒绝导出：" + blocked);
            }
        }
    }

    /**
     * Projects a historical rich draft into an answer-free classroom view. The source remains an actual Luna output;
     * only sections explicitly labelled as solutions/answers/rubrics are removed before the final boundary check.
     */
    private static String stripRestrictedSections(String markdown) {
        StringBuilder safe = new StringBuilder();
        int skippedHeadingDepth = 0;
        for (String rawLine : markdown.replace("\r\n", "\n").split("\n", -1)) {
            String line = rawLine.strip();
            int headingDepth = markdownHeadingDepth(line);
            if (headingDepth > 0) {
                if (skippedHeadingDepth > 0 && headingDepth <= skippedHeadingDepth) {
                    skippedHeadingDepth = 0;
                }
                String label = line.substring(headingDepth).strip().toLowerCase(Locale.ROOT);
                if (isRestrictedAudienceLabel(label)) {
                    skippedHeadingDepth = headingDepth;
                    continue;
                }
            }
            if (skippedHeadingDepth == 0) {
                safe.append(rawLine).append('\n');
            }
        }
        return safe.toString().strip();
    }

    /**
     * Converts a legacy multi-card lecture into one answer-free 16:10 teaching prompt. Older Luna runs produced a
     * whole lesson deck; selecting the first real "已知" example preserves source-grounded content while preventing
     * worked calculations and answer cards from becoming a supposedly blank single-question projection.
     */
    private static String singleQuestionLectureProjection(String markdown, String teacherDraft) {
        List<String> formulaClues = new ArrayList<>();
        // The teacher writer is the authoritative owner of original questions.  A lecture draft often begins with
        // a method hint such as “已知三边时…”, which is not a question and must never become the 16:10 stem.
        String firstProblem = firstTeacherProblem(teacherDraft);
        for (String rawLine : markdown.replace("\r\n", "\n").split("\n")) {
            String line = rawLine.strip();
            if (firstProblem.isBlank() && isLectureQuestionStem(line)) {
                firstProblem = line.substring(2).strip();
            }
            if (firstProblem.isBlank() && (line.startsWith("题目：") || line.startsWith("题目:"))) {
                firstProblem = line.substring(3).strip();
            }
            if (line.startsWith("- ") && formulaClues.size() < 3
                    && (line.contains("定理") || line.contains("公式") || line.contains("cos") || line.contains("sin"))) {
                formulaClues.add(line.substring(2).strip());
            }
        }
        if (firstProblem.isBlank()) {
            // A generic template looks harmless but violates the single-question product promise.  Refuse it so the
            // workflow trace exposes the faulty lecture-writer output and a retry can use actual retrieved evidence.
            throw new IllegalStateException("16:10 单题版缺少可验证的真实题干，拒绝导出占位课堂稿");
        }
        String title = variantTitlePlaceholder(markdown, teacherDraft);
        StringBuilder projection = new StringBuilder("# ").append(title).append("（16:10 单题课堂引导）\n\n")
                // The prompt is the first classroom body block.  A projection cannot make students hunt through
                // objectives before they know which single question they are about to solve.
                .append("## 题目\n\n").append(firstProblem).append("\n\n")
                .append("## 题型识别\n\n先判断本题的目标、条件和可用知识。\n\n")
                .append("## 先确定目标\n\n本题要求的量是什么？它需要哪一个公式或定理作桥梁？________________\n\n")
                .append("## 再整理已知条件\n\n已知量：________________\n\n隐藏条件或角边对应关系：________________\n\n")
                .append("## 方法选择\n\n从下列与本题匹配的知识中选择，并说明理由：\n");
        for (String clue : formulaClues) {
            projection.append("- ").append(clue).append("\n");
        }
        projection.append("\n选择理由：________________\n\n<wait>\n\n")
                .append("## 板书推导\n\n第一步：________________\n\n第二步：________________\n\n结论先不公布，请完成检验后再讨论。\n");
        return projection.toString();
    }

    /** A bullet is a usable projection stem only when it asks for a concrete result, never when it merely describes a method. */
    private static boolean isLectureQuestionStem(String line) {
        if (!line.startsWith("- 已知")) return false;
        String candidate = line.substring(2).strip();
        return candidate.contains("求") || candidate.contains("证明") || candidate.contains("判断");
    }

    /** Reuses the workflow's teacher-authored first problem when the parallel projection writer omitted its stem. */
    private static String teacherDraftMarkdown(MultiAgentWritingArtifact artifact) {
        for (MultiAgentWritingArtifact.StructuredSection section : artifact.sections()) {
            if ("teacher-explanation".equals(section.sectionCode())) return section.content();
        }
        return "";
    }

    private static String firstTeacherProblem(String teacherDraft) {
        String[] lines = safeText(teacherDraft).replace("\r\n", "\n").split("\n");
        for (int index = 0; index < lines.length; index += 1) {
            String heading = lines[index].strip();
            int headingDepth = markdownHeadingDepth(heading);
            if (headingDepth == 0) continue;
            String label = heading.substring(headingDepth).strip();
            // Writers may use ##/### and Chinese numerals alike (问题一、问题1).  Match the semantic heading rather
            // than one exact Markdown depth so a genuine teacher original question is always available to 16:10.
            if (!label.startsWith("问题") && !label.startsWith("原题")) continue;
            for (int next = index + 1; next < lines.length; next += 1) {
                String candidate = lines[next].strip();
                if (candidate.isBlank()) continue;
                if (candidate.startsWith("教材依据：") || candidate.startsWith("资料依据：") || candidate.startsWith("#")) break;
                return label + "：" + candidate;
            }
        }
        return "";
    }

    /** Reads the model-written title from both normal Markdown and the older JSON-to-Markdown compatibility form. */
    private static String variantTitlePlaceholder(String markdown, String teacherDraft) {
        String[] lines = markdown.replace("\r\n", "\n").split("\n");
        // A structured Luna response can start with a generic container heading (for example "课堂卡片")
        // before its actual lesson title. Prefer the first explicit title value so the projection and file name
        // remain tied to the real lesson instead of a renderer placeholder.
        for (int index = 0; index + 1 < lines.length; index += 1) {
            String heading = lines[index].strip();
            String candidate = lines[index + 1].strip();
            if (heading.equalsIgnoreCase("# title") && !candidate.isBlank() && !candidate.startsWith("#")) {
                return candidate;
            }
        }
        for (int index = 0; index < lines.length; index += 1) {
            String line = lines[index].strip();
            if (!line.startsWith("# ")) continue;
            String heading = line.substring(2).strip();
            if (!isGenericTitleLabel(heading)) {
                return heading;
            }
            if (!isGenericTitleLabel(heading)) continue;
            for (int next = index + 1; next < lines.length; next += 1) {
                String candidate = lines[next].strip();
                if (candidate.isBlank()) continue;
                if (!candidate.startsWith("#")) return candidate;
                break;
            }
        }
        String teacherTitle = firstMarkdownTitle(teacherDraft);
        return teacherTitle.isBlank() ? "单题课堂引导" : teacherTitle;
    }

    /** The wrapper owns the title page; remove the same leading Markdown H1 to avoid a visually duplicated title. */
    private static String withoutLeadingDocumentTitle(String content, String title) {
        String normalized = content.replace("\r\n", "\n");
        String trimmed = normalized.stripLeading();
        // The LaTeX wrapper is the sole title renderer. Historical JSON compatibility content sometimes has a
        // different first H1 than the resolved file title, so remove the first H1 rather than only an exact match.
        if (!trimmed.startsWith("# ")) {
            return normalized;
        }
        int leadingLength = normalized.length() - trimmed.length();
        int afterHeading = normalized.indexOf('\n', leadingLength);
        if (afterHeading < 0) return "";
        return normalized.substring(afterHeading + 1).stripLeading();
    }

    private static int markdownHeadingDepth(String value) {
        int depth = 0;
        while (depth < value.length() && value.charAt(depth) == '#') depth += 1;
        return depth > 0 && depth < value.length() && value.charAt(depth) == ' ' ? depth : 0;
    }

    private static boolean isRestrictedAudienceLabel(String label) {
        return label.contains("答案") || label.contains("解答") || label.contains("解析") || label.contains("评分")
                || label.contains("教师") || label.equals("answer") || label.equals("solution")
                || label.contains("review notes") || label.contains("scoring") || label.contains("teacher note");
    }

    private static boolean isGenericTitleLabel(String value) {
        String normalized = value == null ? "" : value.strip().toLowerCase(Locale.ROOT);
        return normalized.equals("title") || normalized.equals("标题") || normalized.equals("document title")
                // These are JSON compatibility field names, never classroom-facing titles.
                || normalized.equals("h1") || normalized.equals("format") || normalized.equals("stage")
                || normalized.equals("difficulty") || normalized.equals("lecturecards");
    }

    /** Sanitizes short layout labels before they are escaped into the renderer-owned LaTeX wrapper. */
    private static String normalizeLayoutLabel(String value) {
        String normalized = safeText(value).replaceAll("[\\p{Cntrl}]", "").replaceAll("\\s+", " ").strip();
        if (normalized.codePointCount(0, normalized.length()) <= MAX_LAYOUT_LABEL_CODE_POINTS) {
            return normalized;
        }
        int end = normalized.offsetByCodePoints(0, MAX_LAYOUT_LABEL_CODE_POINTS);
        return normalized.substring(0, end).strip();
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
        // The latency-optimized workflow deliberately removed a separate merge-model call. Its reviewed teacher
        // writer is therefore the authoritative final teacher body, while student and 16:10 remain separate sections.
        // Falling back only to this audience-owned section preserves the no-trace export guarantee.
        for (MultiAgentWritingArtifact.StructuredSection section : artifact.sections()) {
            if ("teacher-explanation".equals(section.sectionCode()) && !section.content().isBlank()) {
                return section.content().strip();
            }
        }
        throw new IllegalStateException("讲义缺少最终审校正文，拒绝导出 Agent 过程日志");
    }

    /**
     * Uses Luna's first Markdown H1 as the human-facing document identity.  This ties every export format to the
     * actual lesson topic instead of leaking an opaque workflow UUID into a teacher's downloaded file name.
     */
    private static String handoutTitle(MultiAgentWritingArtifact artifact) {
        // Resource-curation content is persisted before the publishable drafts. Prefer the teacher section so its
        // H1 becomes the download name/header instead of leaking an internal stage label such as “Resource Curation”.
        for (MultiAgentWritingArtifact.StructuredSection section : artifact.sections()) {
            if ("teacher-explanation".equals(section.sectionCode())) {
                String teacherTitle = firstMarkdownTitle(section.content());
                if (!teacherTitle.isBlank()) {
                    return teacherTitle;
                }
            }
        }
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

    /** Reads only a concise Markdown H1, never a raw model/system label. */
    private static String firstMarkdownTitle(String content) {
        for (String rawLine : safeText(content).replace("\r\n", "\n").split("\n")) {
            String line = rawLine.strip();
            if (line.startsWith("# ")) {
                String title = line.substring(2).replaceAll("[\\p{Cntrl}\\r\\n]+", " ").strip();
                if (!title.isBlank() && title.codePointCount(0, title.length()) <= 80) {
                    return title;
                }
            }
        }
        return "";
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
                // Model Markdown can place cosmetic blank rows inside \[...\].  XeLaTeX reports a misleading
                // "Missing $" at that empty row; omit it while retaining the surrounding mathematical expression.
                if (line.isBlank()) {
                    continue;
                }
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
            String listPayload = line.startsWith("- ") || line.startsWith("* ") ? line.substring(2).strip() : line;
            if (listPayload.startsWith("![") && listPayload.contains("](")) {
                // Asset routes are authorization-scoped runtime URIs, not classroom prose.  Do not print local
                // traversal-looking paths into the PDF; a future image embedder may resolve the same safe asset.
                latex.append("\\emph{资料图片（已关联至原始资料）}\\par\n");
                continue;
            }
            if (line.startsWith("#### ")) {
                if (inItemize) {
                    latex.append("\\end{itemize}\n");
                    inItemize = false;
                }
                latex.append("\\paragraph{").append(escapeLatexTextPreservingMath(line.substring(5).strip())).append("}\n");
            } else if (line.startsWith("### ")) {
                if (inItemize) {
                    latex.append("\\end{itemize}\n");
                    inItemize = false;
                }
                latex.append("\\subsubsection*{").append(escapeLatexTextPreservingMath(line.substring(4).strip())).append("}\n");
            } else if (line.startsWith("## ")) {
                if (inItemize) {
                    latex.append("\\end{itemize}\n");
                    inItemize = false;
                }
                latex.append("\\subsection*{").append(escapeLatexTextPreservingMath(line.substring(3).strip())).append("}\n");
            } else if (line.startsWith("# ")) {
                if (inItemize) {
                    latex.append("\\end{itemize}\n");
                    inItemize = false;
                }
                latex.append("\\section*{").append(escapeLatexTextPreservingMath(line.substring(2).strip())).append("}\n");
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
        // Mark a compact fill-in blank before escaping text. The final document replaces this neutral marker with a
        // real rule; inserting a LaTeX command here would escape its backslashes and print it literally.
        value = java.util.regex.Pattern.compile("_{3,}").matcher(value)
                .replaceAll(java.util.regex.Matcher.quoteReplacement("MATHAGENTFILLBLANKRULE"));
        // Markdown uses \( ... \) for inline math. A literal replacement is deterministic and avoids the
        // double-escaping ambiguity of a Java regular expression around LaTeX backslashes.
        value = value.replace("\\(", "$").replace("\\)", "$").replace("**", "");
        // Rich-text providers encode a vector arrow as U+20D7 after Latin letters. Noto CJK does not provide a
        // reliable glyph for that combining mark; turning it into real LaTeX avoids the visible square boxes in PDF.
        value = value.replaceAll("([A-Za-z]{1,3})\\u20D7", "\\$\\\\vec{$1}\\$");
        // Some rich-text editors place the combining arrow before rather than after the Latin identifier. Handle
        // both canonical orders; otherwise XeLaTeX receives a standalone combining glyph and renders it as a box.
        value = value.replaceAll("\\u20D7\\s*([A-Za-z]{1,3})", "\\$\\\\vec{$1}\\$");
        // Unicode subscripts (x₁, y₂) are absent from the selected CJK text font. Promote them to LaTeX math
        // identifiers before escaping so coordinate notation remains legible in every exported variant.
        value = value.replaceAll("([A-Za-z])\\u2081", "\\$$1_1\\$");
        value = value.replaceAll("([A-Za-z])\\u2082", "\\$$1_2\\$");
        // Classroom pause markers and HTML spacer tags are prompt/editor transport syntax. They must become
        // printable whitespace, never leak verbatim into either audience's PDF.
        value = value.replace("<wait>", "").replace("</wait>", "")
                // Rich-text line breaks are transport markup.  Preserve their intended writing space without
                // printing literal HTML into the student handout.
                .replaceAll("(?i)<br\\s*/?>", "\n")
                // A model may choose single quotes or reorder harmless CSS spacing.  It is an editor-only spacer,
                // so turn every height-only div into printable worksheet whitespace rather than leaking HTML.
                .replaceAll("(?i)<div\\s+style\\s*=\\s*(['\\\"])\\s*height\\s*:\\s*[0-9]+em\\s*;?\\s*\\1\\s*></div>", "\n\n\n")
                // Keep a pixel-specified free-response area through the escaping pass.  The document builder turns
                // this neutral marker into \vspace after escaping, so it cannot be printed as raw HTML or mistaken
                // for a fill-in rule.
                .replaceAll("(?is)<div\\b[^>]*?height\\s*:\\s*(\\d+)px[^>]*>\\s*</div>", "MATHAGENTHTMLSPACER$1")
                // LLM rich-text output is not a stable HTML dialect: it may use typographic quotes, CSS pixels or
                // decorative borders.  Every empty div still means “leave room for handwriting”, never printable
                // content, so remove the complete transport construct instead of matching one fragile style form.
                .replaceAll("(?is)<div\\b[^>]*>\\s*</div>", "\n\n\n");
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
            case "pdf-teacher", "teacher-pdf" -> "pdf-teacher";
            case "pdf-student", "student-pdf" -> "pdf-student";
            case "pdf-lecture", "lecture-pdf", "pdf-16-10" -> "pdf-lecture";
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

    /** Audience and layout metadata for one separately publishable handout. */
    private enum HandoutVariant {
        FINAL("final-handout", "综合审校版", "", "数学讲义"),
        TEACHER("teacher-explanation", "教师讲义", "（教师版）", "教师备课与课堂讲评"),
        STUDENT("student-worksheet", "学生空白讲义", "（学生空白版）", "姓名：________  班级：________"),
        LECTURE("lecture-cards", "16:10 单题引导", "（16比10单题版）", "课堂投影 · 思考后再继续");

        private final String sectionCode;
        private final String displayName;
        private final String fileSuffix;
        private final String defaultFooter;

        HandoutVariant(String sectionCode, String displayName, String fileSuffix, String defaultFooter) {
            this.sectionCode = sectionCode;
            this.displayName = displayName;
            this.fileSuffix = fileSuffix;
            this.defaultFooter = defaultFooter;
        }

        String sectionCode() { return sectionCode; }
        String displayName() { return displayName; }
        String fileSuffix() { return fileSuffix; }
        String defaultFooter() { return defaultFooter; }
        boolean requiresAnswerFreeProjection() { return this == STUDENT || this == LECTURE; }
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
