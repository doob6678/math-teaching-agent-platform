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

/**
 * Renders teaching handouts into readable PDF files for protected preview and download.
 */
@Service
public class TeachingHandoutPdfExportService {

    public record RenderedHandoutPdf(byte[] bytes, String renderer, int pageCount) {
    }

    static final Logger LOGGER = LoggerFactory.getLogger(TeachingHandoutPdfExportService.class);
    static final Pattern SECTION_COMMAND = Pattern.compile("^\\\\(?:section|subsection|subsubsection|paragraph)\\*?\\{(.+)}\\s*$");
    static final Pattern LATEX_HEADING_LINE = Pattern.compile("^\\\\(section\\*?|subsection\\*?|subsubsection\\*?|paragraph\\*?)\\{(.+)}\\s*$");
    static final Pattern MARKDOWN_HEADING = Pattern.compile("^#{1,6}\\s+(.+)$");
    static final Pattern WRAPPED_TEXT_COMMAND = Pattern.compile("\\\\(?:textbf|textit|emph|text|mathrm)\\{([^{}]*)}");
    static final Pattern FRAC_COMMAND = Pattern.compile("\\\\frac\\{([^{}]+)}\\{([^{}]+)}");
    /** Repairs model output such as y=\\pm($\\frac{b}{a}$)x before XeLaTeX sees mismatched math delimiters. */
    static final Pattern MIXED_PLUS_MINUS_FRACTION = Pattern.compile(
            "(?<![$A-Za-z0-9_])([A-Za-z][A-Za-z0-9_{}^]*)\\s*=\\s*\\\\pm\\s*\\(\\$([^$]+)\\$\\)([A-Za-z0-9])");
    static final Pattern UNDELIMITED_PLUS_MINUS_FRACTION = Pattern.compile(
            "(?<![$A-Za-z0-9_])([A-Za-z][A-Za-z0-9_{}^]*)\\s*=\\s*\\\\pm\\s*\\((\\\\frac\\{[^{}]+}\\{[^{}]+})\\)([A-Za-z0-9])");
    /** Repairs a persisted OCR formula whose squared numerators were split across incompatible dollar runs. */
    static final Pattern SPLIT_QUADRATIC_FRACTION = Pattern.compile(
            "([A-Za-z])\\\\textasciicircum\\{\\}\\$\\\\frac\\{([^{}]+)}\\{([^{}]+)}([+-])([A-Za-z])\\$"
                    + "\\\\textasciicircum\\{\\}\\$\\\\frac\\{([^{}]+)}\\{([^{}]+)}=([0-9]+)\\$");
    /** A remaining exponent split into prose plus one math island represents one mathematical atom. */
    static final Pattern SPLIT_EXPONENT_MATH = Pattern.compile(
            "([A-Za-z])\\\\textasciicircum\\{\\}\\$([^$]+)\\$");
    /** Restores an OCR-escaped multiplication as one inline mathematical expression. */
    static final Pattern BARE_TIMES = Pattern.compile(
            "(?<![$A-Za-z0-9])([0-9]+)\\\\times\\s*([0-9]+)(?![$A-Za-z0-9])");
    static final Pattern SQRT_COMMAND = Pattern.compile("\\\\sqrt\\{([^{}]+)}");
    static final Pattern SUPERSCRIPT_BRACED = Pattern.compile("\\^\\{([^{}]+)}");
    static final Pattern SUPERSCRIPT_SIMPLE = Pattern.compile("\\^([0-9a-zA-Z+-])");
    static final Pattern SUBSCRIPT_BRACED = Pattern.compile("_\\{([^{}]+)}");
    static final Pattern SUBSCRIPT_SIMPLE = Pattern.compile("_([0-9a-zA-Z+-])");
    /** Bare OCR variables such as x^2 must enter math mode before ordinary TeX escaping turns ^ into visible text. */
    static final Pattern BARE_MATH_ATOM = Pattern.compile(
            "(?<![$A-Za-z0-9])([A-Za-z](?:\\^\\{?[A-Za-z0-9]+\\}?|_\\{?[A-Za-z0-9]+\\}?))(?![$A-Za-z0-9])");
    /** Repairs model output that closes math immediately after a function, e.g. {@code $\tan$ C\left(...\right)}. */
    static final Pattern SPLIT_FUNCTION_ARGUMENT = Pattern.compile(
            "\\$(\\\\(?:sin|cos|tan|cot|sec|csc|ln|log|exp))\\$\\s*"
                    + "([A-Za-z](?:_\\{[^}]+}|_[A-Za-z0-9])?)\\s*"
                    + "(\\\\left\\([^$\\n]+?\\\\right\\))");
    /** Repairs a model response that split one inline formula across three dollar-delimiter runs. */
    static final Pattern SPLIT_TRIPLE_DOLLAR_MATH = Pattern.compile(
            "\\$\\$\\$([^$\\n]*)\\$\\$([^$\\n]*)\\$");
    /** Internal retrieval identifiers are audit metadata and must never be printed as lesson mathematics. */
    static final Pattern INTERNAL_EVIDENCE_IDENTIFIER_CLAUSE = Pattern.compile(
            "(?:，|；)?\\s*(?:[\\p{IsHan}]{0,8})?证据(?:编号|锚点|ID|id)(?:为|：)?\\s*"
                    + "\\$?[A-Za-z0-9][A-Za-z0-9_-]*\\$?"
                    + "(?:\\s*[、,，]\\s*\\$?[A-Za-z0-9][A-Za-z0-9_-]*\\$?)*\\s*[；;。]?");
    static final Pattern VSPACE_COMMAND = Pattern.compile("\\\\vspace\\{([0-9.]+)em}");
    static final Pattern UNDERLINE_HSPACE_COMMAND = Pattern.compile("\\\\underline\\{\\\\hspace\\{[0-9.]+em}}");
    static final Pattern VISIBLE_WORKSPACE_LABEL = Pattern.compile(
            "(?:课堂作答区|作答区|我的解答|推导区|手写区|教师手写区|留白区|空白区|板书区|教师板书区)\\s*[：:]?");
    static final Pattern VISIBLE_WORKSPACE_REFERENCE = Pattern.compile(
            "(?:写在|填写在|完成在|放在|留在)(?:课堂作答区|作答区|我的解答|推导区|手写区|教师手写区|留白区|空白区|板书区|教师板书区)");
    static final Pattern MARKDOWN_IMAGE = Pattern.compile("!\\[([^\\]]*)]\\(([^)]+)\\)");
    /** Historical OCR task snapshots may contain source-book branding before the actual stem. */
    static final Pattern HISTORICAL_SOURCE_BANNER = Pattern.compile(
            "^(?:(?:赵礼显数学|飞猪数学)\\s*)?(?:作业|讲义|课堂练习)\\s*\\d+\\s*[.．、:：]?\\s*");
    /** A bare historical product label is equally not printable handout content. */
    static final Pattern HISTORICAL_SOURCE_BRAND = Pattern.compile("^(?:赵礼显数学|飞猪数学)\\s*");
    /** A branded legacy snapshot must be repaired from its source, rather than cosmetically hidden during export. */
    static final Pattern LEGACY_BRAND_REFERENCE = Pattern.compile("(?:赵礼显数学|飞猪数学)");
    /** OCR squares and replacement characters are unknown mathematical relations, never printable question blanks. */
    static final Pattern UNRESOLVED_OCR_MATH_GLYPH = Pattern.compile("[□�]");
    /** A prompt that refers to a figure is incomplete until an authorized local figure marker survives sanitization. */
    static final Pattern FIGURE_DEPENDENT_PROMPT = Pattern.compile("(?:如图|见图|下图|上图|图中)");
    /** Accept standard and URL-safe base64 so persisted markers remain structural across older workers. */
    static final Pattern IMAGE_MARKER = Pattern.compile("\\[\\[HANDOUTIMAGE:([^:\\]]+):([^\\]]+)]]");
    /** Every numbered question is a publication unit: visual evidence and duplicate checks must never cross it. */
    static final Pattern NUMBERED_QUESTION_HEADING = Pattern.compile(
            "^\\\\(?:subsection|section)\\*?\\{第\\s*\\d+\\s*题[^}]*}\\s*$");
    /** Teacher answers are required to contain a concrete, source-grounded resolution rather than a fallback notice. */
    static final Pattern TEACHER_ANSWER_PARAGRAPH = Pattern.compile("^\\\\paragraph\\*?\\{答案与评分点}\\s*$");
    /** The generic fallback is deliberately blocked at publication time because it is not a worked explanation. */
    static final Pattern UNVERIFIED_TEACHER_ANSWER = Pattern.compile("(?:题库未提供可核验答案|需教师补充后使用)");
    /** Non-empty marker payload required by the legacy image transport grammar; it is never printable caption text. */
    static final String INLINE_FIGURE_TRANSPORT_ALT = "__inline_figure__";
    static final Duration LATEX_TIMEOUT = Duration.ofSeconds(45);
    static final float PAGE_WIDTH = PDRectangle.A4.getWidth();
    static final float PAGE_HEIGHT = PDRectangle.A4.getHeight();
    /** The scanned Zhao master is 582 by 812 points, rather than ISO A4. */
    static final float ZHAO_PAGE_WIDTH = 582f;
    static final float ZHAO_PAGE_HEIGHT = 812f;
    /** Measured from the question-text grid in the scanned master pages p10, p100, and p218. */
    static final float ZHAO_CONTENT_MARGIN = 72f;
    /** Dominant dark-blue ink sampled from the original title bars and overlapping-square mark. */
    static final Color ZHAO_NAVY = new Color(44, 57, 135);
    /** Orange outline/accent sampled from the original logo and the Zhao badge lettering. */
    static final Color ZHAO_ORANGE = new Color(240, 134, 48);
    static final float LECTURE_PAGE_WIDTH = 800f;
    static final float LECTURE_PAGE_HEIGHT = 500f;
    static final float MARGIN = 54;
    static final float LECTURE_MARGIN = 34;
    static final float TITLE_FONT_SIZE = 18;
    static final float HEADING_FONT_SIZE = 12.8f;
    static final float BODY_FONT_SIZE = 10.8f;
    static final float IMAGE_CAPTION_SIZE = 8.6f;
    static final float LEADING = 19;
    static final int WRAP_UNITS = 68;
    /** Compact projection typography reserves space for a full real prompt, source figure, and three checked steps. */
    static final String LECTURE_BODY_COMMAND =
            "\\small\\setlength{\\parskip}{0.28em}"
                    + "\\setlist[enumerate]{leftmargin=1.45em,itemsep=0.18em,topsep=0.18em}";
    /** Printed Zhao pages separate consecutive numbered questions with a measured paragraph gap. */
    static final String PRINTED_QUESTION_GAP = "\\vspace{1.25em}";
    /** Authorized crop from the user-provided Zhao master; copied beside handout.tex for XeLaTeX. */
    static final String ZHAO_MASTER_HEADER_ASSET = "handout-assets-zhao-header.png";

