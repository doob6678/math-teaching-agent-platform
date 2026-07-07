package com.doob.mathagent.teaching;

import static org.assertj.core.api.Assertions.assertThat;

import com.doob.mathagent.teaching.service.TeachingHandoutPdfExportService;
import com.doob.mathagent.teaching.vo.TeachingTaskResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

class TeachingHandoutPdfExportServiceTest {

    @Test
    void sanitizesLatexExportBeforeTexDownloadAndPdfCompilation() {
        String sanitized = TeachingHandoutPdfExportService.sanitizeLatexForExport("""
                \\documentclass[11pt,a4paper]{article}
                \\usepackage{fancyhdr}
                \\usepackage{xcolor}
                \\pagestyle{fancy}
                \\fancyhf{}
                \\lhead{教师版讲义}
                \\rhead{双曲线}
                \\lfoot{模板信息}
                \\rfoot{第 \\thepage 页}
                \\definecolor{HandoutAccent}{HTML}{0F766E}
                \\titleformat{\\section}{\\Large\\bfseries}{}{0pt}{}
                \\begin{document}
                \\begin{center}
                {\\LARGE\\bfseries 双曲线讲义}
                \\end{center}
                \\section{讲义模板与版式}
                PDF 版式要求：页眉展示主题和版本，页脚展示页码；教师版使用讲评色，学生版使用练习色。
                \\section{教材与资料证据}
                # p159 - 书名：人教B版选择性必修一 - 章节：第二章 / PDF页码：159 - 页图：![p159](../../pages/p159.png)
                ## 正文
                这是会污染讲义的大段 OCR 原文。
                \\section{教师讲评页}
                \\paragraph{方法步骤}
                由 $2a=6$ 得 $a=3$，再用 $c^2=a^2+b^2$。
                模型openai/gpt-5.5 tokens=1759
                \\end{document}
                """);

        assertThat(sanitized)
                .contains("\\section{来源索引}", "\\section{教师讲评页}", "$2a=6$", "$a=3$", "$c^2=a^2+b^2$")
                .doesNotContain("讲义模板与版式", "PDF 版式要求", "页眉", "页脚", "讲评色", "练习色",
                        "![p159]", "../../pages", "## 正文", "OCR 原文", "tokens", "gpt-5.5",
                        "\\documentclass", "\\usepackage", "\\pagestyle", "\\fancyhf", "\\lhead", "\\rhead",
                        "\\lfoot", "\\rfoot", "\\definecolor", "\\titleformat", "\\begin{document}", "\\end{document}");
    }

    @Test
    void rendersReadableChineseHandoutInsteadOfRawLatexSource() throws Exception {
        TeachingTaskResponse task = new TeachingTaskResponse(
                "task-chinese-pdf",
                "client-chinese-pdf",
                "school-a",
                "teacher",
                "teacher-001",
                TeachingTaskStatus.COMPLETED,
                "学会双曲线的大题和小题",
                "从定义开始掌握双曲线解题",
                List.of(),
                List.of(),
                List.of(),
                "\\section{学习目标}\n掌握双曲线定义与参数关系。\n\\begin{itemize}\n\\item 标准方程和渐近线\n\\end{itemize}",
                "\\section{教师版}\n双曲线定义：两焦点距离差的绝对值为定值$2a$。\n# p159\n- 页图：![p159](../../pages/p159.png)\n模型openai/gpt-5.5 tokens=1759\n\\paragraph{关键方法}\n\\begin{itemize}\n\\item 先确定$a,b,c$，再写方程。\n\\item 参数关系：$c^2=a^2+b^2$，比例式可写为$\\frac{x}{y}$。\n\\end{itemize}",
                "\\section{学生版}\n填写双曲线定义，并完成参数计算。",
                List.of(),
                null,
                List.of(),
                null,
                null);

        byte[] pdf = new TeachingHandoutPdfExportService().render(task, "teacher");

        assertThat(pdf).startsWith(new byte[] {'%', 'P', 'D', 'F'});
        try (PDDocument document = Loader.loadPDF(pdf)) {
            String text = new PDFTextStripper().getText(document);
            assertThat(text).contains("教师版讲义", "模板：标准讲义", "双曲线定义", "参数关系");
            assertThat(text).contains("1页", "/");
            assertThat(text).doesNotContain("\\section", "\\item", "\\frac", "c^2", "a^2", "b^2", "![p159]", "../../pages", "tokens", "gpt-5.5", "AI教师", "AI 讲义草稿", "????");
        }
    }

