package com.doob.mathagent.infrastructure.text;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class FormulaMarkupSanitizerTest {

    @Test
    void convertsUnsupportedDisplayAndInlineDelimitersToFeishuMath() {
        String sanitized = FormulaMarkupSanitizer.sanitizeFeishuMath(
                "Use \\(x^2\\), then \\[x^2-4x+3=0\\].");

        assertThat(sanitized).contains("$x^2$", "$$\nx^2-4x+3=0\n$$");
        assertThat(sanitized).doesNotContain("\\(", "\\)", "\\[", "\\]");
    }

    @Test
    void convertsAlignEnvironmentToDisplayMathWithoutAlignWrapper() {
        String sanitized = FormulaMarkupSanitizer.sanitizeFeishuMath(
                "Solve: \\begin{align} f(x)&=x^2-4x+3\\\\&=(x-1)(x-3) \\end{align}");

        assertThat(sanitized).contains("$$", "f(x)=x^2-4x+3", "=(x-1)(x-3)");
        assertThat(sanitized).doesNotContain("\\begin{align}", "\\end{align}", "&");
    }

    @Test
    void wrapsCommonBareHighSchoolFormulaTextAndConvertsUnicodeSuperscripts() {
        String sanitized = FormulaMarkupSanitizer.sanitizeFeishuMath(
                "标准方程：x\u00b2/a\u00b2-y\u00b2/b\u00b2=1，参数关系：c\u00b2=a\u00b2+b\u00b2，焦点坐标：(\u00b1c,0)。");

        assertThat(sanitized)
                .contains("$x^2/a^2-y^2/b^2=1$", "$c^2=a^2+b^2$", "$(\\pm c,0)$")
                .doesNotContain("x\u00b2", "a\u00b2", "\u00b1c");
    }

    @Test
    void doesNotWrapTextAlreadyInsideMathDelimitersTwice() {
        String sanitized = FormulaMarkupSanitizer.sanitizeFeishuMath(
                "先用 $c^2=a^2+b^2$，再判断 x\u00b2/a\u00b2-y\u00b2/b\u00b2=1。");

        assertThat(sanitized).contains("$c^2=a^2+b^2$", "$x^2/a^2-y^2/b^2=1$");
        assertThat(sanitized).doesNotContain("$$c^2=a^2+b^2$$");
    }

    @Test
    void wrapsSubscriptEquationsUsedInHandoutHints() {
        String sanitized = FormulaMarkupSanitizer.sanitizeFeishuMath(
                "先比较 a_1=a^2，再写出 Sn=an+bn。");

        assertThat(sanitized).contains("$a_1=a^2$", "$Sn=an+bn$");
    }

    @Test
    void wrapsBareLatexFractionEquationsUsedInHandouts() {
        String sanitized = FormulaMarkupSanitizer.sanitizeFeishuMath(
                "设解析式 y=\\frac{k}{x}，代入后得到 y=-\\frac{6}{x}，标准方程 x²/a²-y²/b²=1。");

        assertThat(sanitized)
                .contains("$y=\\frac{k}{x}$", "$y=-\\frac{6}{x}$", "$x^2/a^2-y^2/b^2=1$")
                .doesNotContain("x²", "a²", "y²");
    }

    @Test
    void normalizesSimpleSlashFractionsWithoutBreakingSquaredRatios() {
        String sanitized = FormulaMarkupSanitizer.sanitizeFeishuMath(
                "反比例函数写成 y=k/x，面积公式里常见 (a+b)/c，标准方程仍是 x²/a²-y²/b²=1。");

        assertThat(sanitized)
                .contains("$y=\\frac{k}{x}$", "$\\frac{a+b}{c}$", "$x^2/a^2-y^2/b^2=1$")
                .doesNotContain("y=k/x", "(a+b)/c");
    }

    @Test
    void normalizesShortNumericSlashFractionsUsedInHandouts() {
        String sanitized = FormulaMarkupSanitizer.sanitizeFeishuMath(
                "若 sinA=1/2，则可继续判断；有理式 (x+1)/(x-1) 需要先看定义域，标准方程仍是 x²/a²-y²/b²=1。");

        assertThat(sanitized)
                .contains("$sinA=\\frac{1}{2}$", "$\\frac{x+1}{x-1}$", "$x^2/a^2-y^2/b^2=1$")
                .doesNotContain("sinA=1/2", "(x+1)/(x-1)");
    }

    @Test
    void restoresLegacyEscapedMathTextBeforeWrappingBareFormula() {
        String sanitized = FormulaMarkupSanitizer.sanitizeFeishuMath(
                "参数关系 c\\textasciicircum{}2=a\\textasciicircum{}2+b\\textasciicircum{}2，解析式 y=\\textbackslash{}frac{k}{x}。");

        assertThat(sanitized)
                .contains("$c^2=a^2+b^2$", "$y=\\frac{k}{x}$")
                .doesNotContain("textasciicircum", "textbackslash{}frac");
    }

    @Test
    void convertsBareUnicodeGeometryRelationsToMathCommandsWithoutBreakingExistingMath() {
        String sanitized = FormulaMarkupSanitizer.sanitizeFeishuMath(
                "CC1⊥平面 ABC，且 $A⊥B$，再判断 l∥m。");

        assertThat(sanitized)
                .contains("CC1$\\perp$平面 ABC", "$A\\perp B$", "l$\\parallel$m")
                .doesNotContain("⊥", "∥");
    }

    @Test
    void repairsTransportWhitespaceInsideFracCommand() {
        String sanitized = FormulaMarkupSanitizer.sanitizeFeishuMath(
                "对称轴 x=-\\\\ rac{b}{2a}，结果为 $\\\\ rac{1}{2}$。");

        assertThat(sanitized).contains("\\\\frac{b}{2a}", "\\\\frac{1}{2}");
        assertThat(sanitized).doesNotContain(" rac");
    }

    @Test
    void canonicalizesOnlyUnambiguousRadicalsAndFractions() {
        String sanitized = FormulaMarkupSanitizer.sanitizeFeishuMath(
                "面积为 √(a+b)，且 sinA=1/2，另有 \\frac a{\\sin A}。 ");

        assertThat(sanitized)
                .contains("\\sqrt{a+b}", "$sinA=\\frac{1}{2}$", "\\frac{a}{\\sin A}")
                .doesNotContain("√(", "\\frac a");
    }

    @Test
    void preservesAmbiguousBareRadicalForTheExportGateToReject() {
        String sanitized = FormulaMarkupSanitizer.sanitizeFeishuMath("错误候选 √3a 必须要求模型补花括号。");

        assertThat(sanitized).contains("√3a");
    }
}