    /**
     * Renders the task handout into a PDF byte array.
     *
     * @param task owned teaching task
     * @return PDF bytes beginning with the PDF header
     */
    public byte[] render(TeachingTaskResponse task) {
        return render(task, "teacher");
    }

    /**
     * Renders one specific handout version into a PDF byte array.
     *
     * @param task owned teaching task
     * @param version handout version code, such as teacher or student
     * @return PDF bytes beginning with the PDF header
     */
    public byte[] render(TeachingTaskResponse task, String version) {
        return renderDetailed(task, version).bytes();
    }

    /**
     * Renders one specific handout version and returns bytes plus rendering metadata for audit and frontend preview.
     */
    public RenderedHandoutPdf renderDetailed(TeachingTaskResponse task, String version) {
        Optional<byte[]> compiled = compileLatex(task, version);
        if (compiled.isPresent()) {
            return new RenderedHandoutPdf(compiled.get(), "xelatex", countPages(compiled.get()));
        }
        // Publishing a text-drawn fallback destroys fractions, radicals, superscripts, vectors and page semantics.
        // Formula rendering is therefore a hard product invariant, not a deployment flag: users select a visual
        // template, while every template is compiled by a real XeLaTeX engine or the export fails explicitly.
        throw new IllegalStateException("XeLaTeX 未能生成讲义 PDF；已阻止返回未渲染公式或错误页面");
    }

    /**
     * Renders a user-visible publication and rejects a polished-looking PDF that does not meet the required depth.
     * Internal layout tests may still call {@link #renderDetailed(TeachingTaskResponse, String)} to inspect drafts,
     * but every HTTP download, preview, and ZIP path must use this boundary.
     */
    public RenderedHandoutPdf renderForPublication(TeachingTaskResponse task, String version) {
        validatePublicationSource(task, version);
        RenderedHandoutPdf rendered = renderDetailed(task, version);
        int minimumPages = minimumQualifiedPages(task, version);
        if (minimumPages > 0 && rendered.pageCount() < minimumPages) {
            throw new IllegalStateException(version + " 版仅 " + rendered.pageCount() + " 页；合格讲义至少需要 "
                    + minimumPages + " 页，请补齐真实题库资料后重新生成。");
        }
        if ("lecture".equalsIgnoreCase(version)) {
            int questionCount = numberedQuestionCount(sanitizeLatexForExport(task.handoutLatexFor(version)));
            if (questionCount < 1 || rendered.pageCount() != questionCount) {
                throw new IllegalStateException("16:10 横版必须一题一页；当前识别到 " + questionCount
                        + " 道题、渲染为 " + rendered.pageCount() + " 页，请压缩单题内容或补齐题号后重新生成。");
            }
        }
        return rendered;
    }
    // Pure export rule delegated to TeachingHandoutPdfExportPolicyPartA; process/lifecycle state remains in the exporter facade.
    static void validatePublicationSource(TeachingTaskResponse task, String version) { TeachingHandoutPdfExportPolicyPartA.validatePublicationSource(task, version); }
    // Pure export rule delegated to TeachingHandoutPdfExportPolicyPartA; process/lifecycle state remains in the exporter facade.
    static void validateQuestionPublicationUnits(String sanitized, String version) { TeachingHandoutPdfExportPolicyPartA.validateQuestionPublicationUnits(sanitized, version); }
    // Pure export rule delegated to TeachingHandoutPdfExportPolicyPartA; process/lifecycle state remains in the exporter facade.
    static List<String> numberedQuestionUnits(String source) { return TeachingHandoutPdfExportPolicyPartA.numberedQuestionUnits(source); }
    // Pure export rule delegated to TeachingHandoutPdfExportPolicyPartA; process/lifecycle state remains in the exporter facade.
    static int numberedQuestionCount(String source) { return TeachingHandoutPdfExportPolicyPartA.numberedQuestionCount(source); }

    // Pure export rule delegated to TeachingHandoutPdfExportPolicyPartA; process/lifecycle state remains in the exporter facade.
    static boolean hasAuthorizedImage(String unit) { return TeachingHandoutPdfExportPolicyPartA.hasAuthorizedImage(unit); }
    // Pure export rule delegated to TeachingHandoutPdfExportPolicyPartA; process/lifecycle state remains in the exporter facade.
    static String questionFingerprint(String unit) { return TeachingHandoutPdfExportPolicyPartA.questionFingerprint(unit); }
    // Pure export rule delegated to TeachingHandoutPdfExportPolicyPartA; process/lifecycle state remains in the exporter facade.
    static boolean hasUnverifiedTeacherAnswer(String unit) { return TeachingHandoutPdfExportPolicyPartA.hasUnverifiedTeacherAnswer(unit); }
    // Pure export rule delegated to TeachingHandoutPdfExportPolicyPartA; process/lifecycle state remains in the exporter facade.
    static boolean hasSubstantiveTeacherSolution(String unit) { return TeachingHandoutPdfExportPolicyPartA.hasSubstantiveTeacherSolution(unit); }

    /**
     * Returns the publication floor for a qualified long-form handout.  Rendering remains available to internal
     * review tools, while the protected HTTP preview/export boundary rejects an undersized final deliverable.
     */
    public int minimumQualifiedPages(TeachingTaskResponse task, String version) {
        if (!PdfStyle.isZhaoLixianTemplate(templateNameForVersion(task, version))) {
            return 0;
        }
        return "teacher".equalsIgnoreCase(version) ? 6
                : "student".equalsIgnoreCase(version) ? 4
                : 0;
    }

    /**
     * Compiles the handout with a real XeLaTeX engine when available so math formulas are rendered by LaTeX.
     */
    Optional<byte[]> compileLatex(TeachingTaskResponse task, String version) {
        Optional<Path> engine = latexEnginePath();
        if (engine.isEmpty()) {
            return Optional.empty();
        }
        Path workDir = null;
        try {
            workDir = Files.createTempDirectory("math-agent-handout-");
            Path source = workDir.resolve("handout.tex");
            Path compilerOutput = workDir.resolve("xelatex.out");
            materializeBundledLatexAsset(workDir, ZHAO_MASTER_HEADER_ASSET);
            Files.writeString(source, fullLatexDocument(task, version));
            Process process = runXeLaTeX(engine.get(), workDir, source, compilerOutput);
            if (process == null) {
                LOGGER.warn("XeLaTeX timed out for teaching handout {}", task.taskId());
                return Optional.empty();
            }
            if (process.exitValue() == 0) {
                Process secondPass = runXeLaTeX(engine.get(), workDir, source, compilerOutput);
                if (secondPass == null) {
                    LOGGER.warn("XeLaTeX second pass timed out for teaching handout {}", task.taskId());
                    return Optional.empty();
                }
                process = secondPass;
            }
            Path pdf = workDir.resolve("handout.pdf");
            if (process.exitValue() == 0 && Files.isRegularFile(pdf)) {
                return Optional.of(Files.readAllBytes(pdf));
            }
            Path log = workDir.resolve("handout.log");
            String logTail = Files.isRegularFile(log) ? tail(Files.readString(log), 1200)
                    : Files.isRegularFile(compilerOutput) ? tail(Files.readString(compilerOutput), 1200) : "";
            LOGGER.warn("XeLaTeX failed for teaching handout {} with exit {}. {}", task.taskId(), process.exitValue(), logTail);
            // Preserve the exact .tex/.log pair after a real compiler failure.  Without it the finally block erased
            // the only evidence needed to repair a model-produced formula, forcing operators to guess from a short
            // logger tail and repeatedly fall back to PDFBox.
            workDir = null;
            return Optional.empty();
        } catch (IOException exception) {
            LOGGER.warn("XeLaTeX compilation unavailable for teaching handout {}", task.taskId(), exception);
            return Optional.empty();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        } finally {
            if (workDir != null) {
                deleteRecursively(workDir);
            }
        }
    }
    // Pure export rule delegated to TeachingHandoutPdfExportPolicyPartA; process/lifecycle state remains in the exporter facade.
    static void materializeBundledLatexAsset(Path workDir, String assetName) throws IOException { TeachingHandoutPdfExportPolicyPartA.materializeBundledLatexAsset(workDir, assetName); }
    // Pure export rule delegated to TeachingHandoutPdfExportPolicyPartA; process/lifecycle state remains in the exporter facade.
    static Process runXeLaTeX(Path engine, Path workDir, Path source, Path compilerOutput) throws IOException, InterruptedException { return TeachingHandoutPdfExportPolicyPartA.runXeLaTeX(engine, workDir, source, compilerOutput); }
    // Pure export rule delegated to TeachingHandoutPdfExportPolicyPartA; process/lifecycle state remains in the exporter facade.
    static Optional<Path> latexEnginePath() { return TeachingHandoutPdfExportPolicyPartA.latexEnginePath(); }
    // Pure export rule delegated to TeachingHandoutPdfExportPolicyPartA; process/lifecycle state remains in the exporter facade.
    static boolean containsStructuredSections(String source) { return TeachingHandoutPdfExportPolicyPartA.containsStructuredSections(source); }
    // Pure export rule delegated to TeachingHandoutPdfExportPolicyPartA; process/lifecycle state remains in the exporter facade.
    static String fullLatexDocument(TeachingTaskResponse task, String version) { return TeachingHandoutPdfExportPolicyPartA.fullLatexDocument(task, version); }
    // Pure export rule delegated to TeachingHandoutPdfExportPolicyPartA; process/lifecycle state remains in the exporter facade.
    static String insertLectureQuestionBreaks(String body) { return TeachingHandoutPdfExportPolicyPartA.insertLectureQuestionBreaks(body); }
    // Pure export rule delegated to TeachingHandoutPdfExportPolicyPartA; process/lifecycle state remains in the exporter facade.
    static String insertPrintedQuestionSpacing(String body) { return TeachingHandoutPdfExportPolicyPartA.insertPrintedQuestionSpacing(body); }
    // Pure export rule delegated to TeachingHandoutPdfExportPolicyPartA; process/lifecycle state remains in the exporter facade.
    static boolean endsWithPageBreak(StringBuilder text) { return TeachingHandoutPdfExportPolicyPartA.endsWithPageBreak(text); }