    @Test
    void doesNotExposeInternalLayoutInstructionsInPdfBody() throws Exception {
        TeachingTaskResponse task = new TeachingTaskResponse(
                "task-layout-instruction",
                "client-layout-instruction",
                "school-a",
                "teacher",
                "teacher-001",
                TeachingTaskStatus.COMPLETED,
                "验证公式渲染",
                "输出可打印讲义",
                List.of(),
                List.of(),
                List.of(),
                "",
                """
                \\section{学习目标}
                掌握公式 $$x^2+y^2=1$$ 的表达。
                PDF 版式要求：页眉展示主题和版本，页脚展示页码；教师版使用讲评色。
                \\section{题目}
                说明 $a_1$ 与 $a^2$ 的区别。
                """,
                "\\section{学生版}\n完成空白区。",
                List.of(),
                null,
                List.of(),
                null,
                null);

        byte[] pdf = new TeachingHandoutPdfExportService().render(task, "teacher");

        try (PDDocument document = Loader.loadPDF(pdf)) {
            String text = new PDFTextStripper().getText(document);
            assertThat(text).contains("教师版讲义", "学习目标", "题目");
            assertThat(text).containsOnlyOnce("学习目标");
            assertThat(text).doesNotContain("PDF 版式要求", "页眉展示主题和版本", "\\section", "$$");
        }
    }

    @Test
    void cleansLegacyOcrGarbageFromRenderedSourceIndex() throws Exception {
        TeachingTaskResponse task = new TeachingTaskResponse(
                "task-legacy-source-clean",
                "client-legacy-source-clean",
                "school-a",
                "teacher",
                "teacher-001",
                TeachingTaskStatus.COMPLETED,
                "反比例函数",
                "清理旧讲义来源片段",
                List.of(),
                List.of(),
                List.of(),
                "",
                """
                \\section{来源索引}
                人教B版必修一数学 / 3.1.$1 / PDF$ 96：3.1 函数的概念与性质89 3.1.1 Ѧ ԣХ᛫ ܪ+ắᔢ 我们已经学习过一些函数的知识

                人教B版必修一数学 / 第三章函数 / PDF 129：第三章 函 数 一般地,解析式是多项式的函数的图象都是连续不断的.

                \\section{教师讲评页}
                \\subsection*{方法步骤}
                设解析式 $y=\\frac{k}{x}$，再代入点坐标求 $k$。
                """,
                "\\section{学生版}\n完成练习。\n\\vspace{8em}",
                List.of(),
                null,
                List.of(),
                null,
                null);

        byte[] pdf = new TeachingHandoutPdfExportService().render(task, "teacher");

        try (PDDocument document = Loader.loadPDF(pdf)) {
            String text = new PDFTextStripper().getText(document);
            assertThat(text)
                    .contains("来源索引", "函数", "教师讲评页")
                    .doesNotContain("Ѧ", "ԣ", "᛫", "ܪ", "ắ", "ᔢ");
        }
    }

    @Test
    void rendersDifferentTeacherAndStudentVersionHeaders() throws Exception {
        TeachingTaskResponse task = new TeachingTaskResponse(
                "task-versioned-pdf",
                "client-versioned-pdf",
                "school-a",
                "teacher",
                "teacher-001",
                TeachingTaskStatus.COMPLETED,
                "掌握反比例函数",
                "生成学生练习和教师答案",
                List.of(),
                List.of(),
                List.of(),
                "",
                "\\section{教师版}\n教师讲解：先判断 k 的符号，再讲图像所在象限。",
                "\\section{学生版}\n学生任务：补全定义、图像和空白解答区。",
                List.of(),
                null,
                List.of(),
                null,
                null);

        byte[] teacherPdf = new TeachingHandoutPdfExportService().render(task, "teacher");
        byte[] studentPdf = new TeachingHandoutPdfExportService().render(task, "student");

        assertThat(teacherPdf).isNotEqualTo(studentPdf);
        try (PDDocument teacherDocument = Loader.loadPDF(teacherPdf);
                PDDocument studentDocument = Loader.loadPDF(studentPdf)) {
            PDFTextStripper stripper = new PDFTextStripper();
            assertThat(stripper.getText(teacherDocument)).contains("教师版讲义", "教师版", "教师讲解", "模板：标准讲义", "1页", "/");
            assertThat(stripper.getText(studentDocument)).contains("学生版讲义", "学生版", "学生任务", "模板：标准讲义", "1页", "/");
        }
    }

