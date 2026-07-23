package com.doob.mathagent.teaching;

import static org.assertj.core.api.Assertions.assertThat;

import com.doob.mathagent.knowledge.vo.QuestionBankItemResponse;
import com.doob.mathagent.memory.vo.StudentMemoryResponse;
import com.doob.mathagent.teaching.dto.TeachingTaskRequest;
import com.doob.mathagent.teaching.service.TeachingHandoutPdfExportService;
import com.doob.mathagent.teaching.service.TeachingHandoutTemplateProfile;
import com.doob.mathagent.teaching.service.TeachingHandoutTemplateService;
import com.doob.mathagent.teaching.service.TeachingWorkflowService;
import com.doob.mathagent.teaching.vo.TeachingTaskResponse;
import java.lang.reflect.Method;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Regression checks for concrete knowledge-point filtering before a question enters a printable handout. */
class TeachingQuestionEvidenceAlignmentTest {

    @Test
    void rejectsBroadGeometryQuestionForLinePlaneAngleLesson() throws Exception {
        TeachingTaskRequest request = new TeachingTaskRequest(
                "request-line-angle", "", "空间向量线面角", 3);
        QuestionBankItemResponse unrelated = question(
                "q-prism", "作业1 三棱柱", "在三棱柱 ABC-A1B1C1 中求体积。", "基础");
        QuestionBankItemResponse aligned = question(
                "q-angle", "线面角基础", "利用法向量求直线与平面的线面角。", "基础");

        Method matcher = TeachingWorkflowService.class.getDeclaredMethod(
                "hasSpecificQuestionTopicMatch", TeachingTaskRequest.class, QuestionBankItemResponse.class);
        matcher.setAccessible(true);

        assertThat((Boolean) matcher.invoke(null, request, unrelated)).isFalse();
        assertThat((Boolean) matcher.invoke(null, request, aligned)).isTrue();
    }

    @Test
    void allowsBroadQuestionWhenTheLessonItselfOnlyNamesTheDomain() throws Exception {
        TeachingTaskRequest request = new TeachingTaskRequest(
                "request-space-vector", "", "空间向量", 3);
        QuestionBankItemResponse question = question(
                "q-prism", "空间向量基础", "在三棱柱中建立空间向量并求长度。", "基础");

        Method matcher = TeachingWorkflowService.class.getDeclaredMethod(
                "hasSpecificQuestionTopicMatch", TeachingTaskRequest.class, QuestionBankItemResponse.class);
        matcher.setAccessible(true);

        assertThat((Boolean) matcher.invoke(null, request, question)).isTrue();
    }

    @Test
    void usesAConcreteModelAuthoredStrategyHeadingAndRejectsGenericHeading() throws Exception {
        Method heading = TeachingWorkflowService.class.getDeclaredMethod("methodHeading", String.class, String.class);
        heading.setAccessible(true);

        assertThat((String) heading.invoke(null, "线面角", "方法标题：从法向量到线面角"))
                .isEqualTo("从法向量到线面角");
        assertThat((String) heading.invoke(null, "线面角", "方法标题：核心方法"))
                .isEqualTo("线面角：条件识别与推导");
    }

    @Test
    void groupsYearAndSynchronizationSuffixVariantsUnderOneColoringTopic() throws Exception {
        TeachingTaskRequest request = new TeachingTaskRequest(
                "request-coloring", "如图，一个地区分为5个行政区域，相邻区域不得使用同一颜色，现有4种颜色，求不同着色方法数。",
                "2013年涂色问题地图图片证据", 3);
        QuestionBankItemResponse original = question(
                "q-original", "2013年涂色问题", "如图，一个地区分为5个行政区域，现有四种颜色。", "中等");
        QuestionBankItemResponse fiveColor = question(
                "q-five", "2013涂色问题-教师同步验收", "用不同的5种颜色分别为ABCDE五部分着色，相邻部分不能同色。", "中等");
        QuestionBankItemResponse sixColor = question(
                "q-six", "2013涂色问题-教师同步验收", "用6种不同的颜色给图中的4个格子涂色，相邻格子颜色不同。", "中等");

        Method matcher = TeachingWorkflowService.class.getDeclaredMethod(
                "hasSpecificQuestionTopicMatch", TeachingTaskRequest.class, QuestionBankItemResponse.class);
        matcher.setAccessible(true);
        Method key = TeachingWorkflowService.class.getDeclaredMethod(
                "questionKnowledgePointKey", TeachingTaskRequest.class, QuestionBankItemResponse.class);
        key.setAccessible(true);

        assertThat((Boolean) matcher.invoke(null, request, original)).isTrue();
        assertThat((Boolean) matcher.invoke(null, request, fiveColor)).isTrue();
        assertThat((Boolean) matcher.invoke(null, request, sixColor)).isTrue();
        assertThat((String) key.invoke(null, request, original))
                .isEqualTo((String) key.invoke(null, request, fiveColor))
                .isEqualTo((String) key.invoke(null, request, sixColor));
    }

