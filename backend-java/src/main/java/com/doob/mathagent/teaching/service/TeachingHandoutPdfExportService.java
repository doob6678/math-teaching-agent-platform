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
import java.nio.charset.StandardCharsets;
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
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
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

    private static final Logger LOGGER = LoggerFactory.getLogger(TeachingHandoutPdfExportService.class);
    private static final Pattern SECTION_COMMAND = Pattern.compile("^\\\\(?:section|subsection|subsubsection|paragraph)\\*?\\{(.+)}\\s*$");
    private static final Pattern LATEX_HEADING_LINE = Pattern.compile("^\\\\(section\\*?|subsection\\*?|subsubsection\\*?|paragraph\\*?)\\{(.+)}\\s*$");
    private static final Pattern MARKDOWN_HEADING = Pattern.compile("^#{1,6}\\s+(.+)$");
    private static final Pattern WRAPPED_TEXT_COMMAND = Pattern.compile("\\\\(?:textbf|textit|emph|text|mathrm)\\{([^{}]*)}");
    private static final Pattern FRAC_COMMAND = Pattern.compile("\\\\frac\\{([^{}]+)}\\{([^{}]+)}");
    /** Repairs model output such as y=\\pm($\\frac{b}{a}$)x before XeLaTeX sees mismatched math delimiters. */
    private static final Pattern MIXED_PLUS_MINUS_FRACTION = Pattern.compile(
            "(?<![$A-Za-z0-9_])([A-Za-z][A-Za-z0-9_{}^]*)\\s*=\\s*\\\\pm\\s*\\(\\$([^$]+)\\$\\)([A-Za-z0-9])");
    private static final Pattern UNDELIMITED_PLUS_MINUS_FRACTION = Pattern.compile(
            "(?<![$A-Za-z0-9_])([A-Za-z][A-Za-z0-9_{}^]*)\\s*=\\s*\\\\pm\\s*\\((\\\\frac\\{[^{}]+}\\{[^{}]+})\\)([A-Za-z0-9])");
    /** Repairs a persisted OCR formula whose squared numerators were split across incompatible dollar runs. */
    private static final Pattern SPLIT_QUADRATIC_FRACTION = Pattern.compile(
            "([A-Za-z])\\\\textasciicircum\\{\\}\\$\\\\frac\\{([^{}]+)}\\{([^{}]+)}([+-])([A-Za-z])\\$"
                    + "\\\\textasciicircum\\{\\}\\$\\\\frac\\{([^{}]+)}\\{([^{}]+)}=([0-9]+)\\$");
    /** A remaining exponent split into prose plus one math island represents one mathematical atom. */
    private static final Pattern SPLIT_EXPONENT_MATH = Pattern.compile(
            "([A-Za-z])\\\\textasciicircum\\{\\}\\$([^$]+)\\$");
    /** Restores an OCR-escaped multiplication as one inline mathematical expression. */
    private static final Pattern BARE_TIMES = Pattern.compile(
            "(?<![$A-Za-z0-9])([0-9]+)\\\\times\\s*([0-9]+)(?![$A-Za-z0-9])");
    private static final Pattern SQRT_COMMAND = Pattern.compile("\\\\sqrt\\{([^{}]+)}");
    private static final Pattern SUPERSCRIPT_BRACED = Pattern.compile("\\^\\{([^{}]+)}");
    private static final Pattern SUPERSCRIPT_SIMPLE = Pattern.compile("\\^([0-9a-zA-Z+-])");
    private static final Pattern SUBSCRIPT_BRACED = Pattern.compile("_\\{([^{}]+)}");
    private static final Pattern SUBSCRIPT_SIMPLE = Pattern.compile("_([0-9a-zA-Z+-])");
    /** Bare OCR variables such as x^2 must enter math mode before ordinary TeX escaping turns ^ into visible text. */
    private static final Pattern BARE_MATH_ATOM = Pattern.compile(
            "(?<![$A-Za-z0-9])([A-Za-z](?:\\^\\{?[A-Za-z0-9]+\\}?|_\\{?[A-Za-z0-9]+\\}?))(?![$A-Za-z0-9])");
    private static final Pattern VSPACE_COMMAND = Pattern.compile("\\\\vspace\\{([0-9.]+)em}");
    private static final Pattern UNDERLINE_HSPACE_COMMAND = Pattern.compile("\\\\underline\\{\\\\hspace\\{[0-9.]+em}}");
    private static final Pattern VISIBLE_WORKSPACE_LABEL = Pattern.compile(
            "(?:课堂作答区|作答区|我的解答|推导区|手写区|教师手写区|留白区|空白区|板书区|教师板书区)\\s*[：:]?");
    private static final Pattern VISIBLE_WORKSPACE_REFERENCE = Pattern.compile(
            "(?:写在|填写在|完成在|放在|留在)(?:课堂作答区|作答区|我的解答|推导区|手写区|教师手写区|留白区|空白区|板书区|教师板书区)");
    private static final Pattern MARKDOWN_IMAGE = Pattern.compile("!\\[([^\\]]*)]\\(([^)]+)\\)");
    /** Historical OCR task snapshots may contain source-book branding before the actual stem. */
    private static final Pattern HISTORICAL_SOURCE_BANNER = Pattern.compile(
            "^(?:(?:赵礼显数学|飞猪数学)\\s*)?(?:作业|讲义|课堂练习)\\s*\\d+\\s*[.．、:：]?\\s*");
    /** A bare historical product label is equally not printable handout content. */
    private static final Pattern HISTORICAL_SOURCE_BRAND = Pattern.compile("^(?:赵礼显数学|飞猪数学)\\s*");
    /** A branded legacy snapshot must be repaired from its source, rather than cosmetically hidden during export. */
    private static final Pattern LEGACY_BRAND_REFERENCE = Pattern.compile("(?:赵礼显数学|飞猪数学)");
    /** OCR squares and replacement characters are unknown mathematical relations, never printable question blanks. */
    private static final Pattern UNRESOLVED_OCR_MATH_GLYPH = Pattern.compile("[□�]");
    /** A prompt that refers to a figure is incomplete until an authorized local figure marker survives sanitization. */
    private static final Pattern FIGURE_DEPENDENT_PROMPT = Pattern.compile("(?:如图|见图|下图|上图|图中)");
    /** Accept standard and URL-safe base64 so persisted markers remain structural across older workers. */
    private static final Pattern IMAGE_MARKER = Pattern.compile("\\[\\[HANDOUTIMAGE:([^:\\]]+):([^\\]]+)]]");
    /** Every numbered question is a publication unit: visual evidence and duplicate checks must never cross it. */
    private static final Pattern NUMBERED_QUESTION_HEADING = Pattern.compile(
            "^\\\\(?:subsection|section)\\*?\\{第\\s*\\d+\\s*题[^}]*}\\s*$");
    /** Teacher answers are required to contain a concrete, source-grounded resolution rather than a fallback notice. */
    private static final Pattern TEACHER_ANSWER_PARAGRAPH = Pattern.compile("^\\\\paragraph\\*?\\{答案与评分点}\\s*$");
    /** The generic fallback is deliberately blocked at publication time because it is not a worked explanation. */
    private static final Pattern UNVERIFIED_TEACHER_ANSWER = Pattern.compile("(?:题库未提供可核验答案|需教师补充后使用)");
    /** Non-empty marker payload required by the legacy image transport grammar; it is never printable caption text. */
    private static final String INLINE_FIGURE_TRANSPORT_ALT = "__inline_figure__";
    private static final Duration LATEX_TIMEOUT = Duration.ofSeconds(45);
    private static final float PAGE_WIDTH = PDRectangle.A4.getWidth();
    private static final float PAGE_HEIGHT = PDRectangle.A4.getHeight();
    /** The scanned Zhao master is 582 by 812 points, rather than ISO A4. */
    private static final float ZHAO_PAGE_WIDTH = 582f;
    private static final float ZHAO_PAGE_HEIGHT = 812f;
    /** Measured from the question-text grid in the scanned master pages p10, p100, and p218. */
    private static final float ZHAO_CONTENT_MARGIN = 72f;
    /** Dominant dark-blue ink sampled from the original title bars and overlapping-square mark. */
    private static final Color ZHAO_NAVY = new Color(44, 57, 135);
    /** Orange outline/accent sampled from the original logo and the Zhao badge lettering. */
    private static final Color ZHAO_ORANGE = new Color(240, 134, 48);
    private static final float LECTURE_PAGE_WIDTH = 800f;
    private static final float LECTURE_PAGE_HEIGHT = 500f;
    private static final float MARGIN = 54;
    private static final float LECTURE_MARGIN = 34;
    private static final float TITLE_FONT_SIZE = 18;
    private static final float HEADING_FONT_SIZE = 12.8f;
    private static final float BODY_FONT_SIZE = 10.8f;
    private static final float IMAGE_CAPTION_SIZE = 8.6f;
    private static final float LEADING = 19;
    private static final int WRAP_UNITS = 68;
    /** Compact projection typography reserves space for a full real prompt, source figure, and three checked steps. */
    private static final String LECTURE_BODY_COMMAND =
            "\\small\\setlength{\\parskip}{0.28em}"
                    + "\\setlist[enumerate]{leftmargin=1.45em,itemsep=0.18em,topsep=0.18em}";
    /** Printed Zhao pages separate consecutive numbered questions with a measured paragraph gap. */
    private static final String PRINTED_QUESTION_GAP = "\\vspace{1.25em}";
    /** Authorized crop from the user-provided Zhao master; copied beside handout.tex for XeLaTeX. */
    private static final String ZHAO_MASTER_HEADER_ASSET = "handout-assets-zhao-header.png";

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
        try (PDDocument document = new PDDocument()) {
            String title = versionTitle(version);
            String templateName = templateNameForVersion(task, version);
            PDFont font = loadReadableFont(document);
            PdfStyle style = PdfStyle.forVersion(version, templateName);
            String handoutSource = sanitizeLatexForExport(task.handoutLatexFor(version));
            if (style.isLecture()) {
                handoutSource = stripLectureProjectionColumns(handoutSource);
            } else if ("学生版".equals(style.versionLabel())) {
                handoutSource = stripStudentQuestionUnits(handoutSource);
            } else {
                handoutSource = stripTeacherOcrAnswerBlocks(handoutSource);
            }
            boolean hasStructuredBody = containsStructuredSections(handoutSource);
            String watermark = normalizedWatermark(repairMojibake(task.watermarkText()));
            PdfWriter writer = new PdfWriter(document, font, style, title, templateName, watermark);
            writer.writeMuted("任务编号：" + safeText(task.taskId()));
            writer.writeMuted(watermark);
            writer.writeBlank();
            if (!hasStructuredBody) {
                writer.writeHeading("学习目标");
                writer.writeParagraph(nonBlank(task.learningGoal(), "未填写"));
                if (!safeText(task.questionText()).isBlank()) {
                    writer.writeHeading("题目 / 要求");
                    writer.writeParagraph(task.questionText());
                }
                writer.writeHeading("讲义内容");
            }
            for (ReadableLine line : readableLines(handoutSource)) {
                writer.write(line);
            }
            writer.close();
            addPageFooters(document, font, style, title, watermark, templateName);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.save(out);
            byte[] bytes = out.toByteArray();
            return new RenderedHandoutPdf(bytes, "pdfbox_fallback", document.getNumberOfPages());
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to render teaching handout PDF", exception);
        }
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

    /**
     * Rejects historically persisted source defects before either renderer is allowed to make them look legitimate.
     *
     * <p>This boundary intentionally validates the pre-sanitized snapshot: sanitizing can remove a visible banner,
     * but it cannot restore the missing relation or missing geometry diagram that the learner needs to solve a
     * question. All protected preview, download and ZIP paths call {@link #renderForPublication}, so one check keeps
     * their outcome identical.</p>
     */
    private static void validatePublicationSource(TeachingTaskResponse task, String version) {
        String source = safeText(task == null ? "" : task.handoutLatexFor(version));
        if (source.isBlank()) {
            throw new IllegalStateException(version + " 版没有可发布的讲义正文，请先完成真实资料生成。");
        }
        if (UNRESOLVED_OCR_MATH_GLYPH.matcher(source).find()) {
            throw new IllegalStateException(version + " 版含有未解析的数学符号（□ 或 �）；请回到原始资料核对后重新同步。");
        }
        if (LEGACY_BRAND_REFERENCE.matcher(source).find()) {
            throw new IllegalStateException(version + " 版仍含历史资料品牌；请使用本任务的自定义署名重新生成。");
        }
        String sanitized = sanitizeLatexForExport(source);
        validateQuestionPublicationUnits(sanitized, version);
    }

    /**
     * Validates each numbered question separately.  A document-level image marker is insufficient: it can belong to
     * another question, or point at a deleted file.  Running after sanitization also recognizes the normal, safe
     * includegraphics-to-marker conversion without persisting transport markers in user content.
     */
    private static void validateQuestionPublicationUnits(String sanitized, String version) {
        List<String> units = numberedQuestionUnits(sanitized);
        Set<String> questionFingerprints = new HashSet<>();
        for (int unitIndex = 0; unitIndex < units.size(); unitIndex += 1) {
            String unit = units.get(unitIndex);
            if (FIGURE_DEPENDENT_PROMPT.matcher(unit).find() && !hasAuthorizedImage(unit)) {
                throw new IllegalStateException(version + " 版题干引用“如图”但本题没有同源、已授权且可读取的图像；"
                        + "请先同步并绑定原图。");
            }
            String fingerprint = questionFingerprint(unit);
            if (!fingerprint.isBlank() && !questionFingerprints.add(fingerprint)) {
                throw new IllegalStateException(version + " 版含有重复题干；请保留一次原子题并重新生成。");
            }
            if ("teacher".equalsIgnoreCase(version) && hasUnverifiedTeacherAnswer(unit)) {
                throw new IllegalStateException("教师版第" + (unitIndex + 1)
                        + "题含有空白或不可核验的讲解/答案，不能作为讲义发布；请补齐真实解题步骤。");
            }
        }
    }

    /** Splits only on printable numbered headings so surrounding goals and source notes never become faux questions. */
    private static List<String> numberedQuestionUnits(String source) {
        String[] lines = safeText(source).replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        List<String> units = new ArrayList<>();
        StringBuilder current = null;
        for (String line : lines) {
            if (NUMBERED_QUESTION_HEADING.matcher(line.strip()).matches()) {
                if (current != null) {
                    units.add(current.toString());
                }
                current = new StringBuilder(line).append('\n');
            } else if (current != null) {
                current.append(line).append('\n');
            }
        }
        if (current != null) {
            units.add(current.toString());
        }
        return units;
    }

    /** Counts the same structural heading used by page-break insertion, avoiding a magic page count assumption. */
    private static int numberedQuestionCount(String source) {
        return numberedQuestionUnits(source).size();
    }

    /** Accepts an image only when its marker parses and its local, permission-checked path still exists at export. */
    private static boolean hasAuthorizedImage(String unit) {
        for (String line : safeText(unit).split("\\R")) {
            Optional<HandoutImage> image = parseImageMarker(line.strip());
            if (image.isPresent() && existingLocalImagePath(image.get().path()).isPresent()) {
                return true;
            }
        }
        return false;
    }

    /** Compares a question's visible prompt only, so a distinct solution cannot hide a duplicated source question. */
    private static String questionFingerprint(String unit) {
        String visible = cleanText(unit)
                .replaceAll("第\\s*\\d+\\s*题", "")
                .replaceAll("[^\\p{IsHan}A-Za-z0-9]", "")
                .toLowerCase(Locale.ROOT);
        return visible.length() < 12 ? "" : visible;
    }

    /** Rejects the known fallback or a blank answer block rather than making a template warning look teachable. */
    private static boolean hasUnverifiedTeacherAnswer(String unit) {
        String[] lines = safeText(unit).split("\\R");
        boolean answerStarted = false;
        StringBuilder answer = new StringBuilder();
        for (String line : lines) {
            String stripped = line.strip();
            if (!answerStarted && TEACHER_ANSWER_PARAGRAPH.matcher(stripped).matches()) {
                answerStarted = true;
                continue;
            }
            if (answerStarted && (stripped.startsWith("\\paragraph{") || stripped.startsWith("\\subsection")
                    || stripped.startsWith("\\section"))) {
                break;
            }
            if (answerStarted) {
                answer.append(stripped).append(' ');
            }
        }
        String visibleAnswer = cleanText(answer.toString()).strip();
        if (!answerStarted) {
            return false;
        }
        if (UNVERIFIED_TEACHER_ANSWER.matcher(visibleAnswer).find()) {
            return true;
        }
        // A real model may place its final conclusion at the end of the detailed deduction instead of duplicating it
        // in a second answer paragraph. Accept only a substantial, conclusion-bearing solution block; a blank answer
        // with no derivation remains unpublishable and cannot be used to bypass the teacher gate.
        return visibleAnswer.isBlank() && !hasSubstantiveTeacherSolution(unit);
    }

    /** Detects a teacher-only reasoning chain that can stand in for an otherwise duplicate answer paragraph. */
    private static boolean hasSubstantiveTeacherSolution(String unit) {
        String visible = cleanText(unit).replaceAll("\\s+", " ").strip();
        if (visible.length() < 180) {
            return false;
        }
        boolean hasDerivation = visible.matches(".*(?:条件识别|推导依据|步骤|由.{1,40}|计算|证明).*");
        boolean hasConclusion = visible.matches(".*(?:因此|故|结论|答案).*");
        return hasDerivation && hasConclusion;
    }

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
    private Optional<byte[]> compileLatex(TeachingTaskResponse task, String version) {
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

    /** Copies a bundled visual master asset into the isolated XeLaTeX work directory. */
    private static void materializeBundledLatexAsset(Path workDir, String assetName) throws IOException {
        try (InputStream source = TeachingHandoutPdfExportService.class.getResourceAsStream("/" + assetName)) {
            if (source == null) {
                throw new IOException("Bundled handout asset is missing: " + assetName);
            }
            Files.copy(source, workDir.resolve(assetName), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * Runs one XeLaTeX pass and returns null on timeout.
     */
    private static Process runXeLaTeX(Path engine, Path workDir, Path source, Path compilerOutput) throws IOException, InterruptedException {
        Process process = new ProcessBuilder(
                engine.toString(),
                "-interaction=nonstopmode",
                "-halt-on-error",
                "-file-line-error",
                source.getFileName().toString())
                .directory(workDir.toFile())
                .redirectErrorStream(true)
                .redirectOutput(compilerOutput.toFile())
                .start();
        boolean finished = process.waitFor(LATEX_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            return null;
        }
        return process;
    }

    private static Optional<Path> latexEnginePath() {
        String configured = System.getenv("MATH_AGENT_XELATEX_PATH");
        if (configured == null || configured.isBlank()) {
            configured = System.getProperty("math.agent.xelatex.path", "");
        }
        if (!configured.isBlank() && Files.isRegularFile(Path.of(configured.strip()))) {
            return Optional.of(Path.of(configured.strip()));
        }
        for (Path candidate : List.of(
                Path.of("xelatex"),
                Path.of("C:/Users/doob/AppData/Local/Programs/MiKTeX/miktex/bin/x64/xelatex.exe"),
                Path.of("C:/Program Files/MiKTeX/miktex/bin/x64/xelatex.exe"),
                Path.of("/usr/bin/xelatex"),
                Path.of("/usr/local/bin/xelatex"))) {
            if (!candidate.isAbsolute() || Files.isRegularFile(candidate)) {
                return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }

    private static boolean containsStructuredSections(String source) {
        for (String rawLine : safeText(source).replace("\r\n", "\n").replace('\r', '\n').split("\n")) {
            if (SECTION_COMMAND.matcher(rawLine.strip()).matches()) {
                return true;
            }
        }
        return false;
    }

    private static String fullLatexDocument(TeachingTaskResponse task, String version) {
        String title = versionTitle(version);
        String templateName = templateNameForVersion(task, version);
        PdfStyle style = PdfStyle.forVersion(version, templateName);
        String sanitizedBody = sanitizeLatexForExport(task.handoutLatexFor(version));
        // Old persisted snapshots may still contain the previous two-column projection or student scaffolding.
        // Apply the audience boundary at export time too, so a stale cache cannot reintroduce teacher explanations.
        if (style.isLecture()) {
            sanitizedBody = stripLectureProjectionColumns(sanitizedBody);
        } else if ("学生版".equals(style.versionLabel())) {
            sanitizedBody = stripStudentQuestionUnits(sanitizedBody);
        } else {
            sanitizedBody = stripTeacherOcrAnswerBlocks(sanitizedBody);
        }
        // The 16:10 lecture is a projection deck: keep one question per slide so unrelated
        // questions are never visually locked together.
        String body = renderLatexBody(style.isLecture()
                ? insertLectureQuestionBreaks(sanitizedBody)
                : insertPrintedQuestionSpacing(sanitizedBody));
        String headerTopic = safeHeaderTopic(repairMojibake(task.learningGoal()));
        String watermark = latexText(normalizedWatermark(repairMojibake(task.watermarkText())));
        String documentOptions = style.isLecture() ? "10pt" : "11pt,a4paper";
        String geometryOptions = style.isLecture()
                ? "paperwidth=16in,paperheight=10in,top=14mm,bottom=18mm,left=18mm,right=18mm"
                : PdfStyle.isZhaoLixianTemplate(templateName)
                        // bp is a PDF big point (1/72in).  TeX pt is 1/72.27in and would emit a
                        // 579.83×809.08 PDF page, which visibly drifts from the measured 582×812 master.
                        ? "paperwidth=582bp,paperheight=812bp,top=26mm,bottom=25mm,left=72bp,right=72bp"
                        : "a4paper,top=24mm,bottom=23mm,left=22mm,right=22mm";
        String bodySizeCommand = style.isLecture() ? LECTURE_BODY_COMMAND
                : PdfStyle.isZhaoLixianTemplate(templateName)
                        // The source master is a continuous exercise handout, not a slide deck. Keep real questions
                        // compact while TeachingWorkflowService protects each prompt-plus-diagram unit with Needspace.
                        ? "\\setlength{\\parskip}{0.24em}\\setlist[itemize]{itemsep=0.12em,topsep=0.16em}"
                                + "\\setlist[enumerate]{itemsep=0.12em,topsep=0.16em}"
                        : "";
        String headerFooterCommands = PdfStyle.isZhaoLixianTemplate(templateName)
                ? zhaoHeaderFooterCommands(watermark)
                : genericHeaderFooterCommands();
        String headingCommands = PdfStyle.isZhaoLixianTemplate(templateName)
                ? zhaoHeadingCommands()
                : genericHeadingCommands();
        // The reference pages start directly with an exercise/section block. A centered audience title is a
        // generic-handout convention and conflicts with the Zhao master, so only non-Zhao templates get it.
        String titleBlock = PdfStyle.isZhaoLixianTemplate(templateName) ? "" : """
                \\begin{center}
                {\\LARGE\\bfseries\\color{HandoutAccent} %s}\\\\[0.35em]
                {\\small\\color{HandoutText} %s}
                \\end{center}
                \\vspace{0.6em}
                """.formatted(latexText(title), watermark);
        return """
                \\documentclass[%s]{article}
                \\usepackage[%s]{geometry}
                \\usepackage{fontspec}
                \\usepackage{xeCJK}
                \\usepackage{xcolor}
                \\usepackage{amsmath,amssymb}
                \\usepackage{graphicx}
                \\usepackage{tikz}
                \\usepackage{caption}
                \\usepackage{enumitem}
                \\usepackage{fancyhdr}
                \\usepackage{lastpage}
                \\usepackage{titlesec}
                \\usepackage{needspace}
                %% OCR imports may isolate a standard math command outside a dollar range.  These wrappers preserve
                %% the mathematical glyph in both text and math mode while the source repair remains backward-safe.
                \\let\\MathAgentOriginalVec\\vec
                \\renewcommand{\\vec}[1]{\\ensuremath{\\MathAgentOriginalVec{#1}}}
                \\let\\MathAgentOriginalOverrightarrow\\overrightarrow
                \\renewcommand{\\overrightarrow}[1]{\\ensuremath{\\MathAgentOriginalOverrightarrow{#1}}}
                \\let\\MathAgentOriginalFrac\\frac
                \\renewcommand{\\frac}[2]{\\ensuremath{\\MathAgentOriginalFrac{#1}{#2}}}
                \\let\\MathAgentOriginalTimes\\times
                \\renewcommand{\\times}{\\ensuremath{\\MathAgentOriginalTimes}}
                %% Keep Noto Sans SC as the body font because it has the complete maths glyph fallback required by
                %% imported Chinese sources.  Only display headings use the serif companion, giving hierarchy without
                %% turning a source root sign into a missing-glyph square.
                \\IfFontExistsTF{Noto Sans SC}{\\setCJKmainfont{Noto Sans SC}}{\\IfFontExistsTF{Microsoft YaHei UI}{\\setCJKmainfont{Microsoft YaHei UI}}{\\IfFontExistsTF{SimSun}{\\setCJKmainfont{SimSun}}{}}}
                \\IfFontExistsTF{Noto Sans SC}{\\setCJKsansfont{Noto Sans SC}}{\\IfFontExistsTF{Microsoft YaHei UI}{\\setCJKsansfont{Microsoft YaHei UI}}{}}
                \\IfFontExistsTF{Noto Serif SC}{\\newCJKfontfamily\\HandoutDisplayFont{Noto Serif SC}}{\\newcommand{\\HandoutDisplayFont}{}}
                %% Imported inline root signs are classified as Latin symbols by XeLaTeX.  Arial is the verified
                %% fallback on this Windows renderer; display typography is applied only to CJK headings above.
                \\IfFontExistsTF{Arial}{\\setmainfont{Arial}}{}
                \\setlength{\\parindent}{0pt}
                \\setlength{\\parskip}{0.72em}
                \\setlength{\\headheight}{15pt}
                \\setlength{\\footskip}{14mm}
                \\setkeys{Gin}{keepaspectratio}
                \\captionsetup{font=small,labelformat=empty}
                \\setlist[itemize]{leftmargin=2em,itemsep=0.28em,topsep=0.35em}
                \\setlist[enumerate]{leftmargin=2em,itemsep=0.28em,topsep=0.35em}
                \\definecolor{HandoutAccent}{HTML}{%s}
                \\definecolor{HandoutLight}{HTML}{%s}
                \\definecolor{HandoutBorder}{HTML}{%s}
                \\definecolor{ZhaoOrange}{HTML}{F08630}
                \\definecolor{HandoutText}{HTML}{111111}
                \\pagestyle{fancy}
                %s
                \\color{HandoutText}
                \\everymath{\\color{HandoutText}}
                \\everydisplay{\\color{HandoutText}}
                %s
                \\begin{document}
                %s
                %s
                %s
                \\end{document}
                """.formatted(
                documentOptions,
                geometryOptions,
                hex(style.accent()),
                hex(style.accentLight()),
                hex(style.border()),
                headerFooterCommands.formatted(watermark, latexText(headerTopic), watermark),
                headingCommands,
                titleBlock,
                bodySizeCommand,
                body);
    }

    /** Inserts a page boundary before every question after the first one in 16:10 output. */
    private static String insertLectureQuestionBreaks(String body) {
        if (body == null || body.isBlank()) {
            return body;
        }
        StringBuilder result = new StringBuilder(body.length());
        boolean questionSeen = false;
        for (String rawLine : body.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1)) {
            String line = rawLine.strip();
            boolean questionHeading = line.matches("^\\\\subsection\\*?\\{第\\s*\\d+\\s*题[^}]*}\\s*$")
                    || line.matches("^\\\\section\\*?\\{第\\s*\\d+\\s*题[^}]*}\\s*$");
            if (questionHeading && questionSeen && !endsWithPageBreak(result)) {
                result.append("\\clearpage\n");
            }
            if (questionHeading) {
                questionSeen = true;
            }
            result.append(rawLine).append('\n');
        }
        return result.toString().strip();
    }

    /** Keeps consecutive A4 questions visually distinct without forcing every exercise onto a page. */
    private static String insertPrintedQuestionSpacing(String body) {
        if (body == null || body.isBlank()) {
            return body;
        }
        StringBuilder result = new StringBuilder(body.length());
        boolean questionSeen = false;
        for (String rawLine : body.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1)) {
            String line = rawLine.strip();
            boolean questionHeading = line.matches("^\\\\subsection\\*?\\{第\\s*\\d+\\s*题[^}]*}\\s*$")
                    || line.matches("^\\\\section\\*?\\{第\\s*\\d+\\s*题[^}]*}\\s*$");
            if (questionHeading && questionSeen && !endsWithPageBreak(result)) {
                result.append(PRINTED_QUESTION_GAP).append('\n');
            }
            if (questionHeading) {
                questionSeen = true;
            }
            result.append(rawLine).append('\n');
        }
        return result.toString().strip();
    }

    private static boolean endsWithPageBreak(StringBuilder text) {
        return text.toString().endsWith("\\clearpage\n") || text.toString().endsWith("\\newpage\n");
    }

    private static String genericHeaderFooterCommands() {
        return """
                \\fancyhf{}
                \\lhead{%s}
                \\rhead{%s}
                \\lfoot{%s}
                \\rfoot{第 \\thepage 页 / 共 \\pageref{LastPage} 页}
                \\renewcommand{\\headrulewidth}{0.4pt}
                \\renewcommand{\\footrulewidth}{0.3pt}
                """;
    }

    /**
     * Uses the blue/orange logo language visible in the Zhao master. The source alternates the
     * logo and badge by page, so the conditional lives inside fancyhdr and is evaluated per page.
     */
    private static String zhaoHeaderFooterCommands(String watermark) {
        // The reference PDF informs measurements only.  Do not embed its branded raster crop: the visible identity
        // belongs to the current task and is drawn as vector geometry so it stays crisp at every export resolution.
        return """
                \\newcommand{\\zhaopagetab}{\\tikz[baseline=-0.56ex,x=2.4ex,y=2.4ex]{
                  \\draw[HandoutText,line width=0.08ex] (0,0.22) -- (6.5,0.22);
                  \\draw[HandoutText,line width=0.08ex,rounded corners=0.18ex] (0.25,0.22) -- (0.48,1.05) -- (2.15,1.05) -- (2.38,0.22);
                  \\node[font=\\scriptsize,text=HandoutText] at (1.31,0.65) {\\thepage};}}
                \\setlength{\\headheight}{39pt}
                \\fancyhf{}
                %%%% Vector header: task-owned display name plus a crisp blue/orange frame, never a pasted source logo.
                \\chead{\\tikz[baseline=-0.65ex]{
                  \\draw[HandoutText,line width=0.08ex] (0,0) -- (15.8,0);
                  \\node[anchor=west,font=\\sffamily\\scriptsize\\bfseries,text=HandoutAccent] at (0,0.28) {%s};
                  \\draw[HandoutAccent,line width=0.14ex] (15.1,0.10) rectangle (15.55,0.55);
                  \\draw[ZhaoOrange,line width=0.11ex] (15.25,0.23) rectangle (15.70,0.68);}}
                \\lfoot{\\ifodd\\value{page}\\zhaopagetab\\fi}
                \\rfoot{\\ifodd\\value{page}\\else\\zhaopagetab\\fi}
                \\renewcommand{\\headrulewidth}{0pt}
                \\renewcommand{\\footrulewidth}{0pt}
                """.formatted(watermark);
    }

    private static String genericHeadingCommands() {
        return """
                \\titleformat{\\section}
                  {\\HandoutDisplayFont\\Large\\bfseries\\color{HandoutAccent}}
                  {}{0pt}{\\makebox[0pt][r]{\\color{HandoutAccent}\\rule{4pt}{1.15em}\\hspace{0.7em}}}
                  [{\\vspace{0.2em}\\color{HandoutAccent!35}\\titlerule[0.5pt]}]
                \\titleformat{\\subsection}
                  {\\HandoutDisplayFont\\large\\bfseries\\color{HandoutAccent}}
                  {}{0pt}{\\makebox[0pt][r]{\\color{HandoutAccent!80}\\rule{3pt}{1em}\\hspace{0.6em}}}
                \\titleformat{\\paragraph}{\\HandoutDisplayFont\\normalsize\\bfseries\\color{HandoutText}}{}{0pt}{}
                \\titlespacing*{\\section}{0pt}{1.45em}{0.8em}
                \\titlespacing*{\\subsection}{0pt}{1.1em}{0.55em}
                """;
    }

    /**
     * Zhao pages use compact dark-blue section ink, not the generic red title with a decorative
     * star frame. Keep the title left-aligned so a mathematical question starts on the master grid.
     */
    private static String zhaoHeadingCommands() {
        return """
                % The Zhao master uses a compact navy title tab with white lettering for a question type.
                % Keep the fill only behind the words so long Chinese titles never cross the fixed print grid.
                \\newcommand{\\zhaosectiontitle}[1]{\\colorbox{HandoutAccent}{\\strut\\hspace{0.52em}\\color{white}\\HandoutDisplayFont\\bfseries #1\\hspace{0.52em}}}
                \\titleformat{\\section}
                  {\\HandoutDisplayFont\\large\\bfseries\\color{HandoutAccent}}
                  {}{0pt}{\\zhaosectiontitle}
                \\titleformat{\\subsection}
                  {\\HandoutDisplayFont\\normalsize\\bfseries\\color{HandoutText}}
                  {}{0pt}{}
                \\titleformat{\\subsubsection}
                  {\\HandoutDisplayFont\\normalsize\\bfseries\\color{HandoutText}}
                  {}{0pt}{}
                \\titleformat{\\paragraph}{\\HandoutDisplayFont\\normalsize\\bfseries\\color{HandoutText}}{}{0pt}{}
                \\titlespacing*{\\section}{0pt}{1.0em}{0.55em}
                \\titlespacing*{\\subsection}{0pt}{0.72em}{0.32em}
                \\titlespacing*{\\subsubsection}{0pt}{0.55em}{0.25em}
                """;
    }

    /**
     * Produces the canonical LaTeX body used by preview, download, ZIP export, and PDF rendering.
     * This is a last-resort guard for old tasks or model output that still contains internal layout
     * instructions, OCR page fragments, or provider diagnostics while preserving real handout images.
     */
    public static String sanitizeLatexForExport(String source) {
        String normalized = repairMojibake(safeText(source))
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
        // JSON decoders treat the `\\r` in an unescaped `\\rightarrow` as a carriage return.  After
        // newline normalization this appears as `\\item ightarrow` or `\\par ightarrow`; repair the
        // command before XeLaTeX parses the body so a valid arrow cannot trigger a PDFBox fallback.
        normalized = normalized
                .replaceAll("\\\\item\\s*ightarrow", "\\\\rightarrow")
                .replaceAll("\\\\par\\s*ightarrow", "\\\\rightarrow");
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

    private static String stripMalformedEnvironmentPrefix(String value) {
        String line = safeText(value);
        if (line.isBlank()) {
            return line;
        }
        // Evidence compaction can prepend another bullet, yielding "- - itemize - 内容".
        // Consume every leading bullet before the leaked environment name, but preserve 内容.
        return line.replaceFirst("^(?:-\\s*)*(?:itemize|enumerate)\\s*-\\s*", "");
    }

    /**
     * Cleans empty headings without allowing a page boundary to disappear with the heading body.
     *
     * <p>Question pages deliberately contain a title, a prompt, a large writing area, and then a
     * {@code \clearpage}.  Cleaning the whole document as one recursive title tree used to treat the
     * writing-only paragraph as empty and dropped the page break together with it.  Split at explicit
     * page boundaries first so the renderer receives the same page structure authored by the workflow.</p>
     */
    private static String cleanBlocksPreservingPageBreaks(String latex) {
        if (latex == null || latex.isBlank()) {
            return "";
        }
        StringBuilder result = new StringBuilder();
        StringBuilder segment = new StringBuilder();
        for (String line : latex.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1)) {
            if (isPageBreakCommand(line)) {
                appendCleanSegment(result, segment);
                segment.setLength(0);
                if (!result.isEmpty()) {
                    result.append('\n');
                }
                result.append(line.strip()).append('\n');
            } else {
                segment.append(line).append('\n');
            }
        }
        appendCleanSegment(result, segment);
        return result.toString().strip();
    }

    private static void appendCleanSegment(StringBuilder result, StringBuilder segment) {
        String raw = segment.toString().strip();
        String cleaned = isQuestionPageSegment(raw)
                ? sanitizeQuestionPageSegment(raw)
                : removeEmptyTitledBlocks(raw);
        if (cleaned.isBlank()) {
            return;
        }
        if (!result.isEmpty() && result.charAt(result.length() - 1) != '\n') {
            result.append('\n');
        }
        result.append(cleaned);
    }

    private static boolean isQuestionPageSegment(String segment) {
        return segment != null && segment.matches("(?s).*\\\\subsection\\*?\\{第\\s*\\d+\\s*题}.*");
    }

    /** Keeps authored question-page content while hiding workspace-only labels. */
    private static String sanitizeQuestionPageSegment(String segment) {
        return segment
                .replaceAll("(?m)^\\\\(?:section|subsection|paragraph)\\*?\\{(?:作答|作答区|课堂作答区|我的解答|推导区|手写区|留白区|空白区|板书区|教师板书区)}\\s*$", "")
                .replaceAll("(?m)^\\\\(?:begin|end)\\{(?:center|itemize|enumerate)}\\s*$", "")
                .strip();
    }

    private static boolean isPageBreakCommand(String line) {
        String normalized = line == null ? "" : line.strip();
        return "\\clearpage".equals(normalized) || "\\newpage".equals(normalized);
    }

    private static boolean isWritingSpaceCommand(String line) {
        return line != null && line.strip().matches("^\\\\vspace\\{[0-9.]+em}$");
    }

    /** Converts circled list markers to portable ASCII markers because the configured body font may not contain them. */
    private static String normalizeCircledNumerals(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        StringBuilder builder = new StringBuilder(value.length());
        for (int index = 0; index < value.length(); index += 1) {
            char character = value.charAt(index);
            if (character >= '①' && character <= '⑨') {
                builder.append((int) (character - '①') + 1).append('.');
                if (index + 1 >= value.length() || !Character.isWhitespace(value.charAt(index + 1))) {
                    builder.append(' ');
                }
            } else if (character == '⑩') {
                builder.append("10.");
                if (index + 1 >= value.length() || !Character.isWhitespace(value.charAt(index + 1))) {
                    builder.append(' ');
                }
            } else {
                builder.append(character);
            }
        }
        return builder.toString();
    }

    /** Wraps a complete plus/minus fraction expression in one math environment. */
    private static String normalizeMixedMathDelimiters(String value) {
        Matcher matcher = MIXED_PLUS_MINUS_FRACTION.matcher(value == null ? "" : value);
        StringBuffer buffer = new StringBuffer();
        while (matcher.find()) {
            String replacement = "$%s=\\pm\\left(%s\\right)%s$"
                    .formatted(matcher.group(1), matcher.group(2), matcher.group(3));
            matcher.appendReplacement(buffer, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(buffer);
        Matcher undelimitedMatcher = UNDELIMITED_PLUS_MINUS_FRACTION.matcher(buffer.toString());
        StringBuffer normalized = new StringBuffer();
        while (undelimitedMatcher.find()) {
            String replacement = "$%s=\\pm\\left(%s\\right)%s$"
                    .formatted(undelimitedMatcher.group(1), undelimitedMatcher.group(2), undelimitedMatcher.group(3));
            undelimitedMatcher.appendReplacement(normalized, Matcher.quoteReplacement(replacement));
        }
        undelimitedMatcher.appendTail(normalized);
        return normalized.toString();
    }

    /** Keeps only the first (question) minipage from legacy lecture pages; the second was the teacher cue column. */
    private static String stripLectureProjectionColumns(String body) {
        if (body == null || body.isBlank()) {
            return body;
        }
        StringBuilder result = new StringBuilder(body.length());
        int questionMinipages = 0;
        int minipageDepth = 0;
        boolean dropping = false;
        boolean hasNumberedQuestion = false;
        for (String rawLine : body.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1)) {
            String line = rawLine.strip();
            if (line.matches("^\\\\subsection\\*?\\{第\\s*\\d+\\s*题.*")) {
                hasNumberedQuestion = true;
                questionMinipages = 0;
                dropping = false;
            }
            if ("\\clearpage".equals(line) || "\\newpage".equals(line)) {
                questionMinipages = 0;
                dropping = false;
                minipageDepth = 0;
            }
            if (line.startsWith("\\begin{minipage}")) {
                questionMinipages += 1;
                minipageDepth = 1;
                if (questionMinipages > 1) {
                    dropping = true;
                    continue;
                }
            } else if (dropping && line.startsWith("\\begin{minipage}")) {
                minipageDepth += 1;
            }
            if (dropping) {
                if (line.startsWith("\\begin{minipage}")) {
                    minipageDepth += 1;
                } else if (line.startsWith("\\end{minipage}")) {
                    minipageDepth -= 1;
                    if (minipageDepth <= 0) {
                        dropping = false;
                        minipageDepth = 0;
                    }
                }
                continue;
            }
            if (line.startsWith("\\end{minipage}")) {
                minipageDepth = 0;
            }
            result.append(rawLine).append('\n');
        }
        // A projection without a numbered atomic question is an old topic-only scaffold, not a printable slide.
        return hasNumberedQuestion ? result.toString().strip() : "";
    }

    /** Removes old student-only knowledge/explanation blocks while retaining actual question sections and blanks. */
    private static String stripStudentTeacherBlocks(String body) {
        if (body == null || body.isBlank()) {
            return body;
        }
        StringBuilder result = new StringBuilder(body.length());
        boolean dropping = false;
        int droppedLevel = Integer.MAX_VALUE;
        for (String rawLine : body.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1)) {
            String line = rawLine.strip();
            Matcher heading = LATEX_HEADING_LINE.matcher(line);
            if (heading.matches()) {
                int level = latexHeadingLevel(heading.group(1));
                String title = heading.group(2).replaceAll("\\s+", "");
                if (dropping && level <= droppedLevel) {
                    dropping = false;
                }
                if (List.of("知识速记", "题型识别", "注意", "作答提醒", "自检任务", "错因整理", "订正与错因")
                        .stream().anyMatch(title::contains)) {
                    dropping = true;
                    droppedLevel = level;
                    continue;
                }
            }
            if (dropping) {
                continue;
            }
            if (line.contains("作答提示：") || line.contains("自检任务") || line.contains("题型定位")
                    || line.contains("推导路径") || line.contains("结论核对")) {
                continue;
            }
            result.append(rawLine).append('\n');
        }
        return result.toString().strip();
    }

    /** Student publication keeps only explicitly marked question units; every teacher/explanation block is dropped. */
    private static String stripStudentQuestionUnits(String body) {
        if (body == null || body.isBlank()) {
            return "";
        }
        String[] lines = body.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        StringBuilder result = new StringBuilder(body.length());
        String pendingHeading = "";
        boolean inQuestion = false;
        boolean foundQuestion = false;
        for (String rawLine : lines) {
            String line = rawLine.strip();
            Matcher heading = LATEX_HEADING_LINE.matcher(line);
            boolean questionHeading = line.matches("^\\\\paragraph\\*?\\{题目}\\s*$");
            if (heading.matches() && (line.startsWith("\\section") || line.startsWith("\\subsection"))) {
                if (inQuestion) {
                    result.append("\\clearpage\n");
                    inQuestion = false;
                }
                pendingHeading = rawLine;
                continue;
            }
            if (questionHeading) {
                if (!pendingHeading.isBlank()) {
                    result.append(pendingHeading).append('\n');
                }
                result.append(rawLine).append('\n');
                pendingHeading = "";
                inQuestion = true;
                foundQuestion = true;
                continue;
            }
            if (inQuestion && ("\\clearpage".equals(line) || "\\newpage".equals(line))) {
                result.append(rawLine).append('\n');
                inQuestion = false;
                continue;
            }
            if (inQuestion) {
                if (line.contains("答案") || line.contains("解析") || line.contains("评分点")
                        || line.contains("作答提示") || line.contains("自检任务")
                        || line.contains("完成配套课后拓展习题")) {
                    continue;
                }
                result.append(rawLine).append('\n');
            }
        }
        return foundQuestion ? result.toString().strip() : "";
    }

    /** Removes persisted whole-paper OCR answers; a missing question-level answer must not be disguised as one. */
    private static String stripTeacherOcrAnswerBlocks(String body) {
        if (body == null || body.isBlank()) {
            return body;
        }
        StringBuilder result = new StringBuilder(body.length());
        boolean dropping = false;
        for (String rawLine : body.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1)) {
            String line = rawLine.strip();
            boolean answerHeading = line.matches("^\\\\paragraph\\*?\\{答案与评分点}\\s*$");
            boolean noisyAnswer = line.matches("(?s).*答案要点：.*(?:学科网|股份有限公司|第\\s*\\d+\\s*页).*" )
                    || line.matches("(?s).*(?:学科网|股份有限公司|第\\s*\\d+\\s*页/共\\s*\\d+\\s*页|【解析】|【分析】|【小问).*" );
            if (answerHeading || noisyAnswer) {
                dropping = true;
                continue;
            }
            if (dropping && (line.startsWith("\\paragraph{") || line.startsWith("\\subsection")
                    || line.startsWith("\\section"))) {
                dropping = false;
            }
            if (!dropping) {
                result.append(rawLine).append('\n');
            }
        }
        return result.toString().strip();
    }

    /** Repairs old task snapshots written as UTF-8 bytes decoded through a Latin-1 code page. */
    private static String repairMojibake(String value) {
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

    private static long mojibakeScore(String value) {
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
    private static String normalizeBareMathFragments(String value) {
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

    private static String rewriteBareMathAtoms(String value) {
        Matcher matcher = BARE_MATH_ATOM.matcher(value);
        StringBuffer rewritten = new StringBuffer();
        while (matcher.find()) {
            matcher.appendReplacement(rewritten, Matcher.quoteReplacement("$" + matcher.group(1) + "$"));
        }
        matcher.appendTail(rewritten);
        return rewritten.toString();
    }

    private static String removeVisibleWorkspaceLabels(String value) {
        String withoutReferences = VISIBLE_WORKSPACE_REFERENCE.matcher(value).replaceAll("独立完成");
        return VISIBLE_WORKSPACE_LABEL.matcher(withoutReferences).replaceAll("");
    }

    private static boolean isBlankWorkspaceHeading(String value) {
        String compact = safeText(value)
                .replaceAll("[_＿\\s:：，。,.;；、-]+", "")
                .strip();
        return List.of("作答", "作答区", "课堂作答区", "我的解答", "解答", "推导区", "空白区",
                "留白区", "留白", "手写区", "教师手写区", "板书留白", "板书区", "教师板书区")
                .contains(compact);
    }

    private static String removeEmptyTitledBlocks(String latex) {
        if (latex == null || latex.isBlank()) {
            return "";
        }
        String[] lines = latex.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        return renderNonEmptyTitleRange(lines, 0, lines.length).strip();
    }

    private static String renderNonEmptyTitleRange(String[] lines, int start, int end) {
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
            if (hasRealLatexContent(body)) {
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

    private static int latexHeadingLevel(String command) {
        String normalized = command == null ? "" : command.replace("*", "");
        return switch (normalized) {
            case "section" -> 1;
            case "subsection" -> 2;
            case "subsubsection", "paragraph" -> 3;
            default -> 4;
        };
    }

    private static boolean hasRealLatexContent(String body) {
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

    private static boolean isBlankWorkspaceLabelLine(String line) {
        String text = line == null ? "" : line.strip();
        if (text.isBlank()) {
            return false;
        }
        String compact = text
                .replaceAll("[_＿\\s:：，。,.;；、-]+", "")
                .strip();
        return List.of("作答", "留白区", "留白", "手写区", "教师手写区", "板书留白", "板书区", "教师板书区").contains(compact);
    }

    private static boolean isBlankOnlyLatexLine(String line) {
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

    private static String renderLatexBody(String sanitizedBody) {
        StringBuilder builder = new StringBuilder();
        String[] lines = safeText(sanitizedBody).replace("\r\n", "\n").replace('\r', '\n').split("\n");
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

    private static String renderLatexImageBlock(List<HandoutImage> images) {
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

    private static String renderLatexImageRowCell(HandoutImage image) {
        return """
                \\begin{minipage}[t]{0.48\\linewidth}
                \\centering
                %s
                \\end{minipage}
                """.formatted(renderLatexImageCell(image, "\\linewidth", "0.24\\textheight"));
    }

    private static String renderLatexImageCell(HandoutImage image, String width, String maxHeight) {
        Optional<Path> localPath = existingLocalImagePath(image.path());
        String caption = INLINE_FIGURE_TRANSPORT_ALT.equals(safeText(image.alt())) ? "" : safeText(image.alt());
        StringBuilder builder = new StringBuilder();
        if (localPath.isPresent()) {
            builder.append("\\includegraphics[width=")
                    .append(width)
                    .append(",height=")
                    .append(maxHeight)
                    .append("]{")
                    .append(latexImagePath(localPath.get()))
                    .append("}\n");
        } else {
            builder.append("\\fbox{\\parbox[c][")
                    .append(maxHeight)
                    .append("][c]{")
                    .append(width)
                    .append("}{\\centering 图片未找到");
            if (!caption.isBlank()) {
                builder.append("\\\\").append(latexText(caption));
            }
            builder.append("}}\n");
        }
        if (!caption.isBlank()) {
            builder.append("{\\small ").append(latexText(caption)).append("\\par}\n");
        }
        return builder.toString();
    }

    private static String latexImagePath(Path path) {
        return "\\detokenize{" + path.toAbsolutePath().normalize().toString().replace('\\', '/') + "}";
    }

    private static String escapeLooseTextSpecials(String value) {
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
    private static String normalizeLegacyLatexForExport(String value) {
        String normalized = value
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
                .replace("\\{", "{")
                .replace("\\}", "}");
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
    private static String escapeLooseTextSpecialsPreservingImageMarkers(String value) {
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

    private static String latexText(String value) {
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

    private static String normalizeLegacyLatexText(String value) {
        return safeText(value)
                .replace("\\textasciicircum{}", "^")
                .replace("\\textasciitilde{}", "~")
                .replace("\\textbackslash{}frac", "\\frac")
                .replace("\\textbackslash{}sqrt", "\\sqrt");
    }

    private static String hex(Color color) {
        return "%02X%02X%02X".formatted(color.getRed(), color.getGreen(), color.getBlue());
    }

    private static String tail(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value == null ? "" : value;
        }
        return value.substring(value.length() - maxLength);
    }

    private static int countPages(byte[] pdfBytes) {
        try (PDDocument document = org.apache.pdfbox.Loader.loadPDF(pdfBytes)) {
            return document.getNumberOfPages();
        } catch (IOException exception) {
            return 0;
        }
    }

    private static void deleteRecursively(Path root) {
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
     * Converts a small LaTeX subset into human-readable text lines.
     */
    private static List<ReadableLine> readableLines(String latex) {
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

    private static boolean isDiagnosticLine(String line) {
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

    private static boolean isEvidenceHeading(String heading) {
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

    private static boolean isLegacyMetadataHeading(String heading) {
        String text = safeText(heading).replaceAll("\\s+", "");
        return text.contains("讲义模板") || text.contains("模板与版式");
    }

    private static boolean isForbiddenWorkflowHeading(String heading) {
        String text = safeText(heading).replaceAll("\\s+", "");
        return text.equals("题目入口")
                || text.equals("讲评入口")
                || text.equals("题目/任务")
                || text.equals("题型入口")
                || text.equals("知识入口")
                || text.equals("审题提醒")
                || text.equals("本讲题干");
    }

    private static boolean isVersionOnlyHeading(String heading) {
        String text = safeText(heading).replaceAll("\\s+", "");
        return "教师版".equals(text) || "学生版".equals(text);
    }

    private static boolean isTextbookBodyHeading(String heading) {
        String text = safeText(heading).replaceAll("\\s+", "");
        return "正文".equals(text)
                || "原文".equals(text)
                || "OCR正文".equalsIgnoreCase(text)
                || "教材正文".equals(text);
    }

    private static boolean isInternalLayoutInstruction(String line) {
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
    private static boolean isTemplateMetadataLine(String line) {
        String text = safeText(line).replaceAll("\\s+", "");
        return text.startsWith("模板：")
                || text.startsWith("模板:")
                || text.startsWith("版本：")
                || text.startsWith("版本:");
    }

    private static boolean isUnreadablePlaceholderLine(String line) {
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

    private static boolean isLatexDocumentScaffoldLine(String line) {
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

    private static List<HandoutImage> extractMarkdownImages(String line) {
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

    private static String normalizeImageReference(String rawPath) {
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

    private static String toImageMarker(HandoutImage image) {
        return "[[HANDOUTIMAGE:"
                + Base64.getEncoder().withoutPadding().encodeToString(safeText(image.alt()).getBytes(StandardCharsets.UTF_8))
                + ":"
                + Base64.getEncoder().withoutPadding().encodeToString(safeText(image.path()).getBytes(StandardCharsets.UTF_8))
                + "]]";
    }

    private static Optional<HandoutImage> parseImageMarker(String line) {
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

    private static Optional<Path> existingLocalImagePath(String reference) {
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

    private static boolean isMarkdownImageOnlyLine(String line) {
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

    private static String compactEvidenceReference(String value) {
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
    private static String compactLegacyPdfEvidenceLine(String value) {
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
    private static List<ReadableLine> compactBlanks(List<ReadableLine> lines) {
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
    private static void addBlank(List<ReadableLine> lines) {
        if (!lines.isEmpty() && lines.get(lines.size() - 1).type() != LineType.BLANK) {
            lines.add(new ReadableLine(LineType.BLANK, ""));
        }
    }

    /**
     * Produces readable text from common LaTeX commands without exposing raw source syntax.
     */
    private static String cleanText(String value) {
        String cleaned = safeText(value);
        cleaned = cleaned
                .replace("AI教师讲解草稿", "教师讲解稿")
                .replace("AI 讲义草稿", "讲义内容生成")
                .replace("AI生成状态", "生成状态")
                .replace("\\textbackslash{}", "\\")
                .replace("\\textbackslash", "\\")
                .replace("\\_", "_");
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
        return cleaned;
    }

    /**
     * Converts common LaTeX scripts to readable Unicode so formulas do not leak raw ^/_ syntax in PDFs.
     */
    private static String normalizeMathScripts(String value) {
        String cleaned = value;
        cleaned = replaceScript(SUPERSCRIPT_BRACED, cleaned, true);
        cleaned = replaceScript(SUPERSCRIPT_SIMPLE, cleaned, true);
        cleaned = replaceScript(SUBSCRIPT_BRACED, cleaned, false);
        cleaned = replaceScript(SUBSCRIPT_SIMPLE, cleaned, false);
        return cleaned;
    }

    private static String replaceScript(Pattern pattern, String value, boolean superscript) {
        Matcher matcher = pattern.matcher(value);
        StringBuffer buffer = new StringBuffer();
        while (matcher.find()) {
            matcher.appendReplacement(buffer, Matcher.quoteReplacement(scriptText(matcher.group(1), superscript)));
        }
        matcher.appendTail(buffer);
        return buffer.toString();
    }

    private static String scriptText(String value, boolean superscript) {
        StringBuilder builder = new StringBuilder();
        safeText(value).codePoints().forEach(codePoint -> builder.append(scriptChar(codePoint, superscript)));
        return builder.toString();
    }

    private static String scriptChar(int codePoint, boolean superscript) {
        return switch (codePoint) {
            case '0' -> superscript ? "⁰" : "₀";
            case '1' -> superscript ? "¹" : "₁";
            case '2' -> superscript ? "²" : "₂";
            case '3' -> superscript ? "³" : "₃";
            case '4' -> superscript ? "⁴" : "₄";
            case '5' -> superscript ? "⁵" : "₅";
            case '6' -> superscript ? "⁶" : "₆";
            case '7' -> superscript ? "⁷" : "₇";
            case '8' -> superscript ? "⁸" : "₈";
            case '9' -> superscript ? "⁹" : "₉";
            case '+' -> superscript ? "⁺" : "₊";
            case '-' -> superscript ? "⁻" : "₋";
            case '=' -> superscript ? "⁼" : "₌";
            case '(' -> superscript ? "⁽" : "₍";
            case ')' -> superscript ? "⁾" : "₎";
            case 'a' -> superscript ? "ᵃ" : "ₐ";
            case 'e' -> superscript ? "ᵉ" : "ₑ";
            case 'h' -> superscript ? "ʰ" : "ₕ";
            case 'i' -> superscript ? "ⁱ" : "ᵢ";
            case 'j' -> superscript ? "ʲ" : "ⱼ";
            case 'k' -> superscript ? "ᵏ" : "ₖ";
            case 'l' -> superscript ? "ˡ" : "ₗ";
            case 'm' -> superscript ? "ᵐ" : "ₘ";
            case 'n' -> superscript ? "ⁿ" : "ₙ";
            case 'o' -> superscript ? "ᵒ" : "ₒ";
            case 'p' -> superscript ? "ᵖ" : "ₚ";
            case 'r' -> superscript ? "ʳ" : "ᵣ";
            case 's' -> superscript ? "ˢ" : "ₛ";
            case 't' -> superscript ? "ᵗ" : "ₜ";
            case 'u' -> superscript ? "ᵘ" : "ᵤ";
            case 'v' -> superscript ? "ᵛ" : "ᵥ";
            case 'x' -> superscript ? "ˣ" : "ₓ";
            default -> Character.toString(codePoint);
        };
    }

    /**
     * Applies one regex replacement until nested simple commands no longer match.
     */
    private static String replaceRepeated(Pattern pattern, String value, String replacement) {
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
    private static String versionTitle(String version) {
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
    private static String templateNameForVersion(TeachingTaskResponse task, String version) {
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
    private static String safeText(String value) {
        return value == null ? "" : value.strip();
    }

    /**
     * Returns fallback text for blank values.
     */
    private static String nonBlank(String value, String fallback) {
        String text = safeText(value);
        return text.isBlank() ? fallback : text;
    }

    private static String safeHeaderTopic(String value) {
        String text = safeText(value);
        return isUnreadablePlaceholderLine(text) ? "历史讲义" : nonBlank(text, "历史讲义");
    }

    /**
     * Defends both renderers from legacy snapshots and unsafe text. The request layer performs the same normalization;
     * retaining this boundary means old persisted records cannot bypass safe LaTeX escaping by direct export.
     */
    private static String normalizedWatermark(String value) {
        String normalized = safeText(value).replaceAll("[\\p{Cntrl}]", "").replaceAll("\\s+", " ").strip();
        return normalized.isEmpty() || "飞猪数学".equals(normalized) ? "数学讲义" : normalized;
    }

    /**
     * Adds page numbers and footer metadata after the document page count is known.
     */
    private static void addPageFooters(
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

    private static float footerMargin(PdfStyle style, boolean zhaoTemplate) {
        if (style.isLecture()) {
            return LECTURE_MARGIN;
        }
        return zhaoTemplate ? ZHAO_CONTENT_MARGIN : MARGIN;
    }

    /**
     * Shared safe text encoding helper for footer/header overlays.
     */
    private static String supportedText(PDFont font, String value) {
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

    private static float textWidth(PDFont font, String text, float fontSize) {
        try {
            return font.getStringWidth(supportedText(font, text)) / 1000f * fontSize;
        } catch (IOException exception) {
            return safeText(text).length() * fontSize * 0.55f;
        }
    }

    /**
     * Logical output line type.
     */
    private enum LineType {
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
    private record ReadableLine(LineType type, String text, List<HandoutImage> images) {
        private ReadableLine(LineType type, String text) {
            this(type, text, List.of());
        }
    }

    private record HandoutImage(String alt, String path) {
    }

    /**
     * Visual parameters for one handout version.
     */
    private record PdfStyle(
            Color accent,
            Color accentDark,
            Color accentLight,
            Color titleText,
            Color bodyText,
            Color mutedText,
            Color border,
            String versionLabel) {

        private static PdfStyle forVersion(String version, String templateName) {
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

        private static boolean isZhaoLixianTemplate(String templateName) {
            String normalized = templateName == null ? "" : templateName.toLowerCase(Locale.ROOT);
            // Template codes are snake_case (for example zhao_lixian_topic_v1), while display
            // names are natural language.  Compare a separator-free ASCII identity so both routes
            // select the same renderer without depending on a mutable UI label.
            String compactAscii = normalized.replaceAll("[^a-z0-9]", "");
            return normalized.contains("赵礼显") || compactAscii.contains("zhaolixian");
        }

        private boolean isLecture() {
            return "横版讲解".equals(versionLabel);
        }
    }

    /**
     * Small paginated PDF text writer.
     */
    private static final class PdfWriter {
        private final PDDocument document;
        private final PDFont font;
        private final PdfStyle style;
        private final String title;
        private final String templateName;
        private final String watermark;
        private PDPageContentStream stream;
        private float y;
        private int pageNumber;

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

        private void writeTitle(String text) throws IOException {
            write(new ReadableLine(LineType.TITLE, text));
        }

        private void writeHeading(String text) throws IOException {
            write(new ReadableLine(LineType.HEADING, text));
        }

        private void writeParagraph(String text) throws IOException {
            for (String paragraph : safeText(text).split("\\R")) {
                if (paragraph.isBlank()) {
                    write(new ReadableLine(LineType.BLANK, ""));
                } else {
                    write(new ReadableLine(LineType.PARAGRAPH, paragraph));
                }
            }
        }

        private void writeMuted(String text) throws IOException {
            write(new ReadableLine(LineType.MUTED, text));
        }

        private void writeBlank() throws IOException {
            write(new ReadableLine(LineType.BLANK, ""));
        }

        private void write(ReadableLine line) throws IOException {
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

        private void writeImageBlock(List<HandoutImage> images) throws IOException {
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

        private void drawImageCell(HandoutImage image, float left, float top, float width, float reservedHeight) throws IOException {
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

        private void writeCenteredSmallText(String text, float left, float baseline, float width) throws IOException {
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

        private String truncateCaption(String text, float width) {
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

        private void writeHeadingBlock(String text) throws IOException {
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

        private Color textColor(LineType lineType) {
            return switch (lineType) {
                case TITLE -> style.titleText();
                case MUTED -> style.mutedText();
                default -> style.bodyText();
            };
        }

        private void ensureSpace(float required) throws IOException {
            if (y - required < pageMargin()) {
                newPage();
            }
        }

        private void newPage() throws IOException {
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

        private void drawHeader() throws IOException {
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
        private void drawZhaoHeader() throws IOException {
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

        private PDRectangle pageRectangle() {
            if (style.isLecture()) {
                return new PDRectangle(LECTURE_PAGE_WIDTH, LECTURE_PAGE_HEIGHT);
            }
            return isZhaoTemplate() ? new PDRectangle(ZHAO_PAGE_WIDTH, ZHAO_PAGE_HEIGHT) : PDRectangle.A4;
        }

        private float pageWidth() {
            if (style.isLecture()) {
                return LECTURE_PAGE_WIDTH;
            }
            return isZhaoTemplate() ? ZHAO_PAGE_WIDTH : PAGE_WIDTH;
        }

        private float pageHeight() {
            if (style.isLecture()) {
                return LECTURE_PAGE_HEIGHT;
            }
            return isZhaoTemplate() ? ZHAO_PAGE_HEIGHT : PAGE_HEIGHT;
        }

        private float pageMargin() {
            if (style.isLecture()) {
                return LECTURE_MARGIN;
            }
            return isZhaoTemplate() ? ZHAO_CONTENT_MARGIN : MARGIN;
        }

        private boolean isStudentWorksheetStyle() {
            return "学生版".equals(style.versionLabel());
        }

        private boolean isZhaoTemplate() {
            return PdfStyle.isZhaoLixianTemplate(templateName);
        }

        private void close() throws IOException {
            if (stream != null) {
                stream.close();
                stream = null;
            }
        }

        private static List<String> wrap(String text, int maxUnits) {
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