    @Test
    void pdfboxFallbackKeepsStudentBlanksReadableWithoutLeakingLatexCommands() throws Exception {
        Path fakeEngine = Files.createTempFile("fake-xelatex", ".exe");
        String previous = System.getProperty("math.agent.xelatex.path");
        System.setProperty("math.agent.xelatex.path", fakeEngine.toString());
        try {
            TeachingTaskResponse task = new TeachingTaskResponse(
                    "task-fallback-student-blanks",
                    "client-fallback-student-blanks",
                    "school-a",
                    "student",
                    "student-001",
                    TeachingTaskStatus.COMPLETED,
                    "反比例函数留白练习",
                    "学生版检查公式和作答区",
                    List.of(),
                    List.of(),
                    List.of(),
                    "",
                    "\\section{教师版}\n教师答案：$y=\\frac{k}{x}$。",
                    """
                    \\section{学生版}
                    \\subsection*{知识速记}
                    反比例函数可写为 $y=\\frac{k}{x}$，其中 $k\\ne 0$。
                    \\subsection*{练习任务}
                    \\begin{itemize}
                    \\item 写出定义：\\underline{\\hspace{4em}}
                    \\item 判断点是否在图像上：\\underline{\\hspace{5em}}
                    \\end{itemize}
                    \\subsection*{课堂作答区}
                    \\vspace{8em}
                    """,
                    List.of(),
                    null,
                    List.of(),
                    null,
                    null);

            byte[] pdf = new TeachingHandoutPdfExportService().render(task, "student");

            try (PDDocument document = Loader.loadPDF(pdf)) {
                String text = new PDFTextStripper().getText(document);
                assertThat(text).contains("学生版讲义", "知识速记", "练习任务", "________");
                assertThat(text).contains("y=(k)/(x)", "k", "0");
                assertThat(text).doesNotContain("\\underline", "\\hspace", "\\begin", "\\item", "\\frac", "4em", "5em");
            }
        } finally {
            Files.deleteIfExists(fakeEngine);
            if (previous == null) {
                System.clearProperty("math.agent.xelatex.path");
            } else {
                System.setProperty("math.agent.xelatex.path", previous);
            }
        }
    }

    @Test
    void usesRealXeLaTeXWhenConfiguredAndAvailable() throws Exception {
        Path engine = firstExistingPath(
                "C:/Users/doob/AppData/Local/Programs/MiKTeX/miktex/bin/x64/xelatex.exe",
                "C:/Program Files/MiKTeX/miktex/bin/x64/xelatex.exe",
                "/usr/bin/xelatex",
                "/usr/local/bin/xelatex");
        Assumptions.assumeTrue(engine != null, "XeLaTeX is not installed on this machine");
        String previous = System.getProperty("math.agent.xelatex.path");
        System.setProperty("math.agent.xelatex.path", engine.toString());
        try {
            TeachingTaskResponse task = new TeachingTaskResponse(
                    "task-real-xelatex",
                    "client-real-xelatex",
                    "school-a",
                    "teacher",
                    "teacher-001",
                    TeachingTaskStatus.COMPLETED,
                    "真实 LaTeX 编译验证",
                    "检查公式 $x^2+y^2=1$ 的 PDF 渲染",
                    List.of(),
                    List.of(),
                    List.of(),
                    "",
                    """
                    \\section{学习目标}
                    用真实 XeLaTeX 编译讲义，而不是只走后备 PDF 文本绘制。

                    \\section{公式检查}
                    $$x^2+y^2=1$$

                    \\section{讲评}
                    教师版保留答案、步骤和评分点。
                    """,
                    "\\section{学生版}\n完成公式识别与作答区。\n\\vspace{8em}",
                    List.of(),
                    null,
                    List.of(),
                    null,
                    null);

            byte[] pdf = new TeachingHandoutPdfExportService().render(task, "teacher");

            assertThat(pdf).startsWith(new byte[] {'%', 'P', 'D', 'F'});
            try (PDDocument document = Loader.loadPDF(pdf)) {
                String text = new PDFTextStripper().getText(document);
                assertThat(text).contains("XeLaTeX", "x2");
                assertThat(text).contains("共", "页");
                assertThat(text).doesNotContain("任务编号", "task-real-xelatex", "\\section", "$$");
            }
        } finally {
            if (previous == null) {
                System.clearProperty("math.agent.xelatex.path");
            } else {
                System.setProperty("math.agent.xelatex.path", previous);
            }
        }
    }

    private static Path firstExistingPath(String... candidates) {
        for (String candidate : candidates) {
            Path path = Path.of(candidate);
            if (Files.isRegularFile(path)) {
                return path;
            }
        }
        return null;
    }
}