    /**
     * Produces the canonical LaTeX body used by preview, download, ZIP export, and PDF rendering.
     * This is a last-resort guard for old tasks or model output that still contains internal layout
     * instructions, OCR page fragments, or provider diagnostics while preserving real handout images.
     */
    public static String sanitizeLatexForExport(String source) {
        String normalized = repairMojibake(safeText(source))
                // JSON producers occasionally persist a literal backslash-n instead of a line break. XeLaTeX treats
                // the resulting \n token as an undefined command, so restore transport newlines before parsing.
                .replace("\\n", "\n")
                // JSON producers can interpret LaTeX commands as control characters (\b, \t, \f).
                // Repair those persisted legacy values before any line-level filtering or math normalization.
                .replace("\u0008oldsymbol", "\\boldsymbol")
                .replace("\u0009heta", "\\theta")
                .replace("\u000C rac", "\\frac")
                .replace("\u0008", "")
                .replace("\u0009", " ")
                .replace("\\textbackslash{}frac", "\\frac")
                .replace("\\textbackslash{}sqrt", "\\sqrt")
                .replace("\\textbackslash{}sin", "\\sin")
                .replace("\\textbackslash{}cos", "\\cos")
                .replace("\\textbackslash{}tan", "\\tan")
                .replace("\\textbackslash{}ln", "\\ln")
                .replace("\\textbackslash{}log", "\\log")
                .replace("\\textbackslash{}pi", "\\pi")
                .replace("\\textbackslash{}theta", "\\theta")
                .replace("\\textbackslash{}alpha", "\\alpha")
                .replace("\\textbackslash{}beta", "\\beta")
                .replace("\\textbackslash{}gamma", "\\gamma")
                .replace("\\textbackslash{}Delta", "\\Delta")
                .replace("\\textbackslash{}infty", "\\infty")
                .replace("\\textbackslash{}leq", "\\leq")
                .replace("\\textbackslash{}geq", "\\geq")
                .replace("\\textbackslash{}neq", "\\neq")
                .replace("\\textbackslash{}cdot", "\\cdot")
                .replace("\\textbackslash{}times", "\\times")
                .replace("\\textbackslash{}to", "\\to");
        // A structured response can accidentally serialize one inline formula as `$$$a$$b$` (for example
        // `$$$\\sin$$\\theta$`). XeLaTeX interprets the first two dollars as display math and aborts at the next
        // delimiter. Recover the single intended inline expression before line-level escaping; valid `$$...$$`
        // display formulas do not match this exact split shape and remain unchanged.
        normalized = normalizeTripleDollarMath(normalized);
        // A function, its argument symbol, and scalable parentheses are one mathematical expression. Leaving
        // \left outside dollar delimiters makes XeLaTeX abort with "Missing $ inserted" and previously triggered
        // the lossy text fallback. Normalize only this unambiguous grammar; malformed or incomplete math still fails.
        normalized = normalizeSplitFunctionArguments(normalized);
        // JSON decoders treat the `\\r` in an unescaped `\\rightarrow` as a carriage return.  After
        // newline normalization this appears as `\\item ightarrow` or `\\par ightarrow`; repair the
        // command before XeLaTeX parses the body so a valid arrow cannot trigger a PDFBox fallback.
        normalized = normalized
                .replaceAll("\\\\item\\s*ightarrow", "\\\\rightarrow")
                .replaceAll("\\\\par\\s*ightarrow", "\\\\rightarrow");
        // Source ids such as math_b_bixiu_4_p014_ai_001 are useful in traces, but printing them leaks workflow
        // internals and makes TeX interpret repeated underscores as an invalid nested subscript.
        normalized = INTERNAL_EVIDENCE_IDENTIFIER_CLAUSE.matcher(normalized).replaceAll("");
        List<String> lines = new ArrayList<>();
        boolean inEvidenceSection = false;
        boolean skippingTextbookBody = false;
        boolean skippingLegacyMetadataSection = false;
        boolean skippingBlankWorkspaceSection = false;
        int evidenceLineCount = 0;
        for (String rawLine : normalized.replace("\r\n", "\n").replace('\r', '\n').split("\n")) {
            String line = normalizeMixedMathDelimiters(
                    normalizeBareMathFragments(normalizeCircledNumerals(rawLine.strip())));
            // Some model/Markdown adapters serialize an environment boundary as visible text, for example
            // "- itemize - 内容".  It is layout syntax, not lesson content, so strip only the leading
            // environment label while preserving the actual evidence sentence that follows it.
            line = stripMalformedEnvironmentPrefix(line);
            line = line
                    .replace("AI教师讲解草稿", "教师讲解稿")
                    .replace("AI 讲义草稿", "讲义内容生成")
                    .replace("AI生成状态", "生成状态");
            // Old persisted LaTeX is exported without re-running retrieval. Remove its source-book banner here so a
            // historical task cannot leak a brand even when it has enough pages to pass the publication gate.
            line = HISTORICAL_SOURCE_BRAND.matcher(HISTORICAL_SOURCE_BANNER.matcher(line).replaceFirst(""))
                    .replaceFirst("");
            // Page breaks and explicit writing space are authored layout, not disposable empty headings.
            // Preserve them before section/evidence filtering so every question boundary reaches both renderers.
            if (isPageBreakCommand(line)
                    || (isWritingSpaceCommand(line)
                    && !skippingBlankWorkspaceSection
                    && !skippingLegacyMetadataSection)) {
                lines.add(line);
                continue;
            }
            // Workflow sanitization may already have converted a Markdown image into the opaque marker. Keep that
            // marker as a structural line so the renderer can resolve the authorized local file instead of printing
            // its base64 payload as user-facing text.
            if (parseImageMarker(line).isPresent()) {
                lines.add(line);
                continue;
            }
            // Older persisted drafts sometimes put the marker beside a caption or a sentence. Split it into
            // structural lines before escaping text so the marker can never reach the PDF as visible base64.
            Matcher embeddedImage = IMAGE_MARKER.matcher(line);
            if (embeddedImage.find()) {
                String before = line.substring(0, embeddedImage.start()).strip();
                String marker = embeddedImage.group();
                String after = line.substring(embeddedImage.end()).strip();
                if (!before.isBlank()) {
                    lines.add(before);
                }
                lines.add(marker);
                if (!after.isBlank()) {
                    lines.add(after);
                }
                continue;
            }
            // Older tasks can contain a direct includegraphics command. Convert it to the same structural marker
            // used by Markdown images instead of allowing its optional arguments into PDFBox text extraction.
            if (line.startsWith("\\includegraphics") && line.contains("\\detokenize{") && line.endsWith("}}")) {
                int pathStart = line.indexOf("\\detokenize{") + "\\detokenize{".length();
                String imagePath = line.substring(pathStart, line.length() - 2).replace('\\', '/');
                // Re-check the local file at export time so a resumed stale task cannot read an arbitrary path.
                if (existingLocalImagePath(imagePath).isPresent()) {
                    // A direct includegraphics command is already an authored, permission-checked inline figure.
                    // "相关图示" was a transport-only fallback label that became a meaningless printed caption in
                    // all three versions; retain the image marker but deliberately keep its printable alt blank.
                    lines.add(toImageMarker(new HandoutImage(INLINE_FIGURE_TRANSPORT_ALT, imagePath)));
                }
                continue;
            }
            Matcher section = SECTION_COMMAND.matcher(line);
            if (section.matches()) {
                String heading = cleanText(section.group(1));
                if (isForbiddenWorkflowHeading(heading)) {
                    inEvidenceSection = false;
                    skippingTextbookBody = false;
                    skippingLegacyMetadataSection = false;
                    skippingBlankWorkspaceSection = false;
                    continue;
                }
                if (isVersionOnlyHeading(heading)) {
                    skippingLegacyMetadataSection = false;
                    skippingBlankWorkspaceSection = false;
                    inEvidenceSection = false;
                    skippingTextbookBody = false;
                    continue;
                }
                if (isBlankWorkspaceHeading(heading)) {
                    skippingLegacyMetadataSection = false;
                    skippingBlankWorkspaceSection = true;
                    inEvidenceSection = false;
                    skippingTextbookBody = false;
                    continue;
                }
                skippingBlankWorkspaceSection = false;
                skippingLegacyMetadataSection = isLegacyMetadataHeading(heading);
                if (skippingLegacyMetadataSection) {
                    continue;
                }
                inEvidenceSection = isEvidenceHeading(heading);
                if (inEvidenceSection) {
                    lines.add("\\section{来源索引}");
                    evidenceLineCount = 0;

                    skippingTextbookBody = false;
                    continue;
                }
                skippingTextbookBody = false;
            }
            if (line.isBlank()) {
                if (!skippingTextbookBody && !skippingLegacyMetadataSection && !skippingBlankWorkspaceSection) {
                    lines.add("");
                }
                continue;
            }
            // A malformed OCR/Markdown conversion can leave the environment name as visible text (for example
            // "- itemize -"). It is layout syntax, never lesson content.
            // OCR can flatten an environment opener and its first item into one line, e.g.
            // "- itemize - 先按相邻关系分类". Strip only the leaked environment prefix;
            // the sentence after it is real evidence and must remain printable.
            line = line.replaceFirst("^-?\\s*itemize\\s*-\\s*", "")
                    .replaceFirst("^-?\\s*enumerate\\s*-\\s*", "");
            if (line.isBlank()) {
                continue;
            }
            if (skippingLegacyMetadataSection || skippingBlankWorkspaceSection) {
                continue;
            }
            if (line.startsWith("%") || isDiagnosticLine(line)) {
                continue;
            }
            if (isUnreadablePlaceholderLine(line)) {
                continue;
            }
            if (isLatexDocumentScaffoldLine(line)) {
                continue;
            }
            if (isInternalLayoutInstruction(line)) {
                continue;
            }
            if (isTemplateMetadataLine(line)) {
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
                    lines.add("\\subsection*{" + latexText(heading) + "}");
                }
                continue;
            }
            if (skippingTextbookBody) {
                continue;
            }
            line = removeVisibleWorkspaceLabels(line).strip();
            if (line.isBlank()) {
                continue;
            }
            // OCR/model output sometimes escapes an embedded Markdown table-of-contents line as \#\#\# text.
            // It is navigation metadata, not a mathematical explanation, and must never reach the printable body.
            if (line.contains("\\#\\#\\#") || line.contains("###")) {
                continue;
            }
            // Resolve local Markdown images before evidence compaction.  Evidence summaries used to turn
            // `![alt](path)` into visible text, which made XeLaTeX treat `[width=...]` as a keyval option and
            // forced the whole teacher handout into the low-fidelity PDFBox fallback.
            List<HandoutImage> markdownImages = extractMarkdownImages(line);
            // Outside the evidence summary retain the marker even when an old task points at a missing asset;
            // the fallback renderer can then show the deliberate "图片未找到" state without exposing Markdown.

            // Evidence summaries are stricter: only a permission-checked, existing local asset is renderable.
            List<HandoutImage> images = inEvidenceSection
                    ? markdownImages.stream().filter(image -> existingLocalImagePath(image.path()).isPresent()).toList()
                    : markdownImages;
            if (!images.isEmpty()) {
                String textOnly = MARKDOWN_IMAGE.matcher(line).replaceAll("").strip();
                if (!textOnly.isBlank() && !inEvidenceSection) {
                    lines.add(textOnly);
                }
                for (HandoutImage image : images) {
                    lines.add(toImageMarker(image));
                }
                continue;
            }
            if (inEvidenceSection) {
                line = compactEvidenceReference(line);
                if (line.isBlank() || evidenceLineCount >= 4) {
                    continue;
                }
                evidenceLineCount += 1;
                line = "- " + line;
            }
            lines.add(line);
        }
        // Image markers are an internal transport format.  Escape ordinary LaTeX text, but keep the marker
        // byte-for-byte intact so renderLatexBody can resolve the permission-checked local asset later.
        String cleaned = cleanBlocksPreservingPageBreaks(
                escapeLooseTextSpecialsPreservingImageMarkers(String.join("\n", lines)));
        // A legacy evidence compactor may reintroduce the environment label after the first pass. Remove
        // that visible residue at the final boundary while leaving the sentence itself intact.
        return cleaned
                .replaceAll("(?m)^\\s*(?:-\\s*)+itemize\\s*-\\s*", "")
                .replaceAll("(?m)^\\s*(?:-\\s*)+enumerate\\s*-\\s*", "")
                .strip();
    }

    static String normalizeSplitFunctionArguments(String value) {
        Matcher matcher = SPLIT_FUNCTION_ARGUMENT.matcher(value == null ? "" : value);
        StringBuffer normalized = new StringBuffer();
        while (matcher.find()) {
            String replacement = "$%s %s%s$".formatted(matcher.group(1), matcher.group(2), matcher.group(3));
            matcher.appendReplacement(normalized, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(normalized);
        return normalized.toString();
    }
    // Pure export rule delegated to TeachingHandoutPdfExportPolicyPartA; process/lifecycle state remains in the exporter facade.
    static String stripMalformedEnvironmentPrefix(String value) { return TeachingHandoutPdfExportPolicyPartA.stripMalformedEnvironmentPrefix(value); }
    // Pure export rule delegated to TeachingHandoutPdfExportPolicyPartA; process/lifecycle state remains in the exporter facade.
    static String cleanBlocksPreservingPageBreaks(String latex) { return TeachingHandoutPdfExportPolicyPartA.cleanBlocksPreservingPageBreaks(latex); }
    // Pure export rule delegated to TeachingHandoutPdfExportPolicyPartA; process/lifecycle state remains in the exporter facade.
    static void appendCleanSegment(StringBuilder result, StringBuilder segment) { TeachingHandoutPdfExportPolicyPartA.appendCleanSegment(result, segment); }
    // Pure export rule delegated to TeachingHandoutPdfExportPolicyPartA; process/lifecycle state remains in the exporter facade.
    static boolean isQuestionPageSegment(String segment) { return TeachingHandoutPdfExportPolicyPartA.isQuestionPageSegment(segment); }
    // Pure export rule delegated to TeachingHandoutPdfExportPolicyPartA; process/lifecycle state remains in the exporter facade.
    static String sanitizeQuestionPageSegment(String segment) { return TeachingHandoutPdfExportPolicyPartA.sanitizeQuestionPageSegment(segment); }
    // Pure export rule delegated to TeachingHandoutPdfExportPolicyPartA; process/lifecycle state remains in the exporter facade.
    static boolean isPageBreakCommand(String line) { return TeachingHandoutPdfExportPolicyPartA.isPageBreakCommand(line); }
    // Pure export rule delegated to TeachingHandoutPdfExportPolicyPartA; process/lifecycle state remains in the exporter facade.
    static boolean isWritingSpaceCommand(String line) { return TeachingHandoutPdfExportPolicyPartA.isWritingSpaceCommand(line); }
    // Pure export rule delegated to TeachingHandoutPdfExportPolicyPartA; process/lifecycle state remains in the exporter facade.
    static String normalizeCircledNumerals(String value) { return TeachingHandoutPdfExportPolicyPartA.normalizeCircledNumerals(value); }
    // Pure export rule delegated to TeachingHandoutPdfExportPolicyPartA; process/lifecycle state remains in the exporter facade.
    static String normalizeMixedMathDelimiters(String value) { return TeachingHandoutPdfExportPolicyPartA.normalizeMixedMathDelimiters(value); }
    // Pure export repair delegated to TeachingHandoutPdfExportPolicyPartA so preview, download, and PDF share one rule.
    static String normalizeTripleDollarMath(String value) { return TeachingHandoutPdfExportPolicyPartA.normalizeTripleDollarMath(value); }
    // Pure export rule delegated to TeachingHandoutPdfExportPolicyPartA; process/lifecycle state remains in the exporter facade.
    static String stripLectureProjectionColumns(String body) { return TeachingHandoutPdfExportPolicyPartA.stripLectureProjectionColumns(body); }
    // Pure export rule delegated to TeachingHandoutPdfExportPolicyPartA; process/lifecycle state remains in the exporter facade.
    static String stripStudentTeacherBlocks(String body) { return TeachingHandoutPdfExportPolicyPartA.stripStudentTeacherBlocks(body); }
    // Pure export rule delegated to TeachingHandoutPdfExportPolicyPartA; process/lifecycle state remains in the exporter facade.
    static String stripStudentQuestionUnits(String body) { return TeachingHandoutPdfExportPolicyPartA.stripStudentQuestionUnits(body); }
    // Pure export rule delegated to TeachingHandoutPdfExportPolicyPartA; process/lifecycle state remains in the exporter facade.
    static String stripTeacherOcrAnswerBlocks(String body) { return TeachingHandoutPdfExportPolicyPartA.stripTeacherOcrAnswerBlocks(body); }
    // Pure export rule delegated to TeachingHandoutPdfExportPolicyPartB; process/lifecycle state remains in the exporter facade.
    static String repairMojibake(String value) { return TeachingHandoutPdfExportPolicyPartB.repairMojibake(value); }
    // Pure export rule delegated to TeachingHandoutPdfExportPolicyPartB; process/lifecycle state remains in the exporter facade.
    static long mojibakeScore(String value) { return TeachingHandoutPdfExportPolicyPartB.mojibakeScore(value); }
    // Pure export rule delegated to TeachingHandoutPdfExportPolicyPartB; process/lifecycle state remains in the exporter facade.
    static String normalizeBareMathFragments(String value) { return TeachingHandoutPdfExportPolicyPartB.normalizeBareMathFragments(value); }
    // Pure export rule delegated to TeachingHandoutPdfExportPolicyPartB; process/lifecycle state remains in the exporter facade.
    static String rewriteBareMathAtoms(String value) { return TeachingHandoutPdfExportPolicyPartB.rewriteBareMathAtoms(value); }
    // Pure export rule delegated to TeachingHandoutPdfExportPolicyPartB; process/lifecycle state remains in the exporter facade.
    static String removeVisibleWorkspaceLabels(String value) { return TeachingHandoutPdfExportPolicyPartB.removeVisibleWorkspaceLabels(value); }
    // Pure export rule delegated to TeachingHandoutPdfExportPolicyPartB; process/lifecycle state remains in the exporter facade.
    static boolean isBlankWorkspaceHeading(String value) { return TeachingHandoutPdfExportPolicyPartB.isBlankWorkspaceHeading(value); }
    // Pure export rule delegated to TeachingHandoutPdfExportPolicyPartB; process/lifecycle state remains in the exporter facade.
    static String removeEmptyTitledBlocks(String latex) { return TeachingHandoutPdfExportPolicyPartB.removeEmptyTitledBlocks(latex); }
    // Pure export rule delegated to TeachingHandoutPdfExportPolicyPartB; process/lifecycle state remains in the exporter facade.
    static String renderNonEmptyTitleRange(String[] lines, int start, int end) { return TeachingHandoutPdfExportPolicyPartB.renderNonEmptyTitleRange(lines, start, end); }
    // Pure export rule delegated to TeachingHandoutPdfExportPolicyPartB; process/lifecycle state remains in the exporter facade.
    static int latexHeadingLevel(String command) { return TeachingHandoutPdfExportPolicyPartB.latexHeadingLevel(command); }
    // Pure export rule delegated to TeachingHandoutPdfExportPolicyPartB; process/lifecycle state remains in the exporter facade.
    static boolean hasRealLatexContent(String body) { return TeachingHandoutPdfExportPolicyPartB.hasRealLatexContent(body); }
    // Pure export rule delegated to TeachingHandoutPdfExportPolicyPartB; process/lifecycle state remains in the exporter facade.
    static boolean isBlankWorkspaceLabelLine(String line) { return TeachingHandoutPdfExportPolicyPartB.isBlankWorkspaceLabelLine(line); }
    // Pure export rule delegated to TeachingHandoutPdfExportPolicyPartB; process/lifecycle state remains in the exporter facade.
    static boolean isBlankOnlyLatexLine(String line) { return TeachingHandoutPdfExportPolicyPartB.isBlankOnlyLatexLine(line); }
    /**
     * Applies the final body normalization used immediately before XeLaTeX compilation.
     * Public visibility keeps this compiler-boundary contract directly regression-testable without starting a PDF process.
     */
    public static String renderLatexBody(String sanitizedBody) {
        return TeachingHandoutPdfExportPolicyPartB.renderLatexBody(sanitizedBody);
    }
    // Pure export rule delegated to TeachingHandoutPdfExportPolicyPartB; process/lifecycle state remains in the exporter facade.
    static String renderLatexImageBlock(List<HandoutImage> images) { return TeachingHandoutPdfExportPolicyPartB.renderLatexImageBlock(images); }
    // Pure export rule delegated to TeachingHandoutPdfExportPolicyPartB; process/lifecycle state remains in the exporter facade.
    static String renderLatexImageRowCell(HandoutImage image) { return TeachingHandoutPdfExportPolicyPartB.renderLatexImageRowCell(image); }
    // Pure export rule delegated to TeachingHandoutPdfExportPolicyPartB; process/lifecycle state remains in the exporter facade.
    static String renderLatexImageCell(HandoutImage image, String width, String maxHeight) { return TeachingHandoutPdfExportPolicyPartB.renderLatexImageCell(image, width, maxHeight); }
    // Pure export rule delegated to TeachingHandoutPdfExportPolicyPartB; process/lifecycle state remains in the exporter facade.
    static String latexImagePath(Path path) { return TeachingHandoutPdfExportPolicyPartB.latexImagePath(path); }
    // Pure export rule delegated to TeachingHandoutPdfExportPolicyPartB; process/lifecycle state remains in the exporter facade.
    static String escapeLooseTextSpecials(String value) { return TeachingHandoutPdfExportPolicyPartB.escapeLooseTextSpecials(value); }
    // Pure export rule delegated to TeachingHandoutPdfExportPolicyPartB; process/lifecycle state remains in the exporter facade.
    static String normalizeLegacyLatexForExport(String value) { return TeachingHandoutPdfExportPolicyPartB.normalizeLegacyLatexForExport(value); }
    // Pure export rule delegated to TeachingHandoutPdfExportPolicyPartB; process/lifecycle state remains in the exporter facade.
    static String escapeLooseTextSpecialsPreservingImageMarkers(String value) { return TeachingHandoutPdfExportPolicyPartB.escapeLooseTextSpecialsPreservingImageMarkers(value); }
    // Pure export rule delegated to TeachingHandoutPdfExportPolicyPartB; process/lifecycle state remains in the exporter facade.
    static String latexText(String value) { return TeachingHandoutPdfExportPolicyPartB.latexText(value); }
    // Pure export rule delegated to TeachingHandoutPdfExportPolicyPartB; process/lifecycle state remains in the exporter facade.
    static String normalizeLegacyLatexText(String value) { return TeachingHandoutPdfExportPolicyPartB.normalizeLegacyLatexText(value); }
    // Pure export rule delegated to TeachingHandoutPdfExportPolicyPartB; process/lifecycle state remains in the exporter facade.
    static String hex(Color color) { return TeachingHandoutPdfExportPolicyPartB.hex(color); }
    // Pure export rule delegated to TeachingHandoutPdfExportPolicyPartB; process/lifecycle state remains in the exporter facade.
    static String tail(String value, int maxLength) { return TeachingHandoutPdfExportPolicyPartB.tail(value, maxLength); }
    // Pure export rule delegated to TeachingHandoutPdfExportPolicyPartB; process/lifecycle state remains in the exporter facade.
    static int countPages(byte[] pdfBytes) { return TeachingHandoutPdfExportPolicyPartB.countPages(pdfBytes); }
    // Pure export rule delegated to TeachingHandoutPdfExportPolicyPartB; process/lifecycle state remains in the exporter facade.
    static void deleteRecursively(Path root) { TeachingHandoutPdfExportPolicyPartB.deleteRecursively(root); }
    // Pure export rule delegated to TeachingHandoutPdfExportPolicyPartB; process/lifecycle state remains in the exporter facade.
    static PDFont loadReadableFont(PDDocument document) throws IOException { return TeachingHandoutPdfExportPolicyPartB.loadReadableFont(document); }
    // Pure export rule delegated to TeachingHandoutPdfExportPolicyPartB; process/lifecycle state remains in the exporter facade.
    static String configuredFontValue() { return TeachingHandoutPdfExportPolicyPartB.configuredFontValue(); }
    // Pure export rule delegated to TeachingHandoutPdfExportPolicyPartB; process/lifecycle state remains in the exporter facade.
    static List<Path> commonFontPaths() { return TeachingHandoutPdfExportPolicyPartB.commonFontPaths(); }

    /** Removes container/layout commands that have no visible meaning in the PDFBox fallback. */
    static final Pattern FALLBACK_LAYOUT_COMMAND = Pattern.compile(
            "\\\\(?:begin\\s*\\{minipage}(?:\\s*\\[[^]]*])?\\s*\\{[^}]*}|end\\s*\\{minipage}|hfill|vfill|smallskip|medskip|bigskip|par)\\s*");
    // Pure export rule delegated to TeachingHandoutPdfExportPolicyPartB; process/lifecycle state remains in the exporter facade.
    static List<ReadableLine> readableLines(String latex) { return TeachingHandoutPdfExportPolicyPartB.readableLines(latex); }
    // Pure export rule delegated to TeachingHandoutPdfExportPolicyPartB; process/lifecycle state remains in the exporter facade.
    static boolean isDiagnosticLine(String line) { return TeachingHandoutPdfExportPolicyPartB.isDiagnosticLine(line); }
    // Pure export rule delegated to TeachingHandoutPdfExportPolicyPartB; process/lifecycle state remains in the exporter facade.
    static boolean isEvidenceHeading(String heading) { return TeachingHandoutPdfExportPolicyPartB.isEvidenceHeading(heading); }
    // Pure export rule delegated to TeachingHandoutPdfExportPolicyPartB; process/lifecycle state remains in the exporter facade.
    static boolean isLegacyMetadataHeading(String heading) { return TeachingHandoutPdfExportPolicyPartB.isLegacyMetadataHeading(heading); }
    // Pure export rule delegated to TeachingHandoutPdfExportPolicyPartB; process/lifecycle state remains in the exporter facade.
    static boolean isForbiddenWorkflowHeading(String heading) { return TeachingHandoutPdfExportPolicyPartB.isForbiddenWorkflowHeading(heading); }
    // Pure export rule delegated to TeachingHandoutPdfExportPolicyPartB; process/lifecycle state remains in the exporter facade.
    static boolean isVersionOnlyHeading(String heading) { return TeachingHandoutPdfExportPolicyPartB.isVersionOnlyHeading(heading); }
    // Pure export rule delegated to TeachingHandoutPdfExportPolicyPartB; process/lifecycle state remains in the exporter facade.
    static boolean isTextbookBodyHeading(String heading) { return TeachingHandoutPdfExportPolicyPartB.isTextbookBodyHeading(heading); }
    // Pure export rule delegated to TeachingHandoutPdfExportPolicyPartB; process/lifecycle state remains in the exporter facade.
    static boolean isInternalLayoutInstruction(String line) { return TeachingHandoutPdfExportPolicyPartB.isInternalLayoutInstruction(line); }
    // Pure export rule delegated to TeachingHandoutPdfExportPolicyPartB; process/lifecycle state remains in the exporter facade.
    static boolean isTemplateMetadataLine(String line) { return TeachingHandoutPdfExportPolicyPartB.isTemplateMetadataLine(line); }
    // Pure export rule delegated to TeachingHandoutPdfExportPolicyPartB; process/lifecycle state remains in the exporter facade.
    static boolean isUnreadablePlaceholderLine(String line) { return TeachingHandoutPdfExportPolicyPartB.isUnreadablePlaceholderLine(line); }
    // Pure export rule delegated to TeachingHandoutPdfExportPolicyPartB; process/lifecycle state remains in the exporter facade.
    static boolean isLatexDocumentScaffoldLine(String line) { return TeachingHandoutPdfExportPolicyPartB.isLatexDocumentScaffoldLine(line); }
    // Pure export rule delegated to TeachingHandoutPdfExportPolicyPartB; process/lifecycle state remains in the exporter facade.
    static List<HandoutImage> extractMarkdownImages(String line) { return TeachingHandoutPdfExportPolicyPartB.extractMarkdownImages(line); }
    // Pure export rule delegated to TeachingHandoutPdfExportPolicyPartB; process/lifecycle state remains in the exporter facade.
    static String normalizeImageReference(String rawPath) { return TeachingHandoutPdfExportPolicyPartB.normalizeImageReference(rawPath); }
    // Pure export rule delegated to TeachingHandoutPdfExportPolicyPartB; process/lifecycle state remains in the exporter facade.
    static String toImageMarker(HandoutImage image) { return TeachingHandoutPdfExportPolicyPartB.toImageMarker(image); }
    // Pure export rule delegated to TeachingHandoutPdfExportPolicyPartB; process/lifecycle state remains in the exporter facade.
    static Optional<HandoutImage> parseImageMarker(String line) { return TeachingHandoutPdfExportPolicyPartB.parseImageMarker(line); }
    // Pure export rule delegated to TeachingHandoutPdfExportPolicyPartB; process/lifecycle state remains in the exporter facade.
    static Optional<Path> existingLocalImagePath(String reference) { return TeachingHandoutPdfExportPolicyPartB.existingLocalImagePath(reference); }
    // Pure export rule delegated to TeachingHandoutPdfExportPolicyPartB; process/lifecycle state remains in the exporter facade.
    static boolean isMarkdownImageOnlyLine(String line) { return TeachingHandoutPdfExportPolicyPartB.isMarkdownImageOnlyLine(line); }
    // Pure export rule delegated to TeachingHandoutPdfExportPolicyPartB; process/lifecycle state remains in the exporter facade.
    static String compactEvidenceReference(String value) { return TeachingHandoutPdfExportPolicyPartB.compactEvidenceReference(value); }
    // Pure export rule delegated to TeachingHandoutPdfExportPolicyPartB; process/lifecycle state remains in the exporter facade.
    static String compactLegacyPdfEvidenceLine(String value) { return TeachingHandoutPdfExportPolicyPartB.compactLegacyPdfEvidenceLine(value); }
    // Pure export rule delegated to TeachingHandoutPdfExportPolicyPartB; process/lifecycle state remains in the exporter facade.
    static List<ReadableLine> compactBlanks(List<ReadableLine> lines) { return TeachingHandoutPdfExportPolicyPartB.compactBlanks(lines); }
    // Pure export rule delegated to TeachingHandoutPdfExportPolicyPartB; process/lifecycle state remains in the exporter facade.
    static void addBlank(List<ReadableLine> lines) { TeachingHandoutPdfExportPolicyPartB.addBlank(lines); }

    // Pure export rule delegated to TeachingHandoutPdfExportPolicyPartB; process/lifecycle state remains in the exporter facade.
    static String cleanText(String value) { return TeachingHandoutPdfExportPolicyPartB.cleanText(value); }
    // Pure export rule delegated to TeachingHandoutPdfExportPolicyPartB; process/lifecycle state remains in the exporter facade.
    static String normalizeMathScripts(String value) { return TeachingHandoutPdfExportPolicyPartB.normalizeMathScripts(value); }
    // Pure export rule delegated to TeachingHandoutPdfExportPolicyPartB; process/lifecycle state remains in the exporter facade.
    static String replaceScript(Pattern pattern, String value, boolean superscript) { return TeachingHandoutPdfExportPolicyPartB.replaceScript(pattern, value, superscript); }
    // Pure export rule delegated to TeachingHandoutPdfExportPolicyPartB; process/lifecycle state remains in the exporter facade.
    static String replaceRepeated(Pattern pattern, String value, String replacement) { return TeachingHandoutPdfExportPolicyPartB.replaceRepeated(pattern, value, replacement); }
    // Pure export rule delegated to TeachingHandoutPdfExportPolicyPartB; process/lifecycle state remains in the exporter facade.
    static String versionTitle(String version) { return TeachingHandoutPdfExportPolicyPartB.versionTitle(version); }
    // Pure export rule delegated to TeachingHandoutPdfExportPolicyPartB; process/lifecycle state remains in the exporter facade.
    static String templateNameForVersion(TeachingTaskResponse task, String version) { return TeachingHandoutPdfExportPolicyPartB.templateNameForVersion(task, version); }
    // Pure export rule delegated to TeachingHandoutPdfExportPolicyPartB; process/lifecycle state remains in the exporter facade.
    static String safeText(String value) { return TeachingHandoutPdfExportPolicyPartB.safeText(value); }
    // Pure export rule delegated to TeachingHandoutPdfExportPolicyPartB; process/lifecycle state remains in the exporter facade.
    static String nonBlank(String value, String fallback) { return TeachingHandoutPdfExportPolicyPartB.nonBlank(value, fallback); }
    // Pure export rule delegated to TeachingHandoutPdfExportPolicyPartB; process/lifecycle state remains in the exporter facade.
    static String safeHeaderTopic(String value) { return TeachingHandoutPdfExportPolicyPartB.safeHeaderTopic(value); }
    // Pure export rule delegated to TeachingHandoutPdfExportPolicyPartB; process/lifecycle state remains in the exporter facade.
    static String normalizedWatermark(String value) { return TeachingHandoutPdfExportPolicyPartB.normalizedWatermark(value); }
    // Pure export rule delegated to TeachingHandoutPdfExportPolicyPartB; process/lifecycle state remains in the exporter facade.
    static void addPageFooters(PDDocument document, PDFont font, PdfStyle style, String title, String watermark, String templateName) throws IOException { TeachingHandoutPdfExportPolicyPartB.addPageFooters(document, font, style, title, watermark, templateName); }
    // Pure export rule delegated to TeachingHandoutPdfExportPolicyPartB; process/lifecycle state remains in the exporter facade.
    static float footerMargin(PdfStyle style, boolean zhaoTemplate) { return TeachingHandoutPdfExportPolicyPartB.footerMargin(style, zhaoTemplate); }
    // Pure export rule delegated to TeachingHandoutPdfExportPolicyPartB; process/lifecycle state remains in the exporter facade.
    static String supportedText(PDFont font, String value) { return TeachingHandoutPdfExportPolicyPartB.supportedText(font, value); }
    // Pure export rule delegated to TeachingHandoutPdfExportPolicyPartB; process/lifecycle state remains in the exporter facade.
    static float textWidth(PDFont font, String text, float fontSize) { return TeachingHandoutPdfExportPolicyPartB.textWidth(font, text, fontSize); }

    /**
     * Logical output line type.
     */
    enum LineType {
        TITLE,
        HEADING,
        PARAGRAPH,
        BULLET,
        MUTED,
        IMAGE,
        PAGE_BREAK,
        WRITING_SPACE,
        BLANK
    }

    /**
     * A readable line after LaTeX cleanup.
     */
    record ReadableLine(LineType type, String text, List<HandoutImage> images) {
        ReadableLine(LineType type, String text) {
            this(type, text, List.of());
        }
    }

    record HandoutImage(String alt, String path) {
    }

    /**
     * Visual parameters for one handout version.
     */
    record PdfStyle(
            Color accent,
            Color accentDark,
            Color accentLight,
            Color titleText,
            Color bodyText,
            Color mutedText,
            Color border,
            String versionLabel) {

        static PdfStyle forVersion(String version, String templateName) {
            if (isZhaoLixianTemplate(templateName)) {
                // The source handout uses navy title ink, a small orange logo accent, black body text, and white paper.
                // Keep the palette across audiences so switching versions never silently substitutes generic teal/blue.
                return new PdfStyle(
                        ZHAO_NAVY,
                        new Color(29, 37, 91),
                        Color.WHITE,
                        Color.BLACK,
                        new Color(17, 17, 17),
                        new Color(17, 17, 17),
                        ZHAO_NAVY,
                        "lecture".equalsIgnoreCase(version) ? "横版讲解" :
                                "student".equalsIgnoreCase(version) ? "学生版" : "教师版");
            }
            if ("student".equalsIgnoreCase(version)) {
                return new PdfStyle(
                        new Color(31, 41, 55),
                        new Color(17, 24, 39),
                        new Color(248, 250, 252),
                        new Color(0, 0, 0),
                        new Color(17, 24, 39),
                        new Color(75, 85, 99),
                        new Color(17, 24, 39),
                        "学生版");
            }
            if ("lecture".equalsIgnoreCase(version)) {
                return new PdfStyle(
                        new Color(37, 99, 235),
                        new Color(30, 64, 175),
                        new Color(239, 246, 255),
                        new Color(15, 23, 42),
                        new Color(30, 41, 59),
                        new Color(100, 116, 139),
                        new Color(191, 219, 254),
                        "横版讲解");
            }
            return new PdfStyle(
                    new Color(15, 118, 110),
                    new Color(17, 94, 89),
                    new Color(240, 253, 250),
                    new Color(15, 23, 42),
                    new Color(30, 41, 59),
                    new Color(100, 116, 139),
                    new Color(153, 246, 228),
                    "教师版");
        }

        static boolean isZhaoLixianTemplate(String templateName) {
            String normalized = templateName == null ? "" : templateName.toLowerCase(Locale.ROOT);
            // Template codes are snake_case (for example zhao_lixian_topic_v1), while display
            // names are natural language.  Compare a separator-free ASCII identity so both routes
            // select the same renderer without depending on a mutable UI label.
            String compactAscii = normalized.replaceAll("[^a-z0-9]", "");
            return normalized.contains("赵礼显") || compactAscii.contains("zhaolixian");
        }

        boolean isLecture() {
            return "横版讲解".equals(versionLabel);
        }

    }

    /**
     * Small paginated PDF text writer.
     */
    static final class PdfWriter {
        private final PDDocument document;
        private final PDFont font;
        private final PdfStyle style;
        private final String title;
        private final String templateName;
        private final String watermark;
        private PDPageContentStream stream;
        float y;
        int pageNumber;

        private PdfWriter(
                PDDocument document,
                PDFont font,
                PdfStyle style,
                String title,
                String templateName,
                String watermark) throws IOException {
            this.document = document;
            this.font = font;
            this.style = style;
            this.title = title;
            this.templateName = templateName;
            this.watermark = normalizedWatermark(watermark);
            this.pageNumber = 0;
            newPage();
        }

        void writeTitle(String text) throws IOException {
            write(new ReadableLine(LineType.TITLE, text));
        }

        void writeHeading(String text) throws IOException {
            write(new ReadableLine(LineType.HEADING, text));
        }

        void writeParagraph(String text) throws IOException {
            for (String paragraph : safeText(text).split("\\R")) {
                if (paragraph.isBlank()) {
                    write(new ReadableLine(LineType.BLANK, ""));
                } else {
                    write(new ReadableLine(LineType.PARAGRAPH, paragraph));
                }
            }
        }

        void writeMuted(String text) throws IOException {
            write(new ReadableLine(LineType.MUTED, text));
        }

        void writeBlank() throws IOException {
            write(new ReadableLine(LineType.BLANK, ""));
        }

        void write(ReadableLine line) throws IOException {
            if (line.type() == LineType.PAGE_BREAK) {
                newPage();
                return;
            }
            if (line.type() == LineType.BLANK) {
                y -= LEADING * 0.6f;
                ensureSpace(LEADING);
                return;
            }
            if (line.type() == LineType.IMAGE) {
                writeImageBlock(line.images());
                return;
            }
            if (line.type() == LineType.WRITING_SPACE) {
                ensureSpace(LEADING * 1.15f);
                stream.setStrokingColor(style.border());
                stream.setLineWidth(0.35f);
                stream.moveTo(pageMargin(), y - 3);
                stream.lineTo(pageWidth() - pageMargin(), y - 3);
                stream.stroke();
                y -= LEADING * 1.15f;
                return;
            }
            float fontSize = switch (line.type()) {
                case TITLE -> TITLE_FONT_SIZE;
                case HEADING -> HEADING_FONT_SIZE;
                case MUTED -> BODY_FONT_SIZE - 1;
                default -> BODY_FONT_SIZE;
            };
            float left = line.type() == LineType.BULLET ? pageMargin() + 14 : pageMargin();
            String prefix = line.type() == LineType.BULLET ? "- " : "";
            if (line.type() == LineType.HEADING) {
                writeHeadingBlock(line.text());
                return;
            }
            for (String wrapped : wrap(prefix + line.text(), line.type() == LineType.BULLET ? WRAP_UNITS - 4 : WRAP_UNITS)) {
                ensureSpace(LEADING);
                stream.beginText();
                stream.setFont(font, fontSize);
                stream.setNonStrokingColor(textColor(line.type()));
                stream.newLineAtOffset(left, y);
                stream.showText(supportedText(font, wrapped));
                stream.endText();
                y -= line.type() == LineType.TITLE ? LEADING * 1.25f : LEADING;
            }
            if (line.type() == LineType.TITLE) {
                y -= 8;
            } else if (line.type() == LineType.PARAGRAPH) {
                y -= 3;
            } else if (line.type() == LineType.BULLET) {

                y -= 1;
            }
        }

        void writeImageBlock(List<HandoutImage> images) throws IOException {
            if (images == null || images.isEmpty()) {
                return;
            }
            for (int index = 0; index < images.size(); index += 2) {
                List<HandoutImage> row = images.subList(index, Math.min(index + 2, images.size()));
                float rowHeight = row.size() == 1 ? 214f : 176f;
                float gap = row.size() == 1 ? 0f : 12f;
                float contentWidth = pageWidth() - pageMargin() * 2;
                float cellWidth = row.size() == 1 ? contentWidth * 0.78f : (contentWidth - gap) / 2f;
                float startX = row.size() == 1 ? (pageWidth() - cellWidth) / 2f : pageMargin();
                ensureSpace(rowHeight);
                float top = y;
                for (int column = 0; column < row.size(); column += 1) {
                    drawImageCell(row.get(column), startX + column * (cellWidth + gap), top, cellWidth, rowHeight - 16f);
                }
                y -= rowHeight;
            }
            y -= 6;
        }

        void drawImageCell(HandoutImage image, float left, float top, float width, float reservedHeight) throws IOException {
            String caption = INLINE_FIGURE_TRANSPORT_ALT.equals(safeText(image.alt())) ? "" : safeText(image.alt());
            List<String> captionLines = caption.isBlank() ? List.of() : wrap(caption, Math.max(12, Math.round(width / 7.4f)));
            float captionHeight = captionLines.isEmpty() ? 0f : captionLines.size() * IMAGE_CAPTION_SIZE * 1.35f + 4f;
            float imageAreaHeight = Math.max(72f, reservedHeight - captionHeight - 8f);
            Optional<Path> localPath = existingLocalImagePath(image.path());
            boolean rendered = false;
            if (localPath.isPresent()) {
                try {
                    PDImageXObject pdImage = PDImageXObject.createFromFileByExtension(localPath.get().toFile(), document);
                    float scale = Math.min(width / pdImage.getWidth(), imageAreaHeight / pdImage.getHeight());
                    float drawWidth = pdImage.getWidth() * scale;
                    float drawHeight = pdImage.getHeight() * scale;
                    stream.drawImage(pdImage, left + (width - drawWidth) / 2f, top - drawHeight, drawWidth, drawHeight);
                    rendered = true;
                } catch (IOException | RuntimeException ignored) {
                    rendered = false;
                }
            }
            if (!rendered) {
                float boxHeight = Math.min(imageAreaHeight, 108f);
                stream.setStrokingColor(style.border());
                stream.setLineWidth(0.45f);
                stream.addRect(left, top - boxHeight, width, boxHeight);
                stream.stroke();
                writeCenteredSmallText(localPath.isPresent() ? "\u56fe\u7247\u65e0\u6cd5\u8bfb\u53d6" : "\u56fe\u7247\u672a\u627e\u5230", left, top - boxHeight / 2f, width);
            }
            if (!captionLines.isEmpty()) {
                stream.beginText();
                stream.setFont(font, IMAGE_CAPTION_SIZE);
                stream.setNonStrokingColor(style.mutedText());
                for (int index = 0; index < captionLines.size(); index += 1) {
                    stream.newLineAtOffset(index == 0 ? left : 0f, index == 0 ? top - imageAreaHeight - 14f : -IMAGE_CAPTION_SIZE * 1.35f);
                    stream.showText(supportedText(font, captionLines.get(index)));
                }
                stream.endText();
            }
        }

        void writeCenteredSmallText(String text, float left, float baseline, float width) throws IOException {
            String rendered = truncateCaption(text, width);
            float size = BODY_FONT_SIZE - 1.2f;
            float textWidth = textWidth(font, rendered, size);
            stream.beginText();
            stream.setFont(font, size);
            stream.setNonStrokingColor(style.mutedText());
            stream.newLineAtOffset(left + Math.max(0f, (width - textWidth) / 2f), baseline);
            stream.showText(supportedText(font, rendered));
            stream.endText();
        }

        String truncateCaption(String text, float width) {
            if (safeText(text).isBlank()) {
                return "";
            }
            int maxUnits = Math.max(12, Math.round(width / 7.4f));
            List<String> wrapped = wrap(text, maxUnits);
            if (wrapped.isEmpty()) {
                return "";
            }
            String firstLine = wrapped.get(0);
            return wrapped.size() == 1 ? firstLine : firstLine + "...";
        }

        void writeHeadingBlock(String text) throws IOException {
            y -= 5;
            ensureSpace(40);
            if (isZhaoTemplate()) {
                stream.setStrokingColor(style.accent());
                stream.setLineWidth(0.35f);
                stream.moveTo(pageMargin(), y + 5);
                stream.lineTo(pageWidth() - pageMargin(), y + 5);
                stream.stroke();
            } else if (!isStudentWorksheetStyle()) {
                stream.setNonStrokingColor(style.accentLight());
                stream.addRect(pageMargin(), y - 8, pageWidth() - pageMargin() * 2, 28);
                stream.fill();
                stream.setNonStrokingColor(style.accent());
                stream.addRect(pageMargin(), y - 8, 4, 28);
                stream.fill();
            } else {
                stream.setStrokingColor(style.border());
                stream.setLineWidth(0.35f);
                stream.moveTo(pageMargin(), y - 10);
                stream.lineTo(pageWidth() - pageMargin(), y - 10);
                stream.stroke();
            }
            stream.beginText();
            stream.setFont(font, HEADING_FONT_SIZE);
            stream.setNonStrokingColor(isZhaoTemplate() ? style.accent() : style.accentDark());
            stream.newLineAtOffset(isStudentWorksheetStyle() && !isZhaoTemplate() ? pageMargin() : pageMargin() + 12, y);
            stream.showText(supportedText(font, text));
            stream.endText();
            y -= 38;
        }

        Color textColor(LineType lineType) {
            return switch (lineType) {
                case TITLE -> style.titleText();
                case MUTED -> style.mutedText();
                default -> style.bodyText();
            };
        }

        void ensureSpace(float required) throws IOException {

            if (y - required < pageMargin()) {
                newPage();
            }
        }

        void newPage() throws IOException {
            if (stream != null) {
                stream.close();
            }
            PDPage page = new PDPage(pageRectangle());
            document.addPage(page);
            stream = new PDPageContentStream(document, page);
            pageNumber += 1;
            drawHeader();
            y = isZhaoTemplate()
                    ? pageHeight() - 126
                    : isStudentWorksheetStyle() ? pageHeight() - 132 : pageHeight() - 104;
        }

        void drawHeader() throws IOException {
            if (isZhaoTemplate()) {
                drawZhaoHeader();
                return;
            }
            if (isStudentWorksheetStyle()) {
                stream.setStrokingColor(style.border());
                stream.setLineWidth(0.7f);
                stream.moveTo(pageMargin(), pageHeight() - 72);
                stream.lineTo(pageWidth() - pageMargin(), pageHeight() - 72);
                stream.stroke();

                stream.beginText();
                stream.setFont(font, 10.2f);
                stream.setNonStrokingColor(style.mutedText());
                stream.newLineAtOffset(pageMargin(), pageHeight() - 54);
                stream.showText(supportedText(font, watermark));
                stream.endText();

                stream.beginText();
                stream.setFont(font, 18.5f);
                stream.setNonStrokingColor(style.titleText());
                float titleWidth = textWidth(font, title, 18.5f);
                stream.newLineAtOffset((pageWidth() - titleWidth) / 2f, pageHeight() - 102);
                stream.showText(supportedText(font, title));
                stream.endText();
                return;
            }
            stream.setNonStrokingColor(style.accentLight());
            stream.addRect(0, pageHeight() - 74, pageWidth(), 74);
            stream.fill();
            stream.setNonStrokingColor(style.accent());
            stream.addRect(0, pageHeight() - 74, 7, 74);
            stream.fill();
            stream.setStrokingColor(style.border());
            stream.setLineWidth(0.5f);
            stream.moveTo(pageMargin(), pageHeight() - 74);
            stream.lineTo(pageWidth() - pageMargin(), pageHeight() - 74);
            stream.stroke();

            stream.beginText();
            stream.setFont(font, 14.5f);
            stream.setNonStrokingColor(style.titleText());
            stream.newLineAtOffset(pageMargin(), pageHeight() - 34);
            stream.showText(supportedText(font, title));
            stream.endText();

            stream.beginText();
            stream.setFont(font, 9.2f);
            stream.setNonStrokingColor(style.mutedText());
            stream.newLineAtOffset(pageMargin(), pageHeight() - 54);
            stream.showText(supportedText(font, watermark + " · 第 " + pageNumber + " 页"));
            stream.endText();

            String chip = style.versionLabel();
            float chipWidth = Math.max(52, textWidth(font, chip, 9.5f) + 22);
            float chipX = pageWidth() - pageMargin() - chipWidth;
            stream.setNonStrokingColor(style.accent());
            stream.addRect(chipX, pageHeight() - 48, chipWidth, 24);
            stream.fill();
            stream.beginText();
            stream.setFont(font, 9.5f);
            stream.setNonStrokingColor(Color.WHITE);
            stream.newLineAtOffset(chipX + 11, pageHeight() - 41);
            stream.showText(supportedText(font, chip));
            stream.endText();
        }

        /** Draws the compact Zhao header used by the original printed handout. */
        void drawZhaoHeader() throws IOException {
            float headerY = pageHeight() - 52;
            stream.setStrokingColor(style.border());
            stream.setLineWidth(0.45f);
            stream.moveTo(pageMargin(), headerY);
            stream.lineTo(pageWidth() - pageMargin(), headerY);
            stream.stroke();

            // The fallback renderer must follow the same task-owned naming rule as XeLaTeX. A reference template
            // never supplies a visible brand, so custom user attribution survives both renderer paths.
            String badge = watermark;
            float badgeWidth = Math.max(64f, textWidth(font, badge, 8.2f) + 18f);
            boolean oddPage = pageNumber % 2 == 1;
            float logoX = oddPage ? pageMargin() : pageWidth() - pageMargin() - 20f;
            float badgeX = oddPage ? pageWidth() - pageMargin() - badgeWidth : pageMargin();

            // The overlapping blue/orange squares are the stable logo motif seen on every scanned page.
            float markY = headerY - 14;
            stream.setNonStrokingColor(ZHAO_NAVY);
            stream.addRect(logoX + 6, markY - 4, 12, 12);
            stream.fill();

            stream.setStrokingColor(ZHAO_ORANGE);
            stream.setLineWidth(1.2f);
            stream.addRect(logoX, markY, 12, 12);
            stream.stroke();
            stream.setStrokingColor(Color.WHITE);
            stream.setLineWidth(0.8f);
            stream.addRect(logoX + 7, markY - 3, 8, 8);
            stream.stroke();

            stream.setStrokingColor(style.border());
            stream.setLineWidth(0.45f);
            stream.addRect(badgeX, headerY - 11, badgeWidth, 16);
            stream.stroke();
            stream.beginText();
            stream.setFont(font, 8.2f);
            stream.setNonStrokingColor(ZHAO_ORANGE);
            stream.newLineAtOffset(badgeX + 9, headerY - 5);
            stream.showText(supportedText(font, badge));
            stream.endText();
        }

        PDRectangle pageRectangle() {
            if (style.isLecture()) {
                return new PDRectangle(LECTURE_PAGE_WIDTH, LECTURE_PAGE_HEIGHT);
            }
            return isZhaoTemplate() ? new PDRectangle(ZHAO_PAGE_WIDTH, ZHAO_PAGE_HEIGHT) : PDRectangle.A4;
        }

        float pageWidth() {
            if (style.isLecture()) {
                return LECTURE_PAGE_WIDTH;
            }
            return isZhaoTemplate() ? ZHAO_PAGE_WIDTH : PAGE_WIDTH;
        }

        float pageHeight() {
            if (style.isLecture()) {
                return LECTURE_PAGE_HEIGHT;
            }
            return isZhaoTemplate() ? ZHAO_PAGE_HEIGHT : PAGE_HEIGHT;
        }

        float pageMargin() {
            if (style.isLecture()) {
                return LECTURE_MARGIN;
            }
            return isZhaoTemplate() ? ZHAO_CONTENT_MARGIN : MARGIN;
        }

        boolean isStudentWorksheetStyle() {
            return "学生版".equals(style.versionLabel());
        }

        boolean isZhaoTemplate() {
            return PdfStyle.isZhaoLixianTemplate(templateName);
        }

        void close() throws IOException {
            if (stream != null) {
                stream.close();
                stream = null;
            }
        }

        static List<String> wrap(String text, int maxUnits) {
            List<String> lines = new ArrayList<>();
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

        static int displayUnits(int codePoint) {
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
