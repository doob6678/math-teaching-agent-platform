package com.doob.mathagent.teaching.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class TeachingEvidenceSnippetSanitizerTest {

    @Test
    void removesMarkdownMetadataAndKeepsReadableMathSnippet() {
        String cleaned = TeachingEvidenceSnippetSanitizer.sanitizeCompact("""
                # p159
                - 书名：人教B版选择性必修一
                - PDF页码：159
                ## 正文
                双曲线的渐近线可写成 $y=\\frac{b}{a}x$ 和 $y=-\\frac{b}{a}x$。
                """);

        assertThat(cleaned)
                .contains("双曲线", "$y=\\frac{b}{a}x$")
                .doesNotContain("书名", "PDF页码", "## 正文", "# p159");
    }

    @Test
    void replacesMojibakeOcrFragmentsWithLowQualityHint() {
        String cleaned = TeachingEvidenceSnippetSanitizer.sanitizeCompact("""
                # p88
                Ѧ ԣХ᛫ ԡӜ ᚠᚢᚦ ����
                ԣХ᛫ѦԡӜᚠᚢᚦ
                """);

        assertThat(cleaned)
                .contains("片段质量较低")
                .doesNotContain("Ѧ", "ԣ", "ᚠ", "�");
    }

    @Test
    void stripsEmbeddedOcrMojibakeWhileKeepingReadableSourceText() {
        String cleaned = TeachingEvidenceSnippetSanitizer.sanitizeCompact(
                "人教B版必修一数学 / 3.1.1 / PDF 96：3.1 函数的概念与性质89 3.1.1 Ѧ ԣХ᛫ ܪ+ắᔢ 我们已经学习过一些函数的知识");

        assertThat(cleaned)
                .contains("人教B版必修一数学", "函数的概念与性质", "我们已经学习过一些函数的知识")
                .doesNotContain("Ѧ", "ԣ", "᛫", "ܪ", "ắ", "ᔢ");
    }
}