    @Test
    void removesPromptOnlyMethodHeadingFromPrintableExport() {
        String sanitized = TeachingHandoutPdfExportService.sanitizeLatexForExport(
                "\\section{涂色问题}\n方法标题：邻接图与分步计数\n真实的数学步骤。");

        assertThat(sanitized)
                .doesNotContain("方法标题")
                .contains("真实的数学步骤");
    }

    @Test
    void doesNotReuseFourColorTeacherConclusionForSixColorVariation() throws Exception {
        Method matcher = TeachingWorkflowService.class.getDeclaredMethod(
                "supportingEvidenceMatchesQuestion", String.class, List.class);
        matcher.setAccessible(true);
        TeachingEvidence fourColorEvidence = new TeachingEvidence(
                "TEACHER_RESOURCE", "2013涂色问题", "block-4", 0,
                "现有四种颜色可供选择，合计：24+48=72。");

        assertThat((Boolean) matcher.invoke(null,
                "如图，用6种不同的颜色给图中的4个格子涂色，相邻格子颜色不同。", List.of(fourColorEvidence)))
                .isFalse();
        assertThat((Boolean) matcher.invoke(null,
                "如图，一个地区分为5个行政区域，现有四种颜色可供选择。", List.of(fourColorEvidence)))
                .isTrue();
    }

    @Test
    void rejectsAnImageEvidenceBlockThatMixesNeighbouringColorVariations() throws Exception {
        Method matcher = TeachingWorkflowService.class.getDeclaredMethod(
                "supportingEvidenceMatchesQuestion", String.class, List.class);
        matcher.setAccessible(true);
        TeachingEvidence mixedVariationBlock = new TeachingEvidence(
                "TEACHER_RESOURCE", "2013年涂色问题", "block-mixed", 0,
                "原题现有四种颜色；同步变式现有5种颜色；拓展变式用6种不同的颜色。",
                "C:/authorized/original-map.png");

        // A shared OCR window has no machine-verifiable one-to-one relation between its image and any of the
        // neighbouring variants.  The exporter must omit the asset instead of silently assigning it to one question.
        assertThat((Boolean) matcher.invoke(null,
                "如图，一个地区分为5个行政区域，现有四种颜色可供选择。", List.of(mixedVariationBlock)))
                .isFalse();
        assertThat((Boolean) matcher.invoke(null,
                "如图，用6种不同的颜色给图中的4个格子涂色，相邻格子颜色不同。", List.of(mixedVariationBlock)))
                .isFalse();
    }

    @Test
    void prefersACompleteClassificationSumOverAnIsolatedOcrEquality() throws Exception {
        Method compact = TeachingWorkflowService.class.getDeclaredMethod("compactQuestionBankAnswer", String.class);
        compact.setAccessible(true);

        String printable = (String) compact.invoke(null,
                "OCR 残片 2=15；按分类计算 30 + 360 = 390；答案：390");

        assertThat(printable)
                .contains("30 + 360 = 390")
                .doesNotContain("2=15");
    }

    @Test
    void omitsAFigureDependentQuestionWhenNoAuthorizedFigureWasSynchronized() throws Exception {
        Method append = TeachingWorkflowService.class.getDeclaredMethod(
                "appendTeacherQuestion", StringBuilder.class, int.class, String.class, TeachingEvidence.class,
                String.class, String.class, String.class, String.class);
        append.setAccessible(true);
        StringBuilder latex = new StringBuilder();
        TeachingEvidence figureDependentQuestion = new TeachingEvidence(
                "QUESTION_BANK", "空间向量作业", "q-figure", 0,
                "如图，在三棱柱 ABC-A1B1C1 中，求线面角。", "");

        int nextNumber = (Integer) append.invoke(null, latex, 1, "例题", figureDependentQuestion,
                "", "", "", "先分析条件。");

        assertThat(nextNumber).isEqualTo(1);
        assertThat(latex).isEmpty();
    }

