package com.doob.mathagent.teaching.service;

import com.doob.mathagent.infrastructure.text.FormulaMarkupSanitizer;
import com.doob.mathagent.teaching.vo.TeachingTaskResponse;
import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
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
    private static final Pattern SQRT_COMMAND = Pattern.compile("\\\\sqrt\\{([^{}]+)}");
    private static final Pattern SUPERSCRIPT_BRACED = Pattern.compile("\\^\\{([^{}]+)}");
    private static final Pattern SUPERSCRIPT_SIMPLE = Pattern.compile("\\^([0-9a-zA-Z+-])");
    private static final Pattern SUBSCRIPT_BRACED = Pattern.compile("_\\{([^{}]+)}");
    private static final Pattern SUBSCRIPT_SIMPLE = Pattern.compile("_([0-9a-zA-Z+-])");
    private static final Pattern VSPACE_COMMAND = Pattern.compile("\\\\vspace\\{([0-9.]+)em}");
    private static final Pattern UNDERLINE_HSPACE_COMMAND = Pattern.compile("\\\\underline\\{\\\\hspace\\{[0-9.]+em}}");
    private static final Pattern VISIBLE_WORKSPACE_LABEL = Pattern.compile(
            "(?:课堂作答区|作答区|我的解答|推导区|手写区|教师手写区|留白区|空白区|板书区|教师板书区)\\s*[：:]?");
    private static final Pattern VISIBLE_WORKSPACE_REFERENCE = Pattern.compile(
            "(?:写在|填写在|完成在|放在|留在)(?:课堂作答区|作答区|我的解答|推导区|手写区|教师手写区|留白区|空白区|板书区|教师板书区)");
    private static final Pattern MARKDOWN_IMAGE = Pattern.compile("!\\[([^\\]]*)]\\(([^)]+)\\)");
    private static final Pattern IMAGE_MARKER = Pattern.compile("\\[\\[HANDOUTIMAGE:([A-Za-z0-9+/]+):([A-Za-z0-9+/]+)]]");
    private static final Duration LATEX_TIMEOUT = Duration.ofSeconds(45);
    private static final float PAGE_WIDTH = PDRectangle.A4.getWidth();
    private static final float PAGE_HEIGHT = PDRectangle.A4.getHeight();
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
            PDFont font = loadReadableFont(document);
            PdfStyle style = PdfStyle.forVersion(version);
            String title = versionTitle(version);
            String templateName = task.selectedTemplate() == null ? "标准讲义" : task.selectedTemplate().displayName();
            String handoutSource = sanitizeLatexForExport(task.handoutLatexFor(version));
            boolean hasStructuredBody = containsStructuredSections(handoutSource);
            PdfWriter writer = new PdfWriter(document, font, style, title, templateName);
            writer.writeMuted("任务编号：" + safeText(task.taskId()));
            writer.writeMuted("模板：" + templateName + "　版本：" + title);
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
            addPageFooters(document, font, style, title);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.save(out);
            byte[] bytes = out.toByteArray();
            return new RenderedHandoutPdf(bytes, "pdfbox_fallback", document.getNumberOfPages());
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to render teaching handout PDF", exception);
        }
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
        PdfStyle style = PdfStyle.forVersion(version);
        String title = versionTitle(version);
        String templateName = task.selectedTemplate() == null ? "标准讲义" : task.selectedTemplate().displayName();
        String body = renderLatexBody(sanitizeLatexForExport(task.handoutLatexFor(version)));
        String headerTopic = safeHeaderTopic(task.learningGoal());
        String documentOptions = style.isLecture() ? "10pt" : "11pt,a4paper";
        String geometryOptions = style.isLecture()
                ? "paperwidth=16cm,paperheight=10cm,top=8mm,bottom=8mm,left=10mm,right=10mm"
                : "a4paper,top=24mm,bottom=23mm,left=22mm,right=22mm";
        String bodySizeCommand = style.isLecture() ? "\\small" : "";
        return """
                \\documentclass[%s]{article}
                \\usepackage[%s]{geometry}
                \\usepackage{fontspec}
                \\usepackage{xeCJK}
                \\usepackage{xcolor}
                \\usepackage{amsmath,amssymb}
                \\usepackage{graphicx}
                \\usepackage{caption}
                \\usepackage{enumitem}
                \\usepackage{fancyhdr}
                \\usepackage{lastpage}
                \\usepackage{titlesec}
                \\IfFontExistsTF{Noto Sans SC}{\\setCJKmainfont{Noto Sans SC}}{\\IfFontExistsTF{Microsoft YaHei UI}{\\setCJKmainfont{Microsoft YaHei UI}}{\\IfFontExistsTF{SimSun}{\\setCJKmainfont{SimSun}}{}}}
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
                \\definecolor{HandoutText}{HTML}{0F172A}
                \\pagestyle{fancy}
                \\fancyhf{}
                \\lhead{%s}
                \\rhead{%s}
                \\lfoot{%s}
                \\rfoot{第 \\thepage 页 / 共 \\pageref{LastPage} 页}
                \\renewcommand{\\headrulewidth}{0.4pt}
                \\renewcommand{\\footrulewidth}{0.3pt}
                \\color{HandoutText}
                \\titleformat{\\section}
                  {\\Large\\bfseries\\color{HandoutAccent}}
                  {}{0pt}{\\makebox[0pt][r]{\\color{HandoutAccent}\\rule{4pt}{1.15em}\\hspace{0.7em}}}
                  [{\\vspace{0.2em}\\color{HandoutAccent!35}\\titlerule[0.5pt]}]
                \\titleformat{\\subsection}
                  {\\large\\bfseries\\color{HandoutAccent}}
                  {}{0pt}{\\makebox[0pt][r]{\\color{HandoutAccent!80}\\rule{3pt}{1em}\\hspace{0.6em}}}
                \\titleformat{\\paragraph}{\\normalsize\\bfseries\\color{HandoutAccent}}{}{0pt}{}
                \\titlespacing*{\\section}{0pt}{1.45em}{0.8em}
                \\titlespacing*{\\subsection}{0pt}{1.1em}{0.55em}
                \\begin{document}
                \\begin{center}
                {\\LARGE\\bfseries %s}\\\\[0.35em]
                {\\small 模板：%s \\quad 版本：%s}
                \\end{center}
                \\vspace{0.6em}
                %s
                %s
                \\end{document}
                """.formatted(
                documentOptions,
                geometryOptions,
                hex(style.accent()),
                hex(style.accentLight()),
                latexText(title),
                latexText(headerTopic),
                latexText(templateName),
                latexText(title),
                latexText(templateName),
                latexText(title),
                bodySizeCommand,
                body);
    }

    /**
     * Produces the canonical LaTeX body used by preview, download, ZIP export, and PDF rendering.
     * This is a last-resort guard for old tasks or model output that still contains internal layout
     * instructions, OCR page fragments, or provider diagnostics while preserving real handout images.
     */
    public static String sanitizeLatexForExport(String source) {
        String normalized = safeText(source)
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
        List<String> lines = new ArrayList<>();
        boolean inEvidenceSection = false;
        boolean skippingTextbookBody = false;
        boolean skippingLegacyMetadataSection = false;
        boolean skippingBlankWorkspaceSection = false;
        int evidenceLineCount = 0;
        for (String rawLine : normalized.replace("\r\n", "\n").replace('\r', '\n').split("\n")) {
            String line = rawLine.strip();
            line = line
                    .replace("AI教师讲解草稿", "教师讲解稿")
                    .replace("AI 讲义草稿", "讲义内容生成")
                    .replace("AI生成状态", "生成状态");
            Matcher section = SECTION_COMMAND.matcher(line);
            if (section.matches()) {
                String heading = cleanText(section.group(1));
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
            if (inEvidenceSection) {
                line = compactEvidenceReference(line);
                if (line.isBlank() || evidenceLineCount >= 4) {
                    continue;
                }
                evidenceLineCount += 1;
                line = "- " + line;
            }
            List<HandoutImage> images = inEvidenceSection ? List.of() : extractMarkdownImages(line);
            if (!images.isEmpty()) {
                String textOnly = MARKDOWN_IMAGE.matcher(line).replaceAll("").strip();
                if (!textOnly.isBlank()) {
                    lines.add(textOnly);
                }
                for (HandoutImage image : images) {
                    lines.add(toImageMarker(image));
                }
                continue;
            }
            lines.add(line);
        }
        return removeEmptyTitledBlocks(escapeLooseTextSpecials(String.join("\n", lines)));
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
                builder.append(lines[index].strip()).append('\n').append(body).append("\n\n");
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
        return List.of("留白区", "留白", "手写区", "教师手写区", "板书留白", "板书区", "教师板书区").contains(compact);
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
        String caption = safeText(image.alt());
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
        value = FormulaMarkupSanitizer.sanitizeFeishuMath(safeText(value));
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
        String text = safeText(heading);
        return text.contains("证据")
                || text.contains("来源")
                || text.contains("教材与资料")
                || text.contains("资料证据");
    }

    private static boolean isLegacyMetadataHeading(String heading) {
        String text = safeText(heading).replaceAll("\\s+", "");
        return "讲义模板与版式".equals(text);
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

    private static boolean isUnreadablePlaceholderLine(String line) {
        String text = safeText(line).replaceAll("\\s+", "");
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
            return Optional.of(new HandoutImage(
                    new String(Base64.getDecoder().decode(matcher.group(1)), StandardCharsets.UTF_8),
                    new String(Base64.getDecoder().decode(matcher.group(2)), StandardCharsets.UTF_8)));
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
     * Adds page numbers and footer metadata after the document page count is known.
     */
    private static void addPageFooters(PDDocument document, PDFont font, PdfStyle style, String title) throws IOException {
        int totalPages = document.getNumberOfPages();
        for (int index = 0; index < totalPages; index += 1) {
            PDPage page = document.getPage(index);
            PDRectangle box = page.getMediaBox();
            float pageWidth = box.getWidth();
            float margin = footerMargin(style);
            try (PDPageContentStream footer = new PDPageContentStream(document, page, AppendMode.APPEND, true, true)) {
                footer.setStrokingColor(style.border());
                footer.setLineWidth(0.4f);
                footer.moveTo(margin, 38);
                footer.lineTo(pageWidth - margin, 38);
                footer.stroke();

                footer.beginText();
                footer.setFont(font, 8.8f);
                footer.setNonStrokingColor(style.mutedText());
                footer.newLineAtOffset(margin, 24);
                footer.showText(supportedText(font, title));
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

    private static float footerMargin(PdfStyle style) {
        return style.isLecture() ? LECTURE_MARGIN : MARGIN;
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

        private static PdfStyle forVersion(String version) {
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
        private PDPageContentStream stream;
        private float y;
        private int pageNumber;

        private PdfWriter(PDDocument document, PDFont font, PdfStyle style, String title, String templateName) throws IOException {
            this.document = document;
            this.font = font;
            this.style = style;
            this.title = title;
            this.templateName = templateName;
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
            String caption = safeText(image.alt());
            float captionHeight = caption.isBlank() ? 0f : 18f;
            float imageAreaHeight = Math.max(72f, reservedHeight - captionHeight - 8f);
            Optional<Path> localPath = existingLocalImagePath(image.path());
            if (localPath.isPresent()) {
                PDImageXObject pdImage = PDImageXObject.createFromFileByExtension(localPath.get().toFile(), document);
                float scale = Math.min(width / pdImage.getWidth(), imageAreaHeight / pdImage.getHeight());
                float drawWidth = pdImage.getWidth() * scale;
                float drawHeight = pdImage.getHeight() * scale;
                float drawX = left + (width - drawWidth) / 2f;
                float drawY = top - drawHeight;
                stream.drawImage(pdImage, drawX, drawY, drawWidth, drawHeight);
            } else {
                float boxHeight = Math.min(imageAreaHeight, 108f);
                stream.setStrokingColor(style.border());
                stream.setLineWidth(0.45f);
                stream.addRect(left, top - boxHeight, width, boxHeight);
                stream.stroke();
                writeCenteredSmallText("图片未找到", left, top - boxHeight / 2f + 8f, width);
                if (!caption.isBlank()) {
                    writeCenteredSmallText(caption, left + 10f, top - boxHeight / 2f - 8f, width - 20f);
                }
            }
            if (!caption.isBlank()) {
                stream.beginText();
                stream.setFont(font, IMAGE_CAPTION_SIZE);
                stream.setNonStrokingColor(style.mutedText());
                stream.newLineAtOffset(left, top - imageAreaHeight - 14f);
                stream.showText(supportedText(font, truncateCaption(caption, width)));
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
            if (!isStudentWorksheetStyle()) {
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
            stream.setNonStrokingColor(style.accentDark());
            stream.newLineAtOffset(isStudentWorksheetStyle() ? pageMargin() : pageMargin() + 12, y);
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
            y = isStudentWorksheetStyle() ? pageHeight() - 132 : pageHeight() - 104;
        }

        private void drawHeader() throws IOException {
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
                stream.showText(supportedText(font, "数学讲义"));
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
            stream.showText(supportedText(font, "模板：" + templateName + " · 第 " + pageNumber + " 页"));
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

        private PDRectangle pageRectangle() {
            return style.isLecture() ? new PDRectangle(LECTURE_PAGE_WIDTH, LECTURE_PAGE_HEIGHT) : PDRectangle.A4;
        }

        private float pageWidth() {
            return style.isLecture() ? LECTURE_PAGE_WIDTH : PAGE_WIDTH;
        }

        private float pageHeight() {
            return style.isLecture() ? LECTURE_PAGE_HEIGHT : PAGE_HEIGHT;
        }

        private float pageMargin() {
            return style.isLecture() ? LECTURE_MARGIN : MARGIN;
        }

        private boolean isStudentWorksheetStyle() {
            return "学生版".equals(style.versionLabel());
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
