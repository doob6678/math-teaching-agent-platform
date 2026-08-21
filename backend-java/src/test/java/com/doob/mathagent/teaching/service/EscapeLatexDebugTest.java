package com.doob.mathagent.teaching.service;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class EscapeLatexDebugTest {

    @Test
    void testThetaInChineseParens() {
        // 来自 teacher_writer.md 第 51 行的原始内容
        String input = "过抛物线 $y^2=2px$（$p>0$）的焦点 $F$ 作倾斜角为 $\\theta$ 的直线";
        
        String result = TeachingWorkflowStudentRenderer.escapeLatex(input);
        
        System.out.println("Input:  " + input);
        System.out.println("Output: " + result);
        
        // 期望：所有 $ 符号都应该保留
        assertTrue(result.contains("$\\theta$"), "theta 应该在 $ 符号内");
        assertTrue(result.contains("$F$"), "F 应该在 $ 符号内");
        
        // 期望：中文括号应该被转义
        assertTrue(result.contains("（") || result.contains("\\{") || result.contains("textbackslash"), 
            "中文括号应该被处理");
    }
    
    @Test
    void exportSanitizer_shouldPreserveAuthoredInlineMath() {
        String input = "过抛物线 $y^2=2px$（$p>0$）的焦点 $F$ 作倾斜角为 $\\theta$ 的直线\n"
                + "$(x-\\frac{p}{2})^2+y^2 = (x+\\frac{p}{2})^2$\\par";

        String result = TeachingHandoutPdfExportService.sanitizeLatexForExport(input);

        assertTrue(result.contains("$\\theta$"), "导出清洗不得移除 theta 的数学分隔符");
        assertTrue(result.contains("$(x-\\frac{p}{2})^2+y^2 = (x+\\frac{p}{2})^2$\\par"),
                "导出清洗不得改写完整公式的数学分隔符");
    }
    @Test
    void exportStages_shouldNotRemoveFormulaDelimiter() {
        String source = "$(x-\\frac{p}{2})^2+y^2 = (x+\\frac{p}{2})^2$\\par";
        String triple = TeachingHandoutPdfExportService.normalizeTripleDollarMath(source);
        String adjacent = TeachingHandoutPdfExportService.normalizeSplitAdjacentInlineMath(triple);
        String mixed = TeachingHandoutPdfExportService.normalizeMixedMathDelimiters(adjacent);
        String bare = TeachingHandoutPdfExportService.normalizeBareMathFragments(mixed);
        String escaped = TeachingHandoutPdfExportService.escapeLooseTextSpecials(bare);

        assertEquals(source, triple, "triple-dollar 修复不得改写完整公式");
        assertEquals(source, adjacent, "相邻公式修复不得改写完整公式");
        assertEquals(source, mixed, "正负分式修复不得改写完整公式");
        assertEquals(source, bare, "裸数学片段修复不得改写完整公式");
        assertEquals(source, escaped, "最终转义不得改写完整公式");
    }

    @Test
    void adjacentMathRepair_mustNotCrossParagraphBoundary() {
        String source = "$(x-\\frac{p}{2})^2+y^2 = (x+\\frac{p}{2})^2$\\par\n"
                + "$x^2-px+\\frac{p^2}{4}+y^2 = x^2+px+\\frac{p^2}{4}$\\par";

        assertEquals(source, TeachingHandoutPdfExportService.normalizeSplitAdjacentInlineMath(source),
                "相邻公式修复只能处理同一行的损坏传输，不能跨段吞掉数学分隔符");
    }

    @Test
    void writerMarkdown_shouldRenderBoldWithoutChangingInlineMath() {
        String markdown = "**题目**：过抛物线 $y^2=2px$（$p>0$）作倾斜角为 $\\theta$ 的直线。\n"
                + "- **分层提示**：利用 $F$ 的定义。";

        String result = TeachingWorkflowCorePolicy.renderWriterMarkdown(markdown, false);

        assertTrue(result.contains("\\textbf{题目}："), "Markdown 粗体标签应成为通用 LaTeX 粗体命令");
        assertTrue(result.contains("\\item \\textbf{分层提示}：利用 $F$ 的定义。"), "列表中的 Markdown 粗体应被渲染");
        assertTrue(result.contains("$\\theta$"), "粗体转换不得移除已有数学分隔符");
        assertFalse(result.contains("**"), "已配对的 Markdown 粗体标记不得出现在发布 LaTeX 中");
    }


    @Test
    void studentWorkspaceAndLectureQuestionBreaks_followGenericLayoutMarkers() {
        String student = "**作答区**\\par\n（请独立完成）\\par";
        String restored = TeachingHandoutPdfExportPolicyPartA.restoreStudentWritingSpaces(student);
        assertTrue(restored.startsWith("\\vspace{12.8em}"), "学生作答区必须保留纯空白书写高度");
        assertFalse(restored.contains("underline"), "学生作答区不得画横线");

        assertTrue(TeachingHandoutPdfExportPolicyPartA.isNumberedPresentationHeading("\\subsection{题目1：定义}"),
                "题目加编号的课堂标题必须被识别为分页边界");
        assertTrue(TeachingHandoutPdfExportPolicyPartA.isNumberedPresentationHeading("\\section{知识点2：方程}"),
                "知识点加编号的课堂标题必须被识别为分页边界");
        String lecture = "\\subsection{题目1：定义}\n正文\n"
                + "\\subsection{知识点2：方程}\n正文";
        String paged = TeachingHandoutPdfExportPolicyPartA.insertLectureQuestionBreaks(lecture);
        assertTrue(paged.contains("\\subsection{题目1：定义}\n正文\n\\clearpage\n\\subsection{知识点2：方程}"),
                "16:10 课堂版必须让每个编号题目独占页面");
    }


    @Test
    void exportSanitizer_shouldRenderHistoricBoldAndRemoveBlankWorkspaceMarker() {
        String source = "**分层提示**\\par\n"
                + "****\\par\n"
                + "过抛物线 $y^2=2px$ 作倾斜角为 $\\theta$ 的直线。\\par";

        String result = TeachingHandoutPdfExportService.sanitizeLatexForExport(source);

        assertTrue(result.contains("\\textbf{分层提示}\\par"), "旧 Writer 快照的粗体标签必须以 LaTeX 粗体输出");
        assertTrue(result.contains("$\\theta$"), "历史快照的公式分隔符必须保持完整");
        assertFalse(result.contains("**"), "发布 LaTeX 不得保留 Markdown 粗体标记或空标记");
    }


    @Test
    void exportSanitizer_shouldPreserveCheckpointTeacherMath() throws Exception {
        String input = java.nio.file.Files.readString(java.nio.file.Path.of(
                "../output/acceptance/handout-mcp/recovered-fe814d79/rebuilt-latex/teacher.tex"));

        String normalized = TeachingHandoutPdfExportService.normalizeTripleDollarMath(input);
        normalized = TeachingHandoutPdfExportService.normalizeSplitAdjacentInlineMath(normalized);
        normalized = TeachingHandoutPdfExportService.normalizeBareStyledMathSymbols(normalized);
        normalized = TeachingHandoutPdfExportService.normalizeSplitVectorCommands(normalized);
        normalized = TeachingHandoutPdfExportService.normalizeSplitFunctionArguments(normalized);
        String lineNormalized = input.lines()
                .map(line -> TeachingHandoutPdfExportService.normalizeMixedMathDelimiters(
                        TeachingHandoutPdfExportService.normalizeBareMathFragments(
                                TeachingHandoutPdfExportService.normalizeCircledNumerals(line.strip()))))
                .collect(java.util.stream.Collectors.joining("\n"));
        assertTrue(lineNormalized.contains("$(x-\\frac{p}{2})^2+y^2 = (x+\\frac{p}{2})^2$\\par"),
                "逐行数学清洗不得改写 checkpoint 的完整等式");

        String escaped = TeachingHandoutPdfExportService.escapeLooseTextSpecials(lineNormalized);
        assertTrue(escaped.contains("$(x-\\frac{p}{2})^2+y^2 = (x+\\frac{p}{2})^2$\\par"),
                "最终文本转义不得改写 checkpoint 的完整等式");

        String blockCleaned = TeachingHandoutPdfExportService.cleanBlocksPreservingPageBreaks(escaped);
        assertTrue(blockCleaned.contains("$(x-\\frac{p}{2})^2+y^2 = (x+\\frac{p}{2})^2$\\par"),
                "块清理不得改写 checkpoint 的完整等式");

        String result = TeachingHandoutPdfExportService.sanitizeLatexForExport(input);

        String titleLine = "过抛物线 $y^2=2px$（$p>0$）的焦点 $F$ 作倾斜角为 $\\theta$ 的直线，与抛物线交于 $A$、$B$ 两点。";
        String titleNormalized = TeachingHandoutPdfExportService.normalizeTripleDollarMath(titleLine);
        assertEquals(titleLine, titleNormalized, "三美元修复不得改写完整题干");
        titleNormalized = TeachingHandoutPdfExportService.normalizeSplitAdjacentInlineMath(titleNormalized);
        assertEquals(titleLine, titleNormalized, "相邻公式修复不得改写完整题干");
        titleNormalized = TeachingHandoutPdfExportService.normalizeBareStyledMathSymbols(titleNormalized);
        assertEquals(titleLine, titleNormalized, "样式符号修复不得改写完整题干");
        titleNormalized = TeachingHandoutPdfExportService.normalizeSplitVectorCommands(titleNormalized);
        assertEquals(titleLine, titleNormalized, "向量修复不得改写完整题干");
        titleNormalized = TeachingHandoutPdfExportService.normalizeSplitFunctionArguments(titleNormalized);
        assertEquals(titleLine, titleNormalized, "函数参数修复不得改写完整题干");
        titleNormalized = TeachingHandoutPdfExportService.normalizeMixedMathDelimiters(
                TeachingHandoutPdfExportService.normalizeBareMathFragments(titleNormalized));
        assertEquals(titleLine, titleNormalized, "逐行数学修复不得改写完整题干");
        assertEquals(titleLine, TeachingHandoutPdfExportService.escapeLooseTextSpecials(titleNormalized),
                "文本转义不得改写完整题干");
        assertEquals(titleLine, TeachingHandoutPdfExportService.sanitizeLatexForExport(titleLine),
                "含多个相邻公式的真实题干必须保留 theta 的数学分隔符");

        assertTrue(result.contains("$\\theta$"), "真实教师讲义中的 theta 必须保持在数学分隔符内");
        assertTrue(result.contains("倾斜角为 $\\theta$ 的直线"),
                "真实教师题干中的 theta 必须保持在数学分隔符内");
        assertTrue(result.contains("$(x-\\frac{p}{2})^2+y^2 = (x+\\frac{p}{2})^2$\\par"),
                "真实教师讲义中的完整等式必须保持在数学分隔符内");
    }
}