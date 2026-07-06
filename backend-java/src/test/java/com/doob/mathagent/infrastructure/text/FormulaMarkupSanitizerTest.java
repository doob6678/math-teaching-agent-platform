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
}
