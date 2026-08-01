package com.doob.mathagent.teaching.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class TeachingWorkflowQuestionRendererTest {

    @Test
    void rejectsImportedSolutionFragmentsAndTodoRowsAsPrintableQuestions() {
        assertThat(TeachingWorkflowQuestionRenderer.isUnusableQuestionText(
                "化简目标表达式 利用同角三角函数关系与两角和的正弦公式展开：\\tan C\\left(...\\right)"))
                .isTrue();
        assertThat(TeachingWorkflowQuestionRenderer.isUnusableQuestionText("TODO 解三角形中三角函数怎么化"))
                .isTrue();
        assertThat(TeachingWorkflowQuestionRenderer.isUnusableQuestionText(
                "在三角形 ABC 中，已知 a=5，b=7，A=30°，判断三角形解的个数。"))
                .isFalse();
    }
}
