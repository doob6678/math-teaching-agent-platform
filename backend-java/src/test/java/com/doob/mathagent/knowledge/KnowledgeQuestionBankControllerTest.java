package com.doob.mathagent.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import com.doob.mathagent.infrastructure.security.RequestSubject;
import com.doob.mathagent.knowledge.controller.KnowledgeQuestionBankController;
import com.doob.mathagent.knowledge.dto.KnowledgePointCreateRequest;
import com.doob.mathagent.knowledge.dto.QuestionBankItemCreateRequest;
import com.doob.mathagent.knowledge.service.InMemoryKnowledgeQuestionBankStore;
import com.doob.mathagent.knowledge.service.KnowledgeQuestionBankService;
import com.doob.mathagent.knowledge.service.KnowledgeRelationRecord;
import com.doob.mathagent.knowledge.service.TeacherBlockQuestionImportService;
import com.doob.mathagent.knowledge.vo.KnowledgePointResponse;
import com.doob.mathagent.knowledge.vo.KnowledgeRelationResponse;
import com.doob.mathagent.knowledge.vo.QuestionBankItemResponse;
import com.doob.mathagent.teacher.service.InMemoryTeacherDocumentBlockStore;
import com.doob.mathagent.teacher.service.InMemoryTeacherResourceStore;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.server.ResponseStatusException;

class KnowledgeQuestionBankControllerTest {

    @Test
    void teacherCreatesPrivateKnowledgePointFromBackendSubject() {
        KnowledgeQuestionBankController controller = controller(
                request -> new RequestSubject("school-a", "teacher", "teacher-1", "device-1"));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Subject-Id", "teacher-spoofed");

        KnowledgePointResponse response = controller.createKnowledgePoint(new KnowledgePointCreateRequest(
                "函数定义域",
                "函数/基础",
                "PUBLIC_TEXTBOOK",
                "manual"), request);

        assertThat(response.tenantId()).isEqualTo("school-a");
        assertThat(response.ownerSubjectId()).isEqualTo("teacher-1");
        assertThat(response.permissionScope()).isEqualTo("TEACHER_PRIVATE");
        assertThat(controller.listKnowledgePoints(request))
                .extracting(KnowledgePointResponse::knowledgePointName)
                .containsExactly("函数定义域");
    }

    @Test
    void adminCreatesSharedQuestionLinkedToKnowledgePoint() {
        KnowledgeQuestionBankController controller = controller(
                request -> new RequestSubject("school-a", "admin", "admin-1", "device-1"));
        MockHttpServletRequest request = new MockHttpServletRequest();
        KnowledgePointResponse point = controller.createKnowledgePoint(new KnowledgePointCreateRequest(
                "空间向量数量积",
                "选择性必修/空间向量",
                "MATH_VIP",
                "feishu"), request);

        QuestionBankItemResponse question = controller.createQuestion(new QuestionBankItemCreateRequest(
                "空间向量夹角",
                "已知空间向量 a,b，求夹角。",
                "{\"answer\":\"cos theta\"}",
                "medium",
                "MATH_VIP",
                List.of(point.knowledgePointId())), request);

        assertThat(question.permissionScope()).isEqualTo("MATH_VIP");
        assertThat(question.knowledgePointIds()).containsExactly(point.knowledgePointId());
        assertThat(controller.searchQuestions("向量", 10, request))
                .extracting(QuestionBankItemResponse::questionId)
                .containsExactly(question.questionId());
        assertThat(controller.searchQuestions("", 10, request))
                .extracting(QuestionBankItemResponse::questionId)
                .containsExactly(question.questionId());
    }

    @Test
    void replacesSeparatorOnlyImportedQuestionTitleForDisplay() {
        KnowledgeQuestionBankController controller = controller(
                request -> new RequestSubject("school-a", "admin", "admin-1", "device-1"));
        MockHttpServletRequest request = new MockHttpServletRequest();

        QuestionBankItemResponse question = controller.createQuestion(new QuestionBankItemCreateRequest(
                "赵礼显数学 ************************************************",
                "赵礼显数学\n************************************************\n如图，在四棱柱中求线面角，并说明垂直关系。",
                "{}",
                "medium",
                "MATH_VIP",
                List.of()), request);

        assertThat(question.questionTitle()).startsWith("如图，在四棱柱中求线面角");
    }

    @Test
    void rejectsStudentCreateByBackendRole() {
        KnowledgeQuestionBankController studentController = controller(
                request -> new RequestSubject("school-a", "student", "student-1", "device-1"));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> studentController.createKnowledgePoint(
                        new KnowledgePointCreateRequest("函数定义域", "函数/基础", "TEACHER_PRIVATE", "manual"),
                        new MockHttpServletRequest()))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("teacher or admin");

    }

    @Test
    void listsKnowledgeRelationsFromBackendSubject() {
        InMemoryKnowledgeQuestionBankStore store = new InMemoryKnowledgeQuestionBankStore();
        KnowledgeQuestionBankService service = new KnowledgeQuestionBankService(store);
        KnowledgeQuestionBankController controller = new KnowledgeQuestionBankController(
                service,
                importService(service, store),
                request -> new RequestSubject("school-a", "teacher", "teacher-1", "device-1"));
        MockHttpServletRequest request = new MockHttpServletRequest();
        KnowledgePointResponse source = controller.createKnowledgePoint(new KnowledgePointCreateRequest(
                "Space vector basis",
                "space vector",
                "TEACHER_PRIVATE",
                "manual"), new MockHttpServletRequest());
        KnowledgePointResponse target = controller.createKnowledgePoint(new KnowledgePointCreateRequest(
                "Vector angle",
                "space vector",
                "TEACHER_PRIVATE",
                "manual"), new MockHttpServletRequest());
        store.saveKnowledgeRelation(new KnowledgeRelationRecord(
                "rel-controller-visible",
                "school-a",
                source.knowledgePointId(),
                target.knowledgePointId(),
                "PREREQUISITE_FOR",
                "Basis supports angle calculation.",
                "active"));

        List<KnowledgeRelationResponse> relations = controller.listKnowledgeRelations(request);

        assertThat(relations)
                .extracting(KnowledgeRelationResponse::relationId)
                .containsExactly("rel-controller-visible");
    }

    /**
     * Builds a controller with isolated in-memory storage.
     */
    private static KnowledgeQuestionBankController controller(
            com.doob.mathagent.infrastructure.security.RequestSubjectResolver resolver) {
        InMemoryKnowledgeQuestionBankStore store = new InMemoryKnowledgeQuestionBankStore();
        KnowledgeQuestionBankService service = new KnowledgeQuestionBankService(store);
        return new KnowledgeQuestionBankController(
                service,
                importService(service, store),
                resolver);
    }

    private static TeacherBlockQuestionImportService importService(
            KnowledgeQuestionBankService service,
            InMemoryKnowledgeQuestionBankStore store) {
        return new TeacherBlockQuestionImportService(
                new InMemoryTeacherResourceStore(),
                new InMemoryTeacherDocumentBlockStore(),
                service,
                store);
    }

}