    @Test
    void removesAStandaloneQuestionLabelAndDuplicateStemBeforePrinting() throws Exception {
        Method questionText = TeachingWorkflowService.class.getDeclaredMethod("questionTextOnly", String.class);
        questionText.setAccessible(true);

        String printable = (String) questionText.invoke(null, """
                赵礼显数学作业 1. 如图，在三棱柱 ABC-A1B1C1 中，CC1 ⟂ 平面 ABC。
                题目
                赵礼显数学作业 1. 如图，在三棱柱 ABC-A1B1C1 中，CC1 ⟂ 平面 ABC，求二面角。
                答案要点：略
                """);

        assertThat(printable)
                .doesNotContain("题目\n")
                .doesNotContain("赵礼显数学作业", "赵礼显")
                .contains("求二面角");
    }

    @Test
    void rejectsQuestionStemsWithUnresolvedOcrMathBoxes() throws Exception {
        Method unusable = TeachingWorkflowService.class.getDeclaredMethod("isUnusableQuestionText", String.class);
        unusable.setAccessible(true);

        // A square in a mathematical relation is not an answer blank.  Its source OCR must be repaired from the
        // synchronized document before a teacher handout can claim to contain a real, reviewable example.
        assertThat((Boolean) unusable.invoke(null, "CC1 □ 平面 ABC，求线面角。"))
                .isTrue();
    }

    @Test
    void zhaoMasterStartsWithTheVerifiedQuestionInsteadOfGenericLessonScaffolding() throws Exception {
        Method build = TeachingWorkflowService.class.getDeclaredMethod(
                "buildTeacherHandoutLatex", TeachingTaskRequest.class, List.class, List.class,
                StudentMemoryResponse.class,
                TeachingHandoutTemplateProfile.class, TeachingTaskResponse.AiDraft.class, TeachingDraftSections.class);
        build.setAccessible(true);
        TeachingEvidence question = new TeachingEvidence(
                "QUESTION_BANK", "函数题", "q-function", 0,
                "已知函数 f(x)=x^2-4x+3，求其顶点坐标。答案要点：顶点为(2,-1)。");
        TeachingKnowledgePointPack pack = new TeachingKnowledgePointPack("二次函数顶点", List.of(), question, null);
        TeachingHandoutTemplateProfile zhao = new TeachingHandoutTemplateService().resolve("zhao_lixian_2025_master_v1");

        String latex = (String) build.invoke(null,
                new TeachingTaskRequest("zhao-real-question", "", "二次函数顶点", 3),
                List.of(question), List.of(pack), null, zhao,
                new TeachingTaskResponse.AiDraft(false, "", "", 0, 0, 0, "", ""),
                new TeachingDraftSections("【易错提醒】\n不要漏写定义域。", "", List.of(), List.of(), List.of(), List.of()));

        assertThat(latex)
                .contains("第1题", "已知函数")
                .doesNotContain("题型总览", "掌握“", "本节目标", "易错提醒");
    }

    @Test
    void bindsEachVariationToItsOwnNumberedModelAnswerInsteadOfPrintingAnUnverifiedPlaceholder() throws Exception {
        Method build = TeachingWorkflowService.class.getDeclaredMethod(
                "buildTeacherHandoutLatex", TeachingTaskRequest.class, List.class, List.class,
                StudentMemoryResponse.class,
                TeachingHandoutTemplateProfile.class, TeachingTaskResponse.AiDraft.class, TeachingDraftSections.class);
        build.setAccessible(true);
        TeachingEvidence firstQuestion = new TeachingEvidence(
                "QUESTION_BANK", "真题 1", "q-1", 0, "1．已知 z=-1-i，求 |z|。", "");
        TeachingEvidence thirteenthQuestion = new TeachingEvidence(
                "QUESTION_BANK", "真题 13", "q-13", 0,
                "13．已知α为第一象限角，β为第三象限角，tanα+tanβ=4，求sin(α+β)。", "");
        String modelExplanation = """
                【知识定位】复数与三角函数。
                【题型识别】逐题计算。
                【方法步骤】逐题保留等式。
                【例题详解】题1：已知 z=-1-i，求 |z|。条件识别：复数模长。步骤：由模长定义计算，故结果为$\\sqrt{2}$。
                题13：已知α为第一象限角，β为第三象限角，tanα+tanβ=4，求sin(α+β)。条件识别：和角公式。推导依据：先由正切和角公式得到$\\tan(α+β)=-2\\sqrt{2}$，再结合象限判定正弦符号，故$\\sin(α+β)=-\\frac{2\\sqrt{2}}{3}$。
                【答案与评分点】题1（5分）：答案$\\sqrt{2}$；题13（5分）：答案$-\\frac{2\\sqrt{2}}{3}$。
                【易错提醒】注意象限符号。
                【课堂追问】说明和角正切公式的适用条件。
                """;
        TeachingTaskResponse.AiDraft draft = new TeachingTaskResponse.AiDraft(
                true, "live", "gpt-5.6-luna", 1, 1, 2, "", modelExplanation);
        TeachingKnowledgePointPack pack = new TeachingKnowledgePointPack(
                "复数与三角函数", List.of(), firstQuestion, thirteenthQuestion);
        TeachingHandoutTemplateProfile zhao = new TeachingHandoutTemplateService().resolve("zhao_lixian_2025_master_v1");

        String latex = (String) build.invoke(null,
                new TeachingTaskRequest("zhao-numbered-variation", "", "真题逐题讲解", 3),
                List.of(firstQuestion, thirteenthQuestion), List.of(pack), null, zhao, draft,
                new TeachingDraftSections(modelExplanation, "", List.of(), List.of(), List.of(), List.of()));

        assertThat(latex)
                .contains("第2题", "-\\frac{2\\sqrt{2}}{3}")
                .doesNotContain("题库未提供可核验答案", "题\\par");
    }

