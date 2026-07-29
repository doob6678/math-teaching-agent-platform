package com.doob.mathagent.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Covers the printed-number variants that bound question regions before any visual-model review. */
class QuestionNumberDetectorTest {

    @Test
    void recognizesTopLevelQuestionButNotAParenthesizedSubQuestion() {
        assertThat(QuestionNumberDetector.topLevelNumber("17．已知函数 f(x)=" )).contains("17");
        assertThat(QuestionNumberDetector.topLevelNumber("（1）求 f(0)" )).isEmpty();
        assertThat(QuestionNumberDetector.topLevelNumber("17.(2) 证明不等式" )).contains("17");
    }

    @Test
    void recognizesChineseQuestionPrefixAndRejectsYears() {
        assertThat(QuestionNumberDetector.topLevelNumber("第18题：求直线方程")).contains("18");
        assertThat(QuestionNumberDetector.topLevelNumber("2024 年新课标卷")).isEmpty();
    }
}
