package com.doob.mathagent.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.doob.mathagent.knowledge.service.InMemoryKnowledgeQuestionBankStore;
import com.doob.mathagent.knowledge.service.KnowledgeQuestionBankService;
import com.doob.mathagent.knowledge.service.TeacherBlockQuestionImportService;
import com.doob.mathagent.knowledge.vo.QuestionBankItemResponse;
import com.doob.mathagent.knowledge.vo.TeacherBlockQuestionImportResponse;
import com.doob.mathagent.teacher.service.InMemoryTeacherDocumentBlockStore;
import com.doob.mathagent.teacher.service.InMemoryTeacherResourceStore;
import com.doob.mathagent.teacher.vo.TeacherDocumentBlockResponse;
import com.doob.mathagent.teacher.vo.TeacherResourceDocumentResponse;
import java.util.List;
import org.junit.jupiter.api.Test;

class TeacherBlockQuestionImportServiceTest {

    @Test
    void importsRealQuestionBlocksAndLinksChapterKnowledgePoint() {
        InMemoryTeacherResourceStore resourceStore = new InMemoryTeacherResourceStore();
        InMemoryTeacherDocumentBlockStore blockStore = new InMemoryTeacherDocumentBlockStore();
        InMemoryKnowledgeQuestionBankStore questionStore = new InMemoryKnowledgeQuestionBankStore();
        resourceStore.save(document("doc-vector", "teacher-1", "TEACHER_PRIVATE", "Vector handout"));
        blockStore.replaceActiveBlocks("school-a", "doc-vector", List.of(
                block("b-1", "doc-vector", 1, "空间向量", "数量积", "例1 已知空间向量 a=(1,2,2), b=(2,0,1)，求 a·b 的值。"),
                block("b-2", "doc-vector", 2, "空间向量", "数量积", "本节主要介绍数量积的定义和几何意义。")));
        TeacherBlockQuestionImportService service = service(resourceStore, blockStore, questionStore);

        TeacherBlockQuestionImportResponse response =
                service.importFromTeacherResource("school-a", "teacher", "teacher-1", "doc-vector");

        assertThat(response.importedQuestionCount()).isEqualTo(1);
        assertThat(response.skippedBlockCount()).isEqualTo(1);
        assertThat(response.linkedKnowledgePointCount()).isEqualTo(1);
        assertThat(response.importedQuestions()).hasSize(1);
        QuestionBankItemResponse question = response.importedQuestions().getFirst();
        assertThat(question.questionText()).contains("例1 已知空间向量");
        assertThat(question.answerJson()).isEqualTo("{}");
        assertThat(question.sourceResourceDocumentId()).isEqualTo("doc-vector");
        assertThat(question.sourceBlockId()).isEqualTo("b-1");
        assertThat(question.sourceChecksum()).isEqualTo("b-1-checksum");
        assertThat(service.searchQuestions("school-a", "teacher", "teacher-1", "a·b", 10))
                .extracting(QuestionBankItemResponse::questionId)
                .containsExactly(question.questionId());
    }

    @Test
    void skipsAlreadyImportedBlocksBySourceBlockAndChecksum() {
        InMemoryTeacherResourceStore resourceStore = new InMemoryTeacherResourceStore();
        InMemoryTeacherDocumentBlockStore blockStore = new InMemoryTeacherDocumentBlockStore();
        InMemoryKnowledgeQuestionBankStore questionStore = new InMemoryKnowledgeQuestionBankStore();
        resourceStore.save(document("doc-proof", "teacher-1", "TEACHER_PRIVATE", "Proof handout"));
        blockStore.replaceActiveBlocks("school-a", "doc-proof", List.of(block(
                "b-proof", "doc-proof", 1, "立体几何", "平行证明", "证明：若直线 l 平行平面 alpha，则 l 与平面内某直线平行。")));
        TeacherBlockQuestionImportService service = service(resourceStore, blockStore, questionStore);

        TeacherBlockQuestionImportResponse first =
                service.importFromTeacherResource("school-a", "teacher", "teacher-1", "doc-proof");
        TeacherBlockQuestionImportResponse second =
                service.importFromTeacherResource("school-a", "teacher", "teacher-1", "doc-proof");

        assertThat(first.importedQuestionCount()).isEqualTo(1);
        assertThat(second.importedQuestionCount()).isZero();
        assertThat(second.duplicateBlockCount()).isEqualTo(1);
        assertThat(service.searchQuestions("school-a", "teacher", "teacher-1", "证明", 10))
                .extracting(QuestionBankItemResponse::sourceBlockId)
                .containsExactly("b-proof");
    }

