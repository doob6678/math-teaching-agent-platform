package com.doob.mathagent.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import com.doob.mathagent.knowledge.service.InMemoryKnowledgeQuestionBankStore;
import com.doob.mathagent.knowledge.service.KnowledgePointRecord;
import com.doob.mathagent.knowledge.service.KnowledgeRelationRecord;
import com.doob.mathagent.knowledge.service.QuestionBankSearchText;
import com.doob.mathagent.knowledge.service.QuestionBankItemRecord;
import java.util.List;
import org.junit.jupiter.api.Test;

class KnowledgeQuestionBankStoreTest {

    @Test
    void listsTenantScopedKnowledgePointsAndQuestionsByViewerScope() {
        InMemoryKnowledgeQuestionBankStore store = new InMemoryKnowledgeQuestionBankStore();
        store.saveKnowledgePoint(new KnowledgePointRecord(
                "kp-private",
                "school-a",
                "teacher-1",
                "TEACHER_PRIVATE",
                "函数定义域",
                "函数/基础",
                "active",
                "manual"));
        store.saveKnowledgePoint(new KnowledgePointRecord(
                "kp-other-teacher",
                "school-a",
                "teacher-2",
                "TEACHER_PRIVATE",
                "其他教师私有知识点",
                "函数/基础",
                "active",
                "manual"));
        store.saveKnowledgePoint(new KnowledgePointRecord(
                "kp-vip",
                "school-a",
                "admin-1",
                "MATH_VIP",
                "空间向量数量积",
                "选择性必修/空间向量",
                "active",
                "admin"));
        store.saveKnowledgePoint(new KnowledgePointRecord(
                "kp-other-tenant",
                "school-b",
                "teacher-1",
                "MATH_VIP",
                "不应泄露",
                "other",
                "active",
                "manual"));
        store.saveQuestion(new QuestionBankItemRecord(
                "q-private",
                "school-a",
                "teacher-1",
                "TEACHER_PRIVATE",
                "函数定义域求解",
                "求函数 f(x) 的定义域。",
                "{\"steps\":[]}",
                "easy",
                "active",
                List.of("kp-private")));
        store.saveQuestion(new QuestionBankItemRecord(
                "q-other-teacher",
                "school-a",
                "teacher-2",
                "TEACHER_PRIVATE",
                "其他教师私题",
                "不应返回。",
                "{}",
                "easy",
                "active",
                List.of("kp-other-teacher")));
        store.saveQuestion(new QuestionBankItemRecord(
                "q-vip",
                "school-a",
                "admin-1",
                "MATH_VIP",
                "空间向量夹角",
                "已知空间向量，求夹角。",
                "{}",
                "medium",
                "active",
                List.of("kp-vip")));

        assertThat(store.listKnowledgePoints("school-a", "teacher", "teacher-1"))
                .extracting(KnowledgePointRecord::knowledgePointId)
                .containsExactly("kp-private", "kp-vip");
        assertThat(store.searchQuestions("school-a", "teacher", "teacher-1", "函数", 10))
                .extracting(QuestionBankItemRecord::questionId)
                .containsExactly("q-private");
        assertThat(store.searchQuestions("school-a", "teacher", "teacher-1", "", 10))
                .extracting(QuestionBankItemRecord::questionId)
                .containsExactly("q-private", "q-vip");
        assertThat(store.searchQuestions("school-a", "teacher", "teacher-1", "向量 夹角", 10))
                .extracting(QuestionBankItemRecord::questionId)
                .containsExactly("q-vip");
        assertThat(store.searchQuestions("school-a", "admin", "admin-1", "空间", 10))
                .extracting(QuestionBankItemRecord::questionId)
                .containsExactly("q-vip");
    }

    @Test
    void expandsNaturalMathTopicQueryToVisibleQuestionTypes() {
        InMemoryKnowledgeQuestionBankStore store = new InMemoryKnowledgeQuestionBankStore();
        store.saveQuestion(new QuestionBankItemRecord(
                "q-solid-geometry",
                "school-a",
                "admin-1",
                "MATH_VIP",
                "赵礼显数学 四棱柱线面角基础题",
                "如图，在四棱柱中求线面角，并说明垂直关系。",
                "{}",
                "medium",
                "active",
                List.of()));

        assertThat(store.searchQuestions("school-a", "teacher", "teacher-1", "学会空间向量大题讲义", 10))
                .extracting(QuestionBankItemRecord::questionId)
                .containsExactly("q-solid-geometry");
    }

    @Test
    void coneSectionSearchDoesNotExpandToSolidConeQuestions() {
        InMemoryKnowledgeQuestionBankStore store = new InMemoryKnowledgeQuestionBankStore();
        store.saveQuestion(new QuestionBankItemRecord(
                "q-solid-cone",
                "school-a",
                "admin-1",
                "MATH_VIP",
                "圆锥立体几何体积题",
                "如图，在圆锥中求线面角与体积。",
                "{}",
                "medium",
                "active",
                List.of()));
        store.saveQuestion(new QuestionBankItemRecord(
                "q-hyperbola",
                "school-a",
                "admin-1",
                "MATH_VIP",
                "双曲线标准方程与渐近线",
                "已知焦点和离心率，求双曲线标准方程。",
                "{}",
                "medium",
                "active",
                List.of()));

        assertThat(QuestionBankSearchText.candidateQueries("圆锥曲线专题讲义"))
                .contains("圆锥曲线", "双曲线", "渐近线")
                .doesNotContain("圆锥");
        assertThat(store.searchQuestions("school-a", "teacher", "teacher-1", "圆锥曲线专题讲义", 10))
                .extracting(QuestionBankItemRecord::questionId)
                .containsExactly("q-hyperbola");
    }

    @Test
    void listsRelationsOnlyWhenBothEndpointKnowledgePointsAreVisible() {
        InMemoryKnowledgeQuestionBankStore store = new InMemoryKnowledgeQuestionBankStore();
        store.saveKnowledgePoint(new KnowledgePointRecord(
                "kp-teacher-private",
                "school-a",
                "teacher-1",
                "TEACHER_PRIVATE",
                "Vector private point",
                "space vector",
                "active",
                "manual"));
        store.saveKnowledgePoint(new KnowledgePointRecord(
                "kp-vip",
                "school-a",
                "admin-1",
                "MATH_VIP",
                "Vector VIP point",
                "space vector",
                "active",
                "manual"));
        store.saveKnowledgePoint(new KnowledgePointRecord(
                "kp-other-private",
                "school-a",
                "teacher-2",
                "TEACHER_PRIVATE",
                "Other teacher private point",
                "hidden",
                "active",
                "manual"));
        store.saveKnowledgeRelation(new KnowledgeRelationRecord(
                "rel-visible",
                "school-a",
                "kp-teacher-private",
                "kp-vip",
                "PREREQUISITE_FOR",
                "Teacher-owned point connects to shared vector point.",
                "active"));
        store.saveKnowledgeRelation(new KnowledgeRelationRecord(
                "rel-hidden-target",
                "school-a",
                "kp-teacher-private",
                "kp-other-private",
                "RELATED_TO",
                "This edge would reveal another teacher private point.",
                "active"));

        assertThat(store.listKnowledgeRelations("school-a", "teacher", "teacher-1"))
                .extracting(KnowledgeRelationRecord::relationId)
                .containsExactly("rel-visible");
        assertThat(store.listKnowledgeRelations("school-a", "admin", "admin-1"))
                .extracting(KnowledgeRelationRecord::relationId)
                .containsExactly("rel-hidden-target", "rel-visible");
    }
}
