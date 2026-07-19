package com.doob.mathagent.teaching;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.doob.mathagent.teaching.service.TeachingHandoutPdfExportService;
import com.doob.mathagent.teaching.vo.TeachingHandoutTemplateResponse;
import com.doob.mathagent.teaching.vo.TeachingTaskResponse;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import javax.imageio.ImageIO;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.contentstream.operator.Operator;
import org.apache.pdfbox.cos.COSNumber;
import org.apache.pdfbox.pdfparser.PDFStreamParser;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

class TeachingHandoutPdfExportServiceTest {

    /**
     * A historical task must never turn into a plausible-looking PDF merely because the exporter can render it.
     * The screenshot regression contained all three defects below: a third-party banner, an unresolved OCR relation,
     * and a prompt saying "as shown" without the corresponding authorized figure.
     */
    @Test
    void refusesPublicationOfHistoricalSnapshotWithBrandOcrGapOrMissingFigure() {
        TeachingTaskResponse task = new TeachingTaskResponse(
                "task-rejected-history",
                "client-rejected-history",
                "school-a",
                "teacher",
                "teacher-001",
                TeachingTaskStatus.COMPLETED,
                "空间向量线面角",
                "核对历史快照不可发布",
                List.of(),
                List.of(),
                List.of(),
                "飞猪数学",
                """
                \\section{作业 1}
                赵礼显数学作业 1. 如图，$CC_1$ □ 平面 $ABC$，求二面角。
                \\section{讲解}
                答案待补充。
                """,
                "\\section{学生版}\\n如图完成证明。",
                List.of(),
                null,
                List.of(),
                null,
                null);

        assertThatThrownBy(() -> new TeachingHandoutPdfExportService().renderForPublication(task, "teacher"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("未解析的数学符号");
    }

    /** A content heading that happens to contain “图片证据” must not become a generic source index. */
    @Test
    void preservesTopicHeadingThatContainsImageEvidenceInsteadOfRewritingItAsSourceIndex() {
        String sanitized = TeachingHandoutPdfExportService.sanitizeLatexForExport("""
                \\section{2013年涂色问题地图图片证据：学习目标}
                相邻区域不得使用同一颜色。
                \\section{来源索引}
                教师资料第 12 页。
                """);

        assertThat(sanitized)
                .contains("2013年涂色问题地图图片证据：学习目标", "相邻区域不得使用同一颜色", "\\section{来源索引}")
                .doesNotContain("\\section{来源索引}\n相邻区域不得使用同一颜色");
    }

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
                内部提示词：不要把这段协议内容输出到讲义。
                {{model_output_placeholder}}
                \\end{document}
                """);

        assertThat(sanitized)
                .contains("\\section{教师讲评页}", "$2a=6$", "$a=3$", "$c^2=a^2+b^2$")
                .doesNotContain("讲义模板与版式", "PDF 版式要求", "页眉", "页脚", "讲评色", "练习色",
                        "![p159]", "../../pages", "## 正文", "OCR 原文", "tokens", "gpt-5.5",
                        "\\documentclass", "\\usepackage", "\\pagestyle", "\\fancyhf", "\\lhead", "\\rhead",
                        "\\lfoot", "\\rfoot", "\\definecolor", "\\titleformat", "\\begin{document}", "\\end{document}",
                        "内部提示词", "{{model_output_placeholder}}");
    }

    @Test
    void removesLegacyWorkflowHeadingsAndBenchmarkEvidenceFromOldHandouts() {
        String sanitized = TeachingHandoutPdfExportService.sanitizeLatexForExport("""
                \\section{课前定位}
                \\subsection*{题目入口}
                已知椭圆 x^2/9+y^2/4=1，求切线。
                \\section{来源索引}
                来源 1：教师资料，synthetic-natural-math-benchmark / 模糊讲义。
                \\section{讲评入口}
                \\subsection*{审题提醒}
                这是一段旧的流程话术。
                \\section{典型例题}
                已知 $x^2+y^2=1$，求切线。
                """);

        assertThat(sanitized)
                .contains("\\section{典型例题}", "$x^2+y^2=1$")
                .doesNotContain("题目入口", "讲评入口", "审题提醒", "synthetic-natural-math-benchmark", "模糊讲义");
    }

    @Test
    void normalizesMixedMathDelimitersAroundPlusMinusFractions() {
        String sanitized = TeachingHandoutPdfExportService.sanitizeLatexForExport("""
                \\section{渐近线}
                4. 渐近线方程 y=\\pm($\\frac{b}{a}$)x 用于辅助作图。
                5. 另一种写法 y=\\pm(\\frac{b}{a})x 也必须保持完整数学环境。
                ① 先确定焦点位置，② 再写参数关系。
                """);

        assertThat(sanitized)
                .contains("$y=\\pm\\left(\\frac{b}{a}\\right)x$")
                .contains("1. 先确定焦点位置", "2. 再写参数关系")
                .doesNotContain("\\pm($", "$)x", "\\pm(\\frac", "①", "②");
    }

    @Test
    void restoresPersistedQuadraticOptionsWithoutRewrappingLatexMath() {
        String sanitized = TeachingHandoutPdfExportService.sanitizeLatexForExport("""
                \\section{轨迹方程}
                5．已知曲线 C：$x^2+y^2=16$，A．x\\textasciicircum{}$\\frac{2}{16}+y$\\textasciicircum{}$\\frac{2}{4}=1$。
                14．在如图的4\\textbackslash\\{\\}times 4方格表中选4个方格。
                """);

        assertThat(sanitized)
                .contains("$x^2+y^2=16$", "$\\frac{x^{2}}{16}+\\frac{y^{2}}{4}=1$", "$4\\times 4$")
                .doesNotContain("$$$", "\\textasciicircum", "\\textbackslash");
    }

    @Test
    void preservesPageBreaksBetweenStudentQuestionBlocks() {
        String sanitized = TeachingHandoutPdfExportService.sanitizeLatexForExport("""
                \\section{Practice}
                \\subsection*{Question 1}
                \\paragraph{Prompt}
                Find vertex.
                \\paragraph{Answer}
                Write steps.
                \\vspace{18em}
                \\clearpage
                \\subsection*{Question 2}
                \\paragraph{Prompt}
                Find axis.
                \\paragraph{Answer}
                Write steps.
                \\vspace{18em}
                """);

        assertThat(sanitized)
                .contains("\\section{Practice}", "\\subsection*{Question 1}", "\\clearpage", "\\subsection*{Question 2}")
                .contains("\\paragraph{Answer}", "Write steps.", "\\vspace{18em}");
        assertThat(sanitized.split("\\\\vspace\\{18em}", -1)).hasSize(3);
    }

    @Test
    void keepsMarkdownImagesAsRenderableImageMarkers() {
        String sanitized = TeachingHandoutPdfExportService.sanitizeLatexForExport("""
                \\section{题目}
                如图，完成立体几何判断。
                ![几何图一](C:/tmp/geo-1.png)
                ![几何图二](C:/tmp/geo-2.png)
                """);

        assertThat(sanitized)
                .contains("[[HANDOUTIMAGE:")
                .contains("如图，完成立体几何判断。")
                .doesNotContain("![几何图一]", "![几何图二]");
    }

    @Test
    void promotesExistingEvidenceImageToRenderableMarkerAndRepairsLegacyMathControls() throws Exception {
        Path image = Files.createTempFile("evidence-page-", ".png");
        try {
            String sanitized = TeachingHandoutPdfExportService.sanitizeLatexForExport("""
                    \\section{来源索引}
                    教材证据：![相关图示](%s)
                    \\section{教师讲评页}
                    公式：$%soldsymbol{u}$，角度：$%sheta$，分式：$%srac{1}{2}$。
                    """.formatted(image.toAbsolutePath(), "\u0008", "\u0009", "\u000C "));

            assertThat(sanitized)
                    .contains("[[HANDOUTIMAGE:")
                    .contains("\\boldsymbol{u}", "\\theta", "\\frac{1}{2}")
                    .doesNotContain("![相关图示]");
        } finally {
            Files.deleteIfExists(image);
        }
    }

    @Test
    void normalizesLegacyDirectIncludegraphicsToTheSameImageMarker() throws Exception {
        Path image = Files.createTempFile("direct-image-", ".png");
        try {
            String sanitized = TeachingHandoutPdfExportService.sanitizeLatexForExport(
                    "\\section{题目}\n\\includegraphics[width=0.72\\linewidth,height=0.28\\textheight,keepaspectratio]{\\detokenize{"
                            + image.toAbsolutePath() + "}}\n### 5.1 目录元数据");
            assertThat(sanitized)
                    .contains("[[HANDOUTIMAGE:")
                    .doesNotContain("includegraphics", "### 5.1");
        } finally {
            Files.deleteIfExists(image);
        }
    }

    @Test
    void omitsTransportCaptionForAnAuthorizedInlineQuestionFigure() throws Exception {
        Path image = Files.createTempFile("authorized-question-figure-", ".png");
        try {
            ImageIO.write(new BufferedImage(12, 12, BufferedImage.TYPE_INT_RGB), "png", image.toFile());
            String sanitized = TeachingHandoutPdfExportService.sanitizeLatexForExport("""
                    \\section{题目}
                    如图，完成判断。
                    \\includegraphics[width=0.7\\linewidth]{\\detokenize{%s}}
                    """.formatted(image.toAbsolutePath().toString().replace('\\', '/')));
            var render = TeachingHandoutPdfExportService.class.getDeclaredMethod("renderLatexBody", String.class);
            render.setAccessible(true);

            String rendered = (String) render.invoke(null, sanitized);

            assertThat(rendered).contains("\\includegraphics").doesNotContain("相关图示");
        } finally {
            Files.deleteIfExists(image);
        }
    }

    @Test
    void preservesImageMarkerThroughLatexEscapingAndRemovesEnvironmentArtifacts() throws Exception {
        Path image = createSolidImage("direct-image-marker");
        try {
            String sanitized = TeachingHandoutPdfExportService.sanitizeLatexForExport("""
                    \\section{来源索引}
                    - itemize - 先列出相邻关系，再按禁色数分类。
                    \\includegraphics[width=0.72\\linewidth,height=0.28\\textheight,keepaspectratio]{\\detokenize{%s}}
                    """.formatted(image.toAbsolutePath().toString().replace("\\", "/")));

            assertThat(sanitized)
                    .contains("[[HANDOUTIMAGE:")
                    .doesNotContain("- itemize -", "\\\\includegraphics", "HANDOUTIMAGETOKEN");
        } finally {
            Files.deleteIfExists(image);
        }
    }

    @Test
    void rendersLegacyDirectIncludegraphicsAsPdfImageInsteadOfVisibleMarker() throws Exception {
        Path image = createSolidImage("direct-image-render");
        Path fakeEngine = Files.createTempFile("fake-xelatex", ".exe");
        String previous = System.getProperty("math.agent.xelatex.path");
        System.setProperty("math.agent.xelatex.path", fakeEngine.toString());
        try {
            String imagePath = image.toAbsolutePath().toString().replace("\\", "/");
            TeachingTaskResponse task = new TeachingTaskResponse(
                    "task-direct-image-render",
                    "client-direct-image-render",
                    "school-a",
                    "teacher",
                    "teacher-001",
                    null,
                    TeachingTaskStatus.COMPLETED,
                    "图片证据",
                    "检查图片证据不应暴露内部标记",
                    List.of(),
                    List.of(),
                    List.of(),
                    "",
                    "\\section{来源索引}\n- itemize - 先列出相邻关系。\n\\includegraphics[width=0.72\\linewidth,height=0.28\\textheight,keepaspectratio]{\\detokenize{" + imagePath + "}}",
                    "\\section{学生版}\n完成题目。",
                    List.of(),
                    null,
                    List.of(),
                    null,
                    null);

            TeachingHandoutPdfExportService.RenderedHandoutPdf rendered =
                    new TeachingHandoutPdfExportService().renderDetailed(task, "teacher");

            assertThat(rendered.renderer()).isEqualTo("pdfbox_fallback");
            try (PDDocument document = Loader.loadPDF(rendered.bytes())) {
                String text = new PDFTextStripper().getText(document);
                assertThat(countPdfImages(document)).isGreaterThanOrEqualTo(1);
                assertThat(text).doesNotContain("HANDOUTIMAGE", "- itemize -");
            }
        } finally {
            Files.deleteIfExists(image);
            Files.deleteIfExists(fakeEngine);
            if (previous == null) {
                System.clearProperty("math.agent.xelatex.path");
            } else {
                System.setProperty("math.agent.xelatex.path", previous);
            }
        }
    }

    @Test
    void removesTitleBlocksThatContainOnlyBlankWorkspace() {
        String sanitized = TeachingHandoutPdfExportService.sanitizeLatexForExport("""
                \\section{题目}
                已知 $a+b=3$，求 $2a+2b$。

                \\section{课堂作答区}
                \\vspace{12em}
                教师手写区
                手写区
                板书留白

                \\section{订正记录}
                作答：\\underline{\\hspace{8em}}

                \\section{练习}
                \\begin{enumerate}
                \\item 计算 $2(a+b)$。作答：___
                \\end{enumerate}
                """);

        assertThat(sanitized)
                .contains("\\section{题目}", "\\section{练习}", "计算 $2(a+b)$")
                .doesNotContain("\\section{课堂作答区}", "\\section{订正记录}", "\\vspace{12em}",
                        "教师手写区", "手写区", "板书留白");
    }

    @Test
    void stripsHistoricalSourceBrandBannersFromPersistedLatexBeforePdfExport() {
        String sanitized = TeachingHandoutPdfExportService.sanitizeLatexForExport("""
                \\section{例题}
                赵礼显数学作业 1. 如图，在三棱柱 ABC-A1B1C1 中，求二面角。
                \\paragraph{讲解}
                由题设条件确定法向量。
                """);

        assertThat(sanitized)
                .contains("如图，在三棱柱", "由题设条件确定法向量")
                .doesNotContain("赵礼显数学", "作业 1.");
    }

    @Test
    void dropsUnreadablePlaceholderLinesButKeepsRealQuestions() {
        String sanitized = TeachingHandoutPdfExportService.sanitizeLatexForExport("""
                \\section{例题任务}
                ???????? 这行是旧任务里的坏占位。
                OCR 片段：�� 公式无法识别。
                为什么先判断 $a$、$b$、$c$ 的关系？
                \\section{练习}
                若 $\\sin A=\\frac{1}{2}$，求角 $A$ 的可能取值。
                """);

        assertThat(sanitized)
                .contains("\\section{例题任务}", "为什么先判断 $a$、$b$、$c$ 的关系？", "\\section{练习}", "$\\sin A=\\frac{1}{2}$")
                .doesNotContain("????????", "��", "坏占位");
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
            assertThat(text).contains("教师版讲义", "数学讲义", "双曲线定义", "参数关系")
                    .doesNotContain("飞猪数学");
            assertThat(text).contains("1页", "/");
            assertThat(text).doesNotContain("模板：", "\\section", "\\item", "\\frac", "c^2", "a^2", "b^2", "![p159]", "../../pages", "tokens", "gpt-5.5", "AI教师", "AI 讲义草稿", "????");
        }
    }

    @Test
    void usesPersistedCustomWatermarkInPdfFallback() throws Exception {
        TeachingTaskResponse task = new TeachingTaskResponse(
                "task-custom-watermark", "client-custom-watermark", "school-a", "teacher", "teacher-001",
                TeachingTaskStatus.COMPLETED, "函数最值", "掌握函数最值", List.of(), List.of(), List.of(),
                "\\section{讲义}\\n掌握函数最值。", "\\section{教师版}\\n掌握函数最值。", "", List.of(), null,
                List.of(), null, null).withWatermarkText("张老师数学");

        byte[] pdf = new TeachingHandoutPdfExportService().render(task, "teacher");
        try (PDDocument document = Loader.loadPDF(pdf)) {
            String text = new PDFTextStripper().getText(document);
            assertThat(text).contains("张老师数学").doesNotContain("飞猪数学");
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
            assertThat(stripper.getText(teacherDocument)).contains("教师版讲义", "教师版", "教师讲解", "数学讲义", "1页", "/")
                    .doesNotContain("飞猪数学")
                    .doesNotContain("模板：");
            assertThat(stripper.getText(studentDocument)).contains("学生版讲义", "学生版", "学生任务", "数学讲义", "1页", "/")
                    .doesNotContain("飞猪数学")
                    .doesNotContain("模板：");
        }
    }

    @Test
    void rendersLectureVersionAsSixteenToTenLandscapeWithoutHandwritingLabels() throws Exception {
        Path fakeEngine = Files.createTempFile("fake-xelatex", ".exe");
        String previous = System.getProperty("math.agent.xelatex.path");
        System.setProperty("math.agent.xelatex.path", fakeEngine.toString());
        try {
            TeachingTaskResponse task = new TeachingTaskResponse(
                    "task-lecture-pdf",
                    "client-lecture-pdf",
                    "school-a",
                    "teacher",
                    "teacher-001",
                    null,
                    TeachingTaskStatus.COMPLETED,
                    "学会双曲线定义与标准方程",
                    "横版投屏讲解双曲线",
                    List.of(),
                    List.of(),
                    List.of(),
                    "",
                    "\\section{教师版}\n教师讲解：$c^2=a^2+b^2$。",
                    "\\section{学生版}\n完成练习。\n\\vspace{8em}",
                    """
                    \\section{16:10 横版讲解卡}
                    \\paragraph{课堂投屏}
                    双曲线核心公式 $c^2=a^2+b^2$。
                    \\begin{itemize}
                    \\item 先判断焦点在 x 轴还是 y 轴。
                    \\item 再根据 $2a$、$2c$ 求参数。
                    \\end{itemize}
                    \\vspace{10em}
                    """,
                    List.of(),
                    null,
                    List.of(),
                    null,
                    null);

            TeachingHandoutPdfExportService.RenderedHandoutPdf rendered =
                    new TeachingHandoutPdfExportService().renderDetailed(task, "lecture");

            assertThat(rendered.renderer()).isEqualTo("pdfbox_fallback");
            try (PDDocument document = Loader.loadPDF(rendered.bytes())) {
                var mediaBox = document.getPage(0).getMediaBox();
                assertThat(mediaBox.getWidth()).isGreaterThan(mediaBox.getHeight());
                assertThat(mediaBox.getWidth() / mediaBox.getHeight()).isBetween(1.58f, 1.62f);
                String text = new PDFTextStripper().getText(document);
                assertThat(text)
                        .contains("横版讲解稿", "16:10 横版讲解卡", "课堂投屏", "双曲线核心公式")
                        .doesNotContain("教师手写区", "手写区", "板书留白");
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
    void doesNotExposeTeacherTemplateNameInStudentPdf() throws Exception {
        TeachingHandoutTemplateResponse teacherTemplate = new TeachingHandoutTemplateResponse(
                "teacher_solution_v1",
                "教师详解版",
                "builtin",
                "teacher",
                "教师讲评模板",
                "教师详解",
                "教案式",
                List.of("基础"),
                List.of("教师版"),
                null,
                null,
                null,
                6,
                4);
        TeachingTaskResponse task = new TeachingTaskResponse(
                "task-student-template-name",
                "client-student-template-name",
                "school-a",
                "teacher",
                "teacher-001",
                teacherTemplate,
                TeachingTaskStatus.COMPLETED,
                "已知函数 f(x)=x^2，求 f(2)",
                "复习函数代入",
                List.of(),
                List.of(),
                List.of(),
                "",
                "\\section{教师版}\n教师讲解：代入 x=2。",
                "\\section{学生版}\n模板：教师详解版 版本：学生版讲义\n独立完成代入计算。",
                List.of(),
                null,
                List.of(),
                null,
                null);

        try (PDDocument document = Loader.loadPDF(new TeachingHandoutPdfExportService().render(task, "student"))) {
            String text = new PDFTextStripper().getText(document);
            assertThat(text).contains("学生版讲义", "数学讲义").doesNotContain("飞猪数学");
            assertThat(text).doesNotContain("模板：", "教师详解版", "教师详解版 版本");
        }
    }

    @Test
    void keepsEachLectureCardOnItsOwnFallbackPage() throws Exception {
        Path fakeEngine = Files.createTempFile("fake-xelatex", ".exe");
        String previous = System.getProperty("math.agent.xelatex.path");
        System.setProperty("math.agent.xelatex.path", fakeEngine.toString());
        try {
            TeachingTaskResponse task = new TeachingTaskResponse(
                    "task-lecture-pages",
                    "client-lecture-pages",
                    "school-a",
                    "teacher",
                    "teacher-001",
                    null,
                    TeachingTaskStatus.COMPLETED,
                    "两道投屏题",
                    "横版分页验收",
                    List.of(),
                    List.of(),
                    List.of(),
                    "",
                    "",
                    "",
                    """
                    \\section{16:10 横版讲解卡}
                    \\subsection*{第 1 题 / 讲解单元}
                    第一题只讲定义入口。
                    \\vspace{14em}
                    \\clearpage
                    \\subsection*{第 2 题 / 讲解单元}
                    第二题只讲参数回收。
                    \\vspace{14em}
                    """,
                    List.of(),
                    null,
                    List.of(),
                    null,
                    null);

            TeachingHandoutPdfExportService.RenderedHandoutPdf rendered =
                    new TeachingHandoutPdfExportService().renderDetailed(task, "lecture");

            assertThat(rendered.renderer()).isEqualTo("pdfbox_fallback");
            try (PDDocument document = Loader.loadPDF(rendered.bytes())) {
                assertThat(document.getNumberOfPages()).isEqualTo(2);
                PDFTextStripper stripper = new PDFTextStripper();
                stripper.setStartPage(1);
                stripper.setEndPage(1);
                assertThat(stripper.getText(document)).contains("第 1 题", "第一题").doesNotContain("第二题");
                stripper.setStartPage(2);
                stripper.setEndPage(2);
                assertThat(stripper.getText(document)).contains("第 2 题", "第二题").doesNotContain("第一题");
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
    void keepsEachLectureCardOnItsOwnRealXeLatexPageWhenEngineIsAvailable() throws Exception {
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
                    "task-lecture-real-pages",
                    "client-lecture-real-pages",
                    "school-a",
                    "teacher",
                    "teacher-001",
                    null,
                    TeachingTaskStatus.COMPLETED,
                    "两道投屏题",
                    "横版分页验收",
                    List.of(),
                    List.of(),
                    List.of(),
                    "",
                    "",
                    "",
                    """
                    \\section{16:10 横版讲解卡}
                    \\subsection*{第 1 题 / 讲解单元}
                    \\paragraph{投屏内容}
                    第一题有效内容。
                    \\vspace{14em}
                    \\clearpage
                    \\subsection*{第 2 题 / 讲解单元}
                    \\paragraph{投屏内容}
                    第二题有效内容。
                    \\vspace{14em}
                    """,
                    List.of(),
                    null,
                    List.of(),
                    null,
                    null);

            TeachingHandoutPdfExportService.RenderedHandoutPdf rendered =
                    new TeachingHandoutPdfExportService().renderDetailed(task, "lecture");
            assertThat(rendered.renderer()).isEqualTo("xelatex");
            try (PDDocument document = Loader.loadPDF(rendered.bytes())) {
                assertThat(document.getNumberOfPages()).isEqualTo(2);
                PDFTextStripper stripper = new PDFTextStripper();
                stripper.setStartPage(1);
                stripper.setEndPage(1);
                assertThat(stripper.getText(document)).contains("第 1", "第一题有效内容").doesNotContain("第二题");
                stripper.setStartPage(2);
                stripper.setEndPage(2);
                assertThat(stripper.getText(document)).contains("第 2", "第二题有效内容").doesNotContain("第一题");
            }
        } finally {
            if (previous == null) {
                System.clearProperty("math.agent.xelatex.path");
            } else {
                System.setProperty("math.agent.xelatex.path", previous);
            }
        }
    }

    @Test
    void rendersZhaoTemplateWithRealXeLatexAndSeparatesLectureQuestions() throws Exception {
        Path engine = firstExistingPath(
                "C:/Users/doob/AppData/Local/Programs/MiKTeX/miktex/bin/x64/xelatex.exe",
                "C:/Program Files/MiKTeX/miktex/bin/x64/xelatex.exe",
                "/usr/bin/xelatex",
                "/usr/local/bin/xelatex");
        Assumptions.assumeTrue(engine != null, "XeLaTeX is not installed on this machine");
        String previous = System.getProperty("math.agent.xelatex.path");
        System.setProperty("math.agent.xelatex.path", engine.toString());
        try {
            TeachingHandoutTemplateResponse zhaoTemplate = new TeachingHandoutTemplateResponse(
                    "zhao_lixian_topic_v1",
                    "赵礼显专题讲义",
                    "skill_config",
                    "mixed",
                    "真实赵礼显版式回归",
                    "专题训练",
                    "讲义式专题课",
                    List.of("基础", "提高"),
                    List.of("赵礼显"),
                    "2025暑秋讲义.pdf",
                    "D:/BaiduNetdiskDownload/2025暑秋讲义.pdf",
                    "真实母版",
                    7,
                    3);
            String lecture = """
                    \\section*{二次函数专题}
                    \\subsection*{第1题 例题}
                    题目一：已知 $y=x^2-2x+1$，求顶点。
                    \\subsection*{第2题 变式练习}
                    题目二：求 $y=x^2+1$ 的对称轴。
                    """;
            TeachingTaskResponse task = new TeachingTaskResponse(
                    "task-zhao-real-layout",
                    "client-zhao-real-layout",
                    "school-a",
                    "teacher",
                    "teacher-001",
                    zhaoTemplate,
                    TeachingTaskStatus.COMPLETED,
                    "二次函数专题",
                    "赵礼显版式与横版分页",
                    List.of(),
                    List.of(),
                    List.of(),
                    "",
                    lecture,
                    lecture,
                    lecture,
                    List.of(),
                    null,
                    List.of(),
                    null,
                    null);

            TeachingHandoutPdfExportService.RenderedHandoutPdf rendered =
                    new TeachingHandoutPdfExportService().renderDetailed(task, "lecture");
            assertThat(rendered.renderer()).isEqualTo("xelatex");
            try (PDDocument document = Loader.loadPDF(rendered.bytes())) {
                assertThat(document.getNumberOfPages()).isEqualTo(2);
                // Text extraction from CJK fonts is provider-dependent; page count is the stable
                // contract here because insertLectureQuestionBreaks is what this regression covers.
                assertThat(document.getPage(0).getMediaBox().getWidth()).isGreaterThan(700f);
                assertThat(document.getPage(0).getMediaBox().getHeight()).isGreaterThan(450f);
            }
        } finally {
            if (previous == null) {
                System.clearProperty("math.agent.xelatex.path");
            } else {
                System.setProperty("math.agent.xelatex.path", previous);
            }
        }
    }

    /**
     * Guards the observable Zhao master constraints: the fixed scanned-paper geometry, navy ink,
     * and no generic centered audience title. This is intentionally a rendered-PDF test rather
     * than a source-string test so changes in the LaTeX wrapper cannot bypass the visual contract.
     */
    @Test
    void rendersZhaoTeacherPageWithMasterGeometryAndNavyInk() throws Exception {
        Path engine = firstExistingPath(
                "C:/Users/doob/AppData/Local/Programs/MiKTeX/miktex/bin/x64/xelatex.exe",
                "C:/Program Files/MiKTeX/miktex/bin/x64/xelatex.exe",
                "/usr/bin/xelatex",
                "/usr/local/bin/xelatex");
        Assumptions.assumeTrue(engine != null, "XeLaTeX is not installed on this machine");
        String previous = System.getProperty("math.agent.xelatex.path");
        System.setProperty("math.agent.xelatex.path", engine.toString());
        try {
            TeachingHandoutTemplateResponse zhaoTemplate = new TeachingHandoutTemplateResponse(
                    "zhao_lixian_topic_v1", "赵礼显专题讲义", "skill_config", "mixed",
                    "赵礼显母版视觉回归", "专题训练", "讲义式专题课", List.of("基础"), List.of("赵礼显"),
                    "2025暑秋讲义.pdf", "D:/BaiduNetdiskDownload/2025暑秋讲义.pdf", "真实母版", 7, 3);
            String handout = """
                    \\section{题型四：投影}
                    \\subsection*{第1题 例题}
                    已知正八边形 $A_1A_2A_3A_4A_5A_6A_7A_8$，求向量数量积。
                    """;
            TeachingTaskResponse task = new TeachingTaskResponse(
                    "task-zhao-master-visual", "client-zhao-master-visual", "school-a", "teacher", "teacher-001",
                    zhaoTemplate, TeachingTaskStatus.COMPLETED, "平面向量投影", "赵礼显母版视觉回归", List.of(),
                    List.of(), List.of(), "", handout, handout, handout, List.of(), null, List.of(), null, null);

            TeachingHandoutPdfExportService.RenderedHandoutPdf rendered =
                    new TeachingHandoutPdfExportService().renderDetailed(task, "teacher");
            assertThat(rendered.renderer()).isEqualTo("xelatex");
            try (PDDocument document = Loader.loadPDF(rendered.bytes())) {
                PDPage page = document.getPage(0);
                assertThat(page.getMediaBox().getWidth()).isEqualTo(582f);
                assertThat(page.getMediaBox().getHeight()).isEqualTo(812f);
                String extracted = new PDFTextStripper().getText(document);
                // PDF text extraction may insert a word gap between CJK and the digit.  Normalize
                // whitespace here so this rendered-layout regression remains about visible content.
                assertThat(extracted).contains("题型四").doesNotContain("教师版讲义");
                assertThat(extracted.replaceAll("\\s+", "")).contains("第1题");
                assertThat(containsRgbColor(page, new Color(44, 57, 135))).isTrue();
            }
        } finally {
            if (previous == null) {
                System.clearProperty("math.agent.xelatex.path");
            } else {
                System.setProperty("math.agent.xelatex.path", previous);
            }
        }
    }

    /**
     * The template code is the durable rendering identity.  The UI is free to replace a display
     * name for an audience, but that must never silently route a Zhao student worksheet back to
     * the generic A4/red renderer.
     */
    @Test
    void rendersZhaoStudentPageFromStableTemplateCodeWhenDisplayNameIsAudienceSpecific() throws Exception {
        Path engine = firstExistingPath(
                "C:/Users/doob/AppData/Local/Programs/MiKTeX/miktex/bin/x64/xelatex.exe",
                "C:/Program Files/MiKTeX/miktex/bin/x64/xelatex.exe",
                "/usr/bin/xelatex",
                "/usr/local/bin/xelatex");
        Assumptions.assumeTrue(engine != null, "XeLaTeX is not installed on this machine");
        String previous = System.getProperty("math.agent.xelatex.path");
        System.setProperty("math.agent.xelatex.path", engine.toString());
        try {
            TeachingHandoutTemplateResponse zhaoTemplate = new TeachingHandoutTemplateResponse(
                    "zhao_lixian_topic_v1", "学生专题讲义", "skill_config", "teacher",
                    "stable template identity regression", "专题训练", "赵礼显", List.of("基础"), List.of("赵礼显"),
                    "2025暑秋讲义.pdf", "D:/BaiduNetdiskDownload/2025暑秋讲义.pdf", "真实母版", 7, 3);
            String handout = """
                    \\section{二次函数}
                    \\subsection*{第1题 例题}
                    已知 $y=x^2-2x+1$，求顶点坐标。
                    """;
            TeachingTaskResponse task = new TeachingTaskResponse(
                    "task-zhao-code-identity", "client-zhao-code-identity", "school-a", "teacher", "teacher-001",
                    zhaoTemplate, TeachingTaskStatus.COMPLETED, "二次函数", "赵礼显母版视觉回归", List.of(),
                    List.of(), List.of(), "", handout, handout, handout, List.of(), null, List.of(), null, null);

            TeachingHandoutPdfExportService.RenderedHandoutPdf rendered =
                    new TeachingHandoutPdfExportService().renderDetailed(task, "student");
            assertThat(rendered.renderer()).isEqualTo("xelatex");
            try (PDDocument document = Loader.loadPDF(rendered.bytes())) {
                PDPage page = document.getPage(0);
                assertThat(page.getMediaBox().getWidth()).isEqualTo(582f);
                assertThat(page.getMediaBox().getHeight()).isEqualTo(812f);
                assertThat(new PDFTextStripper().getText(document)).doesNotContain("学生版讲义");
                assertThat(containsRgbColor(page, new Color(44, 57, 135))).isTrue();
            }
        } finally {
            if (previous == null) {
                System.clearProperty("math.agent.xelatex.path");
            } else {
                System.setProperty("math.agent.xelatex.path", previous);
            }
        }
    }

    /**
     * Exercises the real XeLaTeX path used by task download, rather than only the PDFBox fallback.
     *
     * <p>Teacher-resource figures arrive at the renderer as an authorized local path and are first
     * normalized into an opaque {@code HANDOUTIMAGE} transport marker.  A marker is never lesson
     * content: each exported audience version must contain a native PDF image object instead of that
     * implementation detail.  Keeping this regression at the PDF boundary catches stale marker
     * expansion in the exact path that previously produced a visible Base64 string.</p>
     */
    @Test
    void rendersAuthorizedTeacherFigureInEveryVersionWithRealXeLatex() throws Exception {
        Path engine = firstExistingPath(
                "C:/Users/doob/AppData/Local/Programs/MiKTeX/miktex/bin/x64/xelatex.exe",
                "C:/Program Files/MiKTeX/miktex/bin/x64/xelatex.exe",
                "/usr/bin/xelatex",
                "/usr/local/bin/xelatex");
        Assumptions.assumeTrue(engine != null, "XeLaTeX is not installed on this machine");
        Path image = createSolidImage("authorized-teacher-figure");
        String previous = System.getProperty("math.agent.xelatex.path");
        System.setProperty("math.agent.xelatex.path", engine.toString());
        try {
            String imagePath = image.toAbsolutePath().toString().replace("\\", "/");
            String sharedHandout = """
                    \\section{图像证据}
                    如图，根据相邻关系完成判断。
                    \\includegraphics[width=0.72\\linewidth,height=0.28\\textheight,keepaspectratio]{\\detokenize{%s}}
                    \\subsection*{第1题 例题}
                    请结合图像说明你的判断依据。
                    """.formatted(imagePath);
            TeachingTaskResponse task = new TeachingTaskResponse(
                    "task-real-authorized-figure",
                    "client-real-authorized-figure",
                    "school-a",
                    "teacher",
                    "teacher-001",
                    null,
                    TeachingTaskStatus.COMPLETED,
                    "授权图像证据",
                    "验证教师图片在三个版本中都可见",
                    List.of(),
                    List.of(),
                    List.of(),
                    "",
                    sharedHandout,
                    sharedHandout,
                    sharedHandout,
                    List.of(),
                    null,
                    List.of(),
                    null,
                    null);

            for (String version : List.of("teacher", "student", "lecture")) {
                TeachingHandoutPdfExportService.RenderedHandoutPdf rendered =
                        new TeachingHandoutPdfExportService().renderDetailed(task, version);
                assertThat(rendered.renderer()).isEqualTo("xelatex");
                try (PDDocument document = Loader.loadPDF(rendered.bytes())) {
                    assertThat(countPdfImages(document)).as(version).isGreaterThanOrEqualTo(1);
                    assertThat(new PDFTextStripper().getText(document))
                            .doesNotContain("HANDOUTIMAGE", "HANDOUTIMAGETOKEN");
                }
            }
        } finally {
            Files.deleteIfExists(image);
            if (previous == null) {
                System.clearProperty("math.agent.xelatex.path");
            } else {
                System.setProperty("math.agent.xelatex.path", previous);
            }
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
                    "学生版检查公式和留白",
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
                assertThat(text).doesNotContain("作答区", "手写区", "留白区",
                        "\\underline", "\\hspace", "\\begin", "\\item", "\\frac", "4em", "5em");
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
    void pdfboxFallbackEmbedsQuestionImagesAndCaptions() throws Exception {
        Path firstImage = createSolidImage("handout-geometry-1");
        Path secondImage = createSolidImage("handout-geometry-2");
        Path fakeEngine = Files.createTempFile("fake-xelatex", ".exe");
        String previous = System.getProperty("math.agent.xelatex.path");
        System.setProperty("math.agent.xelatex.path", fakeEngine.toString());
        try {
            TeachingTaskResponse task = new TeachingTaskResponse(
                    "task-image-fallback",
                    "client-image-fallback",
                    "school-a",
                    "teacher",
                    "teacher-001",
                    TeachingTaskStatus.COMPLETED,
                    "image handout export",
                    "verify multi-image export under a question",
                    List.of(),
                    List.of(),
                    List.of(),
                    "",
                    """
                    \\section{Question}
                    Inspect the solid geometry figure below.
                    ![FigureOne](%s)
                    ![FigureTwo](%s)
                    \\section{Review}
                    Read the outer edges before the hidden edges.
                    """.formatted(firstImage.toString().replace("\\", "/"), secondImage.toString().replace("\\", "/")),
                    "\\section{Student}\nFinish the question from the figures.",
                    List.of(),
                    null,
                    List.of(),
                    null,
                    null);

            TeachingHandoutPdfExportService.RenderedHandoutPdf rendered =
                    new TeachingHandoutPdfExportService().renderDetailed(task, "teacher");

            assertThat(rendered.renderer()).isEqualTo("pdfbox_fallback");
            try (PDDocument document = Loader.loadPDF(rendered.bytes())) {
                String text = new PDFTextStripper().getText(document);
                long imageCount = countPdfImages(document);
                assertThat(imageCount).isGreaterThanOrEqualTo(2);
                assertThat(text).contains("Question", "FigureOne", "FigureTwo", "Read the outer edges before the hidden edges.");
                assertThat(text).doesNotContain("![FigureOne]", "![FigureTwo]");
            }
        } finally {
            Files.deleteIfExists(firstImage);
            Files.deleteIfExists(secondImage);
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
                    "\\section{学生版}\n完成公式识别。\n\\vspace{8em}",
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

    private static Path createSolidImage(String prefix) throws Exception {
        Path file = Files.createTempFile(prefix, ".png");
        BufferedImage image = new BufferedImage(160, 120, BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D graphics = image.createGraphics();
        try {
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, 160, 120);
            graphics.setColor(new Color(15, 118, 110));
            graphics.drawRect(12, 12, 136, 96);
            graphics.drawLine(20, 100, 80, 24);
            graphics.drawLine(80, 24, 140, 88);
        } finally {
            graphics.dispose();
        }
        ImageIO.write(image, "png", file.toFile());
        return file;
    }

    /** Finds a rendered RGB graphics operator with the expected Zhao master color. */
    private static boolean containsRgbColor(PDPage page, Color expected) throws Exception {
        PDFStreamParser parser = new PDFStreamParser(page);
        // PDFBox 3 returns parsed operands/operators directly; the legacy getTokens accessor no longer exists.
        List<Object> tokens = parser.parse();
        float red = expected.getRed() / 255f;
        float green = expected.getGreen() / 255f;
        float blue = expected.getBlue() / 255f;
        float tolerance = 0.002f;
        for (int index = 3; index < tokens.size(); index += 1) {
            if (!(tokens.get(index) instanceof Operator operator)
                    || !("rg".equals(operator.getName()) || "RG".equals(operator.getName()))
                    || !(tokens.get(index - 3) instanceof COSNumber redToken)
                    || !(tokens.get(index - 2) instanceof COSNumber greenToken)
                    || !(tokens.get(index - 1) instanceof COSNumber blueToken)) {
                continue;
            }
            if (Math.abs(redToken.floatValue() - red) <= tolerance
                    && Math.abs(greenToken.floatValue() - green) <= tolerance
                    && Math.abs(blueToken.floatValue() - blue) <= tolerance) {
                return true;
            }
        }
        return false;
    }

    private static long countPdfImages(PDDocument document) throws Exception {
        long total = 0;
        for (int pageIndex = 0; pageIndex < document.getNumberOfPages(); pageIndex += 1) {
            for (org.apache.pdfbox.cos.COSName name : document.getPage(pageIndex).getResources().getXObjectNames()) {
                if (document.getPage(pageIndex).getResources().getXObject(name) instanceof PDImageXObject) {
                    total += 1;
                }
            }
        }
        return total;
    }
}
