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
import com.doob.mathagent.teacher.block.TeacherDocumentBlockResponse;
import com.doob.mathagent.teacher.document.TeacherResourceDocumentResponse;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.server.ResponseStatusException;

class TeacherBlockQuestionImportControllerTest {

    @Test
    void importsTeacherResourceBlocksWithBackendSubject() {
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
                request -> new RequestSubject("school-a", "teacher", "teacher-1", "device-1"));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Subject-Id", "teacher-spoofed");

        TeacherBlockQuestionImportResponse response =
                controller.importTeacherResourceQuestions("doc-vector", request);

        assertThat(response.importedQuestionCount()).isEqualTo(1);
        assertThat(response.importedQuestions().getFirst().ownerSubjectId()).isEqualTo("teacher-1");
    }

    @Test
    void rejectsStudentImportByBackendRole() {
        KnowledgeQuestionBankController studentController = controller(
                new InMemoryTeacherResourceStore(),
                new InMemoryTeacherDocumentBlockStore(),
                new InMemoryKnowledgeQuestionBankStore(),
                request -> new RequestSubject("school-a", "student", "student-1", "device-1"));

        assertThatThrownBy(() -> studentController.importTeacherResourceQuestions(
                        "doc-vector",
                        new MockHttpServletRequest()))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("teacher or admin");
    }

    private static KnowledgeQuestionBankController controller(
            InMemoryTeacherResourceStore resourceStore,
            InMemoryTeacherDocumentBlockStore blockStore,
            InMemoryKnowledgeQuestionBankStore questionStore,
            com.doob.mathagent.infrastructure.security.RequestSubjectResolver resolver) {
        KnowledgeQuestionBankService service = new KnowledgeQuestionBankService(questionStore);
        TeacherBlockQuestionImportService importService = new TeacherBlockQuestionImportService(
                resourceStore,
                blockStore,
                service,
                questionStore);
        return new KnowledgeQuestionBankController(service, importService, resolver);
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
                "ready",
                "ready",
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

