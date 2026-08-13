package com.doob.mathagent.teaching.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class TeachingHandoutPdfExportPolicyPartATest {

    /**
     * The publication gate operates on a complete numbered unit, including the AI explanation. A method sentence
     * such as “在平面图中…” must not be mistaken for an omitted source figure, while “图中” in the actual prompt
     * still fails closed without an authorized image marker.
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
        String missingFigure = """
                \\subsection*{第1题 例题}
                \\paragraph{题目}
                图中点 A、B、C 的位置如题设，求线面角。
                """;

        assertThatCode(() -> TeachingHandoutPdfExportPolicyPartA
                .validateQuestionPublicationUnits(selfContainedProjection, "teacher"))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> TeachingHandoutPdfExportPolicyPartA
                .validateQuestionPublicationUnits(missingFigure, "teacher"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("同源、已授权且可读取的图像");
    }

    /** A split `\\vec` command must become one valid inline formula before XeLaTeX sees the draft. */
    @Test
    void restoresSplitVectorCommandInsideInlineMath() {
        String sanitized = TeachingHandoutPdfExportService.sanitizeLatexForExport(
                "\\begin{itemize}\n\\item $$\n\\item 设 $\\vec{OA}$=$\\vec$ a，$\\vec{OB}$=$\\vec$ b。\n\\end{itemize}");

        assertThat(sanitized).contains("$\\vec{OA}=\\vec{a}$", "$\\vec{OB}=\\vec{b}$")
                .doesNotContain("$\\vec$ a", "$\\vec$ b", "\\item $$");
    }

    /** The real exporter must rejoin a function and its TeX operand without consuming ordinary prose. */
    @Test
    void rejoinsAdjacentInlineMathAroundATeXControlWordWithoutAbsorbingProse() {
        String sanitized = TeachingHandoutPdfExportService.sanitizeLatexForExport(
                "公式 $\\sin$\\theta=$\\frac{1}{2}$；变量 $x$ and $y$ 保持分开。");

        assertThat(sanitized)
                .contains("$\\sin\\theta=\\frac{1}{2}$", "$x$ and $y$")
                .doesNotContain("$\\sin$\\theta=$\\frac{1}{2}$");
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
