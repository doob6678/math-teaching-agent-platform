package com.doob.mathagent.teaching.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** Covers publication failures that must happen before any XeLaTeX process is started. */
class HandoutPublicationGateTest {
    private final HandoutPublicationGate gate = new HandoutPublicationGate();

    @Test
    void rejectsInternalMetadataAndMalformedFraction() {
        assertThatThrownBy(() -> gate.validateLatex("\\section{第1题}\npromptTokens=12", "teacher"))
                .hasMessageContaining("内部模型元数据");
        assertThatThrownBy(() -> gate.validateLatex("\\section{第1题}\ny=\\frac x{2}", "teacher"))
                .hasMessageContaining("frac");
    }

    @Test
    void rejectsQuestionReorderingAndMissingQuestionNumber() {
        assertThatThrownBy(() -> gate.validateLatex(
                "\\section{第2题}\n内容\n\\section{第1题}\n内容", "student"))
                .hasMessageContaining("题号不连续");
    }
    @Test
    void rejectsTeacherAnswerAndScoringBlocksFromStudentVersion() {
        assertThatThrownBy(() -> gate.validateLatex(
                "\\section{第1题}\n\\paragraph{答案与评分点}\n证明过程", "student"))
                .hasMessageContaining("学生版包含教师答案或评分内容");
        assertThatThrownBy(() -> gate.validateLatex(
                "\\section{第1题}\n参考解析：略", "student"))
                .hasMessageContaining("学生版包含教师答案或评分内容");
    }
}
