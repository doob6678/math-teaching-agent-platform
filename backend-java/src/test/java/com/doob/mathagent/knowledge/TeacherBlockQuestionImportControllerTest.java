package com.doob.mathagent.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.doob.mathagent.infrastructure.security.RequestSubject;
import com.doob.mathagent.knowledge.controller.KnowledgeQuestionBankController;
import com.doob.mathagent.knowledge.service.InMemoryKnowledgeQuestionBankStore;
import com.doob.mathagent.knowledge.service.KnowledgeQuestionBankService;
import com.doob.mathagent.knowledge.service.TeacherBlockQuestionImportService;
import com.doob.mathagent.knowledge.vo.TeacherBlockQuestionImportResponse;
import com.doob.mathagent.teacher.service.InMemoryTeacherDocumentBlockStore;
import com.doob.mathagent.teacher.service.InMemoryTeacherResourceStore;
import com.doob.mathagent.teacher.vo.TeacherDocumentBlockResponse;
import com.doob.mathagent.teacher.vo.TeacherResourceDocumentResponse;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.server.ResponseStatusException;

class TeacherBlockQuestionImportControllerTest {

    @Test
    void importsTeacherResourceBlocksWithCapabilityAndBackendSubject() {
        InMemoryTeacherResourceStore resourceStore = new InMemoryTeacherResourceStore();
        InMemoryTeacherDocumentBlockStore blockStore = new InMemoryTeacherDocumentBlockStore();
        InMemoryKnowledgeQuestionBankStore questionStore = new InMemoryKnowledgeQuestionBankStore();
        resourceStore.save(document("doc-vector", "teacher-1"));
        blockStore.replaceActiveBlocks("school-a", "doc-vector", List.of(block(
                "b-1", "doc-vector", "例题 已知空间向量 a,b，求 a 与 b 的夹角。")));
        KnowledgeQuestionBankController controller = controller(
                resourceStore,
                blockStore,
                questionStore,
                request -> new RequestSubject("school-a", "teacher", "teacher-1", "device-1"),
                (token, action, path, requestHash, subject) ->
                        "token-ok".equals(token)
                                && "question-bank:import-teacher-resource".equals(action)
                                && "/api/question-bank/import/teacher-resources/doc-vector".equals(path)
                                && "hash-ok".equals(requestHash)
                                && "teacher-1".equals(subject.subjectId()));
        MockHttpServletRequest request = requestWithCapability("token-ok", "hash-ok");
        request.addHeader("X-Subject-Id", "teacher-spoofed");

        TeacherBlockQuestionImportResponse response =
                controller.importTeacherResourceQuestions("doc-vector", request);

        assertThat(response.importedQuestionCount()).isEqualTo(1);
        assertThat(response.importedQuestions().getFirst().ownerSubjectId()).isEqualTo("teacher-1");
    }

    @Test
    void rejectsImportWithoutCapabilityAndRejectsStudentSubject() {
        KnowledgeQuestionBankController protectedController = controller(
                new InMemoryTeacherResourceStore(),
                new InMemoryTeacherDocumentBlockStore(),
                new InMemoryKnowledgeQuestionBankStore(),
                request -> new RequestSubject("school-a", "teacher", "teacher-1", "device-1"),
                (token, action, path, requestHash, subject) -> false);

        assertThatThrownBy(() -> protectedController.importTeacherResourceQuestions(
                        "doc-vector",
                        requestWithCapability("bad-token", "hash-bad")))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Capability token");

        KnowledgeQuestionBankController studentController = controller(
                new InMemoryTeacherResourceStore(),
                new InMemoryTeacherDocumentBlockStore(),
                new InMemoryKnowledgeQuestionBankStore(),
                request -> new RequestSubject("school-a", "student", "student-1", "device-1"),
                (token, action, path, requestHash, subject) -> true);

        assertThatThrownBy(() -> studentController.importTeacherResourceQuestions(
                        "doc-vector",
                        requestWithCapability("token-ok", "hash-ok")))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("teacher or admin");
    }

    private static KnowledgeQuestionBankController controller(
            InMemoryTeacherResourceStore resourceStore,
            InMemoryTeacherDocumentBlockStore blockStore,
            InMemoryKnowledgeQuestionBankStore questionStore,
            com.doob.mathagent.infrastructure.security.RequestSubjectResolver resolver,
            com.doob.mathagent.knowledge.service.KnowledgeQuestionBankCapabilityVerifier verifier) {
        KnowledgeQuestionBankService service = new KnowledgeQuestionBankService(questionStore);
        TeacherBlockQuestionImportService importService = new TeacherBlockQuestionImportService(
                resourceStore,
                blockStore,
                service,
                questionStore);
        return new KnowledgeQuestionBankController(service, importService, resolver, verifier);
    }

    private static MockHttpServletRequest requestWithCapability(String token, String requestHash) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Capability-Token", token);
        request.addHeader("X-Request-Hash", requestHash);
        return request;
    }

    private static TeacherResourceDocumentResponse document(String documentId, String ownerSubjectId) {
        return new TeacherResourceDocumentResponse(
                documentId,
                "school-a",
                ownerSubjectId,
                "feishu",
                "Vector handout",
                "https://my.feishu.cn/drive/folder/" + documentId,
                null,
                "TEACHER_PRIVATE",
                "synced",
                "parsed",
                "pending",
                "waiting_rebuild",
                List.of());
    }

    private static TeacherDocumentBlockResponse block(String blockId, String documentId, String text) {
        return new TeacherDocumentBlockResponse(
                blockId,
                documentId,
                documentId + ":1",
                "text",
                1,
                "空间向量",
                "夹角",
                null,
                null,
                text,
                text.toLowerCase(),
                "[]",
                "[]",
                blockId + "-checksum",
                1.0,
                "active");
    }
}
