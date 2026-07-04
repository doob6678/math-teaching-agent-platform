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
}
