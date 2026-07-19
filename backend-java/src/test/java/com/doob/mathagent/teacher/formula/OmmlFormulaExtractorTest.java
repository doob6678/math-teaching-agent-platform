package com.doob.mathagent.teacher.formula;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class OmmlFormulaExtractorTest {

    @Test
    void preservesFractionAndSuperscriptFromRealWordMathXml() {
        String paragraphXml = """
                <w:p xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"
                     xmlns:m="http://schemas.openxmlformats.org/officeDocument/2006/math">
                  <m:oMath>
                    <m:f><m:num><m:r><m:t>x</m:t></m:r></m:num><m:den><m:r><m:t>y</m:t></m:r></m:den></m:f>
                    <m:r><m:t>+</m:t></m:r>
                    <m:sSup><m:e><m:r><m:t>z</m:t></m:r></m:e><m:sup><m:r><m:t>2</m:t></m:r></m:sup></m:sSup>
                  </m:oMath>
                </w:p>
                """;

        List<OmmlFormulaExtractor.ExtractedFormula> formulas = OmmlFormulaExtractor.extractFromParagraphXml(paragraphXml);

        assertThat(formulas).hasSize(1);
        OmmlFormulaExtractor.ExtractedFormula formula = formulas.getFirst();
        assertThat(formula.omml()).contains("<m:f>");
        assertThat(formula.mathMl()).contains("<mfrac>").contains("<msup>");
        assertThat(formula.plainText()).isEqualTo("x/y+z^2");
        assertThat(formula.latex()).isNull();
    }

    @Test
    void ignoresParagraphsWithoutOfficeMathInsteadOfInventingFormulaText() {
        List<OmmlFormulaExtractor.ExtractedFormula> formulas = OmmlFormulaExtractor.extractFromParagraphXml(
                "<w:p xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:r><w:t>x+y</w:t></w:r></w:p>");

        assertThat(formulas).isEmpty();
    }
}
