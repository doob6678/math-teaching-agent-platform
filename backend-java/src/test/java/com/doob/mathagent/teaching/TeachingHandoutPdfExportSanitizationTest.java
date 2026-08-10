package com.doob.mathagent.teaching;

import static org.assertj.core.api.Assertions.assertThat;

import com.doob.mathagent.teaching.service.TeachingHandoutPdfExportService;
import org.junit.jupiter.api.Test;

/** Regression coverage for persisted OCR/transport placeholders at the publication boundary. */
class TeachingHandoutPdfExportSanitizationTest {

    /** A corrupt placeholder must never appear as a plausible printable problem beside valid lesson content. */
    @Test
    void removesUnreadablePlaceholderLinesWhileRetainingNeighbouringLessonContent() {
        String sanitized = TeachingHandoutPdfExportService.sanitizeLatexForExport("""
                \\section{函数基础}
                ??????
                已知 $f(x)=x^2$，求 $f(2)$。
                """);

        assertThat(sanitized)
                .contains("\\section{函数基础}", "已知 $f(x)=x^2$，求 $f(2)$。")
                .doesNotContain("??????");
    }
}
