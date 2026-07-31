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
 * TeachingHandoutPdfExportPolicyPartA isolates deterministic PDF export policy from process orchestration.
 * It contains no mutable request state; the facade remains responsible for renderer selection and cleanup.
 */
final class TeachingHandoutPdfExportPolicyPartA {
    private TeachingHandoutPdfExportPolicyPartA() {
        // Static policy component: construction would create state with no owner.
    }


    /**
     * Rejects historically persisted source defects before either renderer is allowed to make them look legitimate.
     *
     * <p>This boundary intentionally validates the pre-sanitized snapshot: sanitizing can remove a visible banner,
     * but it cannot restore the missing relation or missing geometry diagram that the learner needs to solve a
     * question. All protected preview, download and ZIP paths call {@link #renderForPublication}, so one check keeps
     * their outcome identical.</p>

     */
    static void validatePublicationSource(TeachingTaskResponse task, String version) {
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
    static void validateQuestionPublicationUnits(String sanitized, String version) {
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
    static List<String> numberedQuestionUnits(String source) {
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
    static int numberedQuestionCount(String source) {
        return numberedQuestionUnits(source).size();
    }


    /** Accepts an image only when its marker parses and its local, permission-checked path still exists at export. */
    static boolean hasAuthorizedImage(String unit) {
        for (String line : safeText(unit).split("\\R")) {
            Optional<HandoutImage> image = parseImageMarker(line.strip());
            if (image.isPresent() && existingLocalImagePath(image.get().path()).isPresent()) {
                return true;
            }
        }
        return false;
    }


    /** Compares a question's visible prompt only, so a distinct solution cannot hide a duplicated source question. */
    static String questionFingerprint(String unit) {
        String visible = cleanText(unit)
                .replaceAll("第\\s*\\d+\\s*题", "")
                .replaceAll("[^\\p{IsHan}A-Za-z0-9]", "")
                .toLowerCase(Locale.ROOT);
        return visible.length() < 12 ? "" : visible;
    }


    /** Rejects the known fallback or a blank answer block rather than making a template warning look teachable. */
    static boolean hasUnverifiedTeacherAnswer(String unit) {
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
    static boolean hasSubstantiveTeacherSolution(String unit) {
        String visible = cleanText(unit).replaceAll("\\s+", " ").strip();
        if (visible.length() < 180) {
            return false;
        }
        boolean hasDerivation = visible.matches(".*(?:条件识别|推导依据|步骤|由.{1,40}|计算|证明).*");
        boolean hasConclusion = visible.matches(".*(?:因此|故|结论|答案).*");
        return hasDerivation && hasConclusion;
    }


    /** Copies a bundled visual master asset into the isolated XeLaTeX work directory. */
    static void materializeBundledLatexAsset(Path workDir, String assetName) throws IOException {
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
    static Process runXeLaTeX(Path engine, Path workDir, Path source, Path compilerOutput) throws IOException, InterruptedException {
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


    static Optional<Path> latexEnginePath() {
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


    static boolean containsStructuredSections(String source) {
        for (String rawLine : safeText(source).replace("\r\n", "\n").replace('\r', '\n').split("\n")) {
            if (SECTION_COMMAND.matcher(rawLine.strip()).matches()) {
                return true;
            }
        }
        return false;
    }


    static String fullLatexDocument(TeachingTaskResponse task, String version) {
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
    static String insertLectureQuestionBreaks(String body) {
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
    static String insertPrintedQuestionSpacing(String body) {
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


    static boolean endsWithPageBreak(StringBuilder text) {
        return text.toString().endsWith("\\clearpage\n") || text.toString().endsWith("\\newpage\n");
    }


    static String genericHeaderFooterCommands() {
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
    static String zhaoHeaderFooterCommands(String watermark) {
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


    static String genericHeadingCommands() {
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

    static String zhaoHeadingCommands() {
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


    static String stripMalformedEnvironmentPrefix(String value) {
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
    static String cleanBlocksPreservingPageBreaks(String latex) {
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


    static void appendCleanSegment(StringBuilder result, StringBuilder segment) {
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


    static boolean isQuestionPageSegment(String segment) {
        return segment != null && segment.matches("(?s).*\\\\subsection\\*?\\{第\\s*\\d+\\s*题}.*");
    }


    /** Keeps authored question-page content while hiding workspace-only labels. */
    static String sanitizeQuestionPageSegment(String segment) {
        return segment
                .replaceAll("(?m)^\\\\(?:section|subsection|paragraph)\\*?\\{(?:作答|作答区|课堂作答区|我的解答|推导区|手写区|留白区|空白区|板书区|教师板书区)}\\s*$", "")
                .replaceAll("(?m)^\\\\(?:begin|end)\\{(?:center|itemize|enumerate)}\\s*$", "")
                .strip();
    }


    static boolean isPageBreakCommand(String line) {
        String normalized = line == null ? "" : line.strip();
        return "\\clearpage".equals(normalized) || "\\newpage".equals(normalized);
    }


    static boolean isWritingSpaceCommand(String line) {
        return line != null && line.strip().matches("^\\\\vspace\\{[0-9.]+em}$");
    }


    /** Converts circled list markers to portable ASCII markers because the configured body font may not contain them. */
    static String normalizeCircledNumerals(String value) {
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
    static String normalizeMixedMathDelimiters(String value) {
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


    /** Returns whether this runtime forbids publishing the lossy PDFBox fallback. */
    static boolean requiresXeLaTeX() {
        return Boolean.parseBoolean(System.getenv().getOrDefault("MATH_AGENT_REQUIRE_XELATEX", "false"));
    }


    /** Keeps only the first (question) minipage from legacy lecture pages; the second was the teacher cue column. */
    static String stripLectureProjectionColumns(String body) {
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
    static String stripStudentTeacherBlocks(String body) {
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
    static String stripStudentQuestionUnits(String body) {
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
    static String stripTeacherOcrAnswerBlocks(String body) {
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
}
