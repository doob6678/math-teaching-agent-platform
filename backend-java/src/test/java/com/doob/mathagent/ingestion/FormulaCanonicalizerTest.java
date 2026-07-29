package com.doob.mathagent.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Keeps equivalent presentational LaTex out of the exact-dedup fingerprint. */
class FormulaCanonicalizerTest {

    @Test
    void normalizesWhitespaceAndEquivalentFracSyntaxWithoutDroppingParameters() {
        assertThat(FormulaCanonicalizer.canonicalize("\\frac { a } { b } + x_ { 2 }")).isEqualTo("frac(a,b)+x_(2)");
        assertThat(FormulaCanonicalizer.canonicalize("\\frac{a}{b}+x_{3}")).isNotEqualTo("frac(a,b)+x_(2)");
    }
}