    @Test
    void archivesStaleImportedQuestionWhenSameBlockGetsNewChecksum() {
        InMemoryTeacherResourceStore resourceStore = new InMemoryTeacherResourceStore();
        InMemoryTeacherDocumentBlockStore blockStore = new InMemoryTeacherDocumentBlockStore();
        InMemoryKnowledgeQuestionBankStore questionStore = new InMemoryKnowledgeQuestionBankStore();
        resourceStore.save(document("doc-refresh", "teacher-1", "TEACHER_PRIVATE", "Refreshable proof handout"));
        TeacherBlockQuestionImportService service = service(resourceStore, blockStore, questionStore);
        blockStore.replaceActiveBlocks("school-a", "doc-refresh", List.of(blockWithChecksum(
                "b-refresh",
                "doc-refresh",
                1,
                "立体几何",
                "平行证明",
                "证明：若直线 l 平行平面 alpha，则 l 与平面内某直线平行。",
                "checksum-v1")));

        TeacherBlockQuestionImportResponse first =
                service.importFromTeacherResource("school-a", "teacher", "teacher-1", "doc-refresh");
        blockStore.replaceActiveBlocks("school-a", "doc-refresh", List.of(blockWithChecksum(
                "b-refresh",
                "doc-refresh",
                1,
                "立体几何",
                "平行证明",
                "证明：若直线 l 平行平面 alpha，则可通过反证法补充说明存在平行线。",
                "checksum-v2")));
        TeacherBlockQuestionImportResponse second =
                service.importFromTeacherResource("school-a", "teacher", "teacher-1", "doc-refresh");

        assertThat(first.importedQuestionCount()).isEqualTo(1);
        assertThat(second.importedQuestionCount()).isEqualTo(1);
        assertThat(second.duplicateBlockCount()).isZero();
        assertThat(service.searchQuestions("school-a", "teacher", "teacher-1", "反证法", 10))
                .extracting(QuestionBankItemResponse::sourceChecksum)
                .containsExactly("checksum-v2");
    }

    @Test
    void doesNotImportAnotherTeacherPrivateDocument() {
        InMemoryTeacherResourceStore resourceStore = new InMemoryTeacherResourceStore();
        InMemoryTeacherDocumentBlockStore blockStore = new InMemoryTeacherDocumentBlockStore();
        InMemoryKnowledgeQuestionBankStore questionStore = new InMemoryKnowledgeQuestionBankStore();
        resourceStore.save(document("doc-other", "teacher-2", "TEACHER_PRIVATE", "Other private handout"));
        blockStore.replaceActiveBlocks("school-a", "doc-other", List.of(block(
                "b-other", "doc-other", 1, "函数", "定义域", "已知函数 f(x)=sqrt(x-1)，求定义域。")));
        TeacherBlockQuestionImportService service = service(resourceStore, blockStore, questionStore);

        assertThatThrownBy(() -> service.importFromTeacherResource("school-a", "teacher", "teacher-1", "doc-other"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not visible");
        assertThat(service.searchQuestions("school-a", "admin", "admin-1", "定义域", 10)).isEmpty();
    }

    @Test
    void rejectsStudentImport() {
        TeacherBlockQuestionImportService service = service(
                new InMemoryTeacherResourceStore(),
                new InMemoryTeacherDocumentBlockStore(),
                new InMemoryKnowledgeQuestionBankStore());

        assertThatThrownBy(() -> service.importFromTeacherResource("school-a", "student", "student-1", "doc-1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("teacher or admin");
    }

    private static TeacherBlockQuestionImportService service(
            InMemoryTeacherResourceStore resourceStore,
            InMemoryTeacherDocumentBlockStore blockStore,
            InMemoryKnowledgeQuestionBankStore questionStore) {
        return new TeacherBlockQuestionImportService(
                resourceStore,
                blockStore,
                new KnowledgeQuestionBankService(questionStore),
                questionStore);
    }

    private static TeacherResourceDocumentResponse document(
            String documentId,
            String ownerSubjectId,
            String permissionScope,
            String title) {
        return new TeacherResourceDocumentResponse(
                documentId,
                "school-a",
                ownerSubjectId,
                "feishu",
                title,
                "https://my.feishu.cn/drive/folder/" + documentId,
                null,
                permissionScope,
                "synced",
                "parsed",
                "pending",
                "waiting_rebuild",
                List.of());
    }

    private static TeacherDocumentBlockResponse block(
            String blockId,
            String documentId,
            int blockOrder,
            String chapter,
            String section,
            String text) {
        return blockWithChecksum(blockId, documentId, blockOrder, chapter, section, text, blockId + "-checksum");
    }

    private static TeacherDocumentBlockResponse blockWithChecksum(
            String blockId,
            String documentId,
            int blockOrder,
            String chapter,
            String section,
            String text,
            String checksum) {
        return new TeacherDocumentBlockResponse(
                blockId,
                documentId,
                documentId + ":" + blockOrder,
                "text",
                blockOrder,
                chapter,
                section,
                null,
                null,
                text,
                text.toLowerCase(),
                "[]",
                "[]",
                checksum,
                1.0,
                "active");
    }
}
