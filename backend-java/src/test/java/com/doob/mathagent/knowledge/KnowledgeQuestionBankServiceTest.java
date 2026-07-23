package com.doob.mathagent.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import com.doob.mathagent.knowledge.dto.QuestionBankItemCreateRequest;
import com.doob.mathagent.knowledge.service.InMemoryKnowledgeQuestionBankStore;
import com.doob.mathagent.knowledge.service.KnowledgeQuestionBankService;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Verifies that question-bank search keeps concrete topic boundaries before semantic ranking. */
class KnowledgeQuestionBankServiceTest {

    @Test
    void quadraticSearchDoesNotReturnGenericFunctionOrStatisticsRows() {
        KnowledgeQuestionBankService service = new KnowledgeQuestionBankService(new InMemoryKnowledgeQuestionBankStore());
        service.createQuestion(
                "school-a", "teacher", "teacher-1",
                new QuestionBankItemCreateRequest(
                        "2022 二次函数最值",
                        "已知二次函数 f(x)=x^2-4x+3，求闭区间上的最小值。",
                        "{}", "medium", "TEACHER_PRIVATE", List.of()));
        service.createQuestion(
                "school-a", "teacher", "teacher-1",
                new QuestionBankItemCreateRequest(
                        "2022 概率统计",
                        "随机调查 100 位居民，估计患病率。",
                        "{}", "medium", "TEACHER_PRIVATE", List.of()));
        service.createQuestion(
                "school-a", "teacher", "teacher-1",
                new QuestionBankItemCreateRequest(
                        "2022 一般函数",
                        "已知函数 f(x) 的定义域，求值域。",
                        "{}", "medium", "TEACHER_PRIVATE", List.of()));

        assertThat(service.searchQuestions("school-a", "teacher", "teacher-1", "二次函数", 20))
                .extracting(item -> item.questionTitle())
                .containsExactly("2022 二次函数最值");
    }

    @Test
    void quadraticSearchKeepsOcrQuadraticTermWithoutLiteralFx() {
        KnowledgeQuestionBankService service = new KnowledgeQuestionBankService(new InMemoryKnowledgeQuestionBankStore());
        service.createQuestion("school-a", "teacher", "teacher-1", new QuestionBankItemCreateRequest(
                "2023 高考函数综合题", "已知函数 y=x²-2x+1，求其在区间上的最小值。",
                "{}", "medium", "TEACHER_PRIVATE", List.of()));

        assertThat(service.searchQuestions("school-a", "teacher", "teacher-1", "二次函数", 20))
                .extracting(item -> item.questionTitle())
                .containsExactly("2023 高考函数综合题");
    }

    @Test
    void compoundHyperbolaQueryKeepsTheSpecificTopicRow() {
        KnowledgeQuestionBankService service = new KnowledgeQuestionBankService(new InMemoryKnowledgeQuestionBankStore());
        service.createQuestion("school-a", "teacher", "teacher-1", new QuestionBankItemCreateRequest(
                "双曲线定义与参数关系基础题",
                "已知双曲线焦距为 10，且 2a=6，求 a,c,b^2。",
                "{}", "medium", "TEACHER_PRIVATE", List.of()));
        assertThat(service.searchQuestions("school-a", "teacher", "teacher-1", "双曲线定义与参数关系", 20))
                .extracting(item -> item.questionTitle())
                .containsExactly("双曲线定义与参数关系基础题");
    }
}
