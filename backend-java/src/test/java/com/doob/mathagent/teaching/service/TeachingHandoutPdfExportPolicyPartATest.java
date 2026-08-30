package com.doob.mathagent.teaching.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class TeachingHandoutPdfExportPolicyPartATest {

    /**
     * 2026-08-30 起按老板决定取消图片硬门槛：图中/投影等文字不再触发“缺同源图像”的发布失败，
     * 图片选用交给 AI 相关性提示词（见 docs 的讲义架构与 no-hard-gate 结论）。
     */
    @Test
    void distinguishesPlanarProjectionProseFromARequiredSourceFigure() {
        String selfContainedProjection = """
                \\subsection*{第1题 例题}
                \\paragraph{题目}
                已知直线与平面的关系，求线面角。
                \\paragraph{推导}
                折叠前先在平面图中找出对应的垂直关系，再转入空间证明。
                """;
        String proseOnlyFigure = """
                \\subsection*{第1题 例题}
                \\paragraph{题目}
                图中点 A、B、C 的位置如题设，求线面角。
                """;

        assertThatCode(() -> TeachingHandoutPdfExportPolicyPartA
                .validateQuestionPublicationUnits(selfContainedProjection, "teacher"))
                .doesNotThrowAnyException();
        assertThatCode(() -> TeachingHandoutPdfExportPolicyPartA
                .validateQuestionPublicationUnits(proseOnlyFigure, "teacher"))
                .doesNotThrowAnyException();
    }

    /**
     * 含 $ 定界的行不再做跨 $ 合并（skip 规则，避免 MIXED_MATH_DELIMITER 误伤）；分裂的 \vec 只补全花括号，
     * 空的 \item $$ 被移除。合并行为由写作端保证，不再由导出端跨定界重写。
     */
    @Test
    void restoresSplitVectorCommandInsideInlineMath() {
        String sanitized = TeachingHandoutPdfExportService.sanitizeLatexForExport(
                "\\begin{itemize}\n\\item $$\n\\item 设 $\\vec{OA}$=$\\vec$ a，$\\vec{OB}$=$\\vec$ b。\n\\end{itemize}");

        assertThat(sanitized).contains("$\\vec{OA}$=$\\vec{a}$", "$\\vec{OB}$=$\\vec{b}$")
                .doesNotContain("$\\vec$ a", "$\\vec$ b", "\\item $$");
    }

    /** 已含 $ 定界且无需补全的行必须原样保留：不做跨定界合并，也不吞并普通散文。 */
    @Test
    void keepsPreDelimitedInlineMathVerbatimWithoutAbsorbingProse() {
        String sanitized = TeachingHandoutPdfExportService.sanitizeLatexForExport(
                "公式 $\\sin$\\theta=$\\frac{1}{2}$；变量 $x$ and $y$ 保持分开。");

        assertThat(sanitized)
                .contains("$\\sin$\\theta=$\\frac{1}{2}$", "$x$ and $y$")
                .doesNotContain("$\\sin\\theta=\\frac{1}{2}$");
    }

    /** Bare styled symbols are valid AI math content but must not be passed to XeLaTeX outside math mode. */
    @Test
    void wrapsLooseStyledSymbolsWhilePreservingExistingInlineMath() {
        String sanitized = TeachingHandoutPdfExportService.sanitizeLatexForExport(
                "方向向量为 \\mathbf a，且已有 $x=\\mathbf b$。");

        assertThat(sanitized).contains("$\\mathbf{a}$", "$x=\\mathbf b$")
                .doesNotContain("$x=$$\\mathbf{b}$$");
    }
}
