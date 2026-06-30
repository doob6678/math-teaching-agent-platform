package com.doob.mathagent.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import com.doob.mathagent.knowledge.service.InMemoryKnowledgeQuestionBankStore;
import com.doob.mathagent.knowledge.service.KnowledgePointRecord;
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
        assertThat(store.searchQuestions("school-a", "admin", "admin-1", "空间", 10))
                .extracting(QuestionBankItemRecord::questionId)
                .containsExactly("q-vip");
    }
}