    @Test
    void convertsUnsupportedTriangleAndAngleGlyphsToRenderableLatexMath() throws Exception {
        Method escape = TeachingWorkflowService.class.getDeclaredMethod("escapeLatex", String.class);
        escape.setAccessible(true);

        String printable = (String) escape.invoke(null, "记△ABC中，∠A=30°，且AB⊥AC。");

        assertThat(printable)
                .contains("$\\triangle$", "$\\angle$")
                .doesNotContain("△", "∠");
    }

    @Test
    void allowsOneCompletedTaskWithKnownMissingGeometryGlyphsToBeRepairedByResume() throws Exception {
        Method recoverable = TeachingWorkflowService.class.getDeclaredMethod(
                "hasRecoverableTeacherPublicationIssue", TeachingTaskResponse.class);
        recoverable.setAccessible(true);
        TeachingTaskResponse staleVisualDraft = new TeachingTaskResponse(
                "task-geometry-glyph", "request-geometry-glyph", "tenant-a", "teacher", "teacher-a",
                com.doob.mathagent.teaching.TeachingTaskStatus.COMPLETED, "如图求角", "立体几何", List.of(),
                List.of(), List.of(), "教师版含△ABC与∠A", "教师版含△ABC与∠A", "学生版", List.of(),
                new TeachingTaskResponse.MemoryReuse(false, "", "", "", 0, ""), List.of(),
                new TeachingTaskResponse.AiDraft(false, "", "", 0, 0, 0, "", ""), null);

        assertThat((Boolean) recoverable.invoke(null, staleVisualDraft)).isTrue();
    }

    @Test
    void keepsDistinctAtomicBlocksFromTheSameFeishuDocumentForQuestionImageBinding() throws Exception {
        Method key = TeachingWorkflowService.class.getDeclaredMethod("canonicalEvidenceKey", TeachingEvidence.class);
        key.setAccessible(true);
        TeachingEvidence originalQuestion = new TeachingEvidence(
                "TEACHER_RESOURCE", "涂色问题 / JdADd9Qc6o5JcbxdzsJcrn3qnJf / 2013年涂色问题",
                "block-original", 0, "如图，现有四种颜色。", "C:/authorized/original.png");
        TeachingEvidence sixColorVariation = new TeachingEvidence(
                "TEACHER_RESOURCE", "涂色问题 / JdADd9Qc6o5JcbxdzsJcrn3qnJf / 改版6种颜色",
                "block-six", 0, "如图，用6种不同的颜色。", "C:/authorized/six.png");

        // Synchronization produces several atomic blocks under one Feishu document.  Deduplication may remove a
        // mirror, but must not collapse the original question block and a neighbouring variation into one source.
        assertThat((String) key.invoke(null, originalQuestion))
                .isNotEqualTo((String) key.invoke(null, sixColorVariation));
    }

    private static QuestionBankItemResponse question(String id, String title, String text, String difficulty) {
        return new QuestionBankItemResponse(
                id, "tenant-a", "teacher-a", "TEACHER_PRIVATE", title, text, "{}", difficulty, "active", List.of());
    }
}
