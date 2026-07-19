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
import com.doob.mathagent.teacher.block.TeacherDocumentBlockResponse;
import com.doob.mathagent.teacher.document.TeacherResourceDocumentResponse;
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
        assertThat(question.questionTitle()).contains("数量积");
        assertThat(question.answerJson()).isEqualTo("{}");
        assertThat(question.sourceResourceDocumentId()).isEqualTo("doc-vector");
        assertThat(question.sourceBlockId()).isEqualTo("b-1");
        assertThat(question.sourceChecksum()).isEqualTo("b-1-checksum");
        assertThat(service.searchQuestions("school-a", "teacher", "teacher-1", "a·b", 10))
                .extracting(QuestionBankItemResponse::questionId)
                .containsExactly(question.questionId());
    }

    @Test
    void importsOnlyAtomicPromptsAndSeparatesVerifiedSourceAnswer() {
        InMemoryTeacherResourceStore resourceStore = new InMemoryTeacherResourceStore();
        InMemoryTeacherDocumentBlockStore blockStore = new InMemoryTeacherDocumentBlockStore();
        InMemoryKnowledgeQuestionBankStore questionStore = new InMemoryKnowledgeQuestionBankStore();
        resourceStore.save(document("doc-coloring", "teacher-1", "TEACHER_PRIVATE", "涂色问题"));
        blockStore.replaceActiveBlocks("school-a", "doc-coloring", List.of(
                block("b-heading", "doc-coloring", 1, "计数原理", "涂色问题", "七、涂色问题（长时间不考）"),
                block("b-guide", "doc-coloring", 2, "计数原理", "涂色问题", "学习分类思路，作业里可能会看到涂色问题。"),
                block("b-current", "doc-coloring", 3, "计数原理", "涂色问题", "2013年涂色问题\n如图，一个地区分为5个行政区域，现给地图着色，要求相邻区域不得使用同一颜色，现有四种颜色可供选择，则不同的着色方法共有____种(以数字作答)。\n---\n答案：48。先给中心区域着色，再依次分类讨论。"),
                block("b-variation", "doc-coloring", 4, "计数原理", "涂色问题", "变式：一个地区分为5个行政区域，现有五种颜色可供选择，要求相邻区域颜色不同，则不同的着色方法共有多少种？\n答案：540。")));
        TeacherBlockQuestionImportService service = service(resourceStore, blockStore, questionStore);

        TeacherBlockQuestionImportResponse response =
                service.importFromTeacherResource("school-a", "teacher", "teacher-1", "doc-coloring");

        assertThat(response.importedQuestionCount()).isEqualTo(2);
        assertThat(response.skippedBlockCount()).isEqualTo(2);
        assertThat(response.importedQuestions())
                .extracting(QuestionBankItemResponse::questionText)
                .allSatisfy(questionText -> {
                    assertThat(questionText).doesNotContain("答案：");
                    assertThat(questionText).doesNotContain("---");
                    assertThat(questionText).containsAnyOf("共有", "多少种");
                });
        QuestionBankItemResponse current = response.importedQuestions().stream()
                .filter(question -> question.sourceBlockId().equals("b-current"))
                .findFirst()
                .orElseThrow();
        assertThat(current.answerJson()).contains("48").contains("先给中心区域着色");
    }

    @Test
    void rejectsAtomicLookingQuestionWhenOcrLostAMathematicalRelation() {
        InMemoryTeacherResourceStore resourceStore = new InMemoryTeacherResourceStore();
        InMemoryTeacherDocumentBlockStore blockStore = new InMemoryTeacherDocumentBlockStore();
        InMemoryKnowledgeQuestionBankStore questionStore = new InMemoryKnowledgeQuestionBankStore();
        resourceStore.save(document("doc-ocr-relation", "teacher-1", "TEACHER_PRIVATE", "空间向量"));
        blockStore.replaceActiveBlocks("school-a", "doc-ocr-relation", List.of(block(
                "b-ocr-relation", "doc-ocr-relation", 1, "立体几何", "线面关系",
                "如图，在三棱柱 ABC-A1B1C1 中，CC1 □ 平面 ABC，求二面角。")));

        TeacherBlockQuestionImportResponse response = service(resourceStore, blockStore, questionStore)
                .importFromTeacherResource("school-a", "teacher", "teacher-1", "doc-ocr-relation");

        assertThat(response.importedQuestionCount()).isZero();
        assertThat(response.skippedBlockCount()).isEqualTo(1);
        assertThat(service(resourceStore, blockStore, questionStore)
                .searchQuestions("school-a", "teacher", "teacher-1", "二面角", 10)).isEmpty();
    }

    @Test
    void replacesLegacySameChecksumImportWhenTheNewParserSeparatesStemAndAnswer() {
        InMemoryTeacherResourceStore resourceStore = new InMemoryTeacherResourceStore();
        InMemoryTeacherDocumentBlockStore blockStore = new InMemoryTeacherDocumentBlockStore();
        InMemoryKnowledgeQuestionBankStore questionStore = new InMemoryKnowledgeQuestionBankStore();
        resourceStore.save(document("doc-reimport", "teacher-1", "TEACHER_PRIVATE", "涂色问题"));
        String sourceText = "例题：五个相邻区域，现有四种颜色，求不同着色方法数。\n答案：48。";
        blockStore.replaceActiveBlocks("school-a", "doc-reimport", List.of(
                block("b-color", "doc-reimport", 1, "计数原理", "涂色问题", sourceText)));
        KnowledgeQuestionBankService questionBankService = new KnowledgeQuestionBankService(questionStore);
        questionBankService.createImportedQuestion(
                "school-a", "teacher", "teacher-1", "TEACHER_PRIVATE", "旧版导入", sourceText,
                "medium", "doc-reimport", "b-color", "b-color-checksum", List.of());
        TeacherBlockQuestionImportService service = new TeacherBlockQuestionImportService(
                resourceStore, blockStore, questionBankService, questionStore);

        TeacherBlockQuestionImportResponse response =
                service.importFromTeacherResource("school-a", "teacher", "teacher-1", "doc-reimport");

        assertThat(response.importedQuestionCount()).isEqualTo(1);
        assertThat(response.duplicateBlockCount()).isZero();
        QuestionBankItemResponse refreshed = response.importedQuestions().getFirst();
        assertThat(refreshed.questionText()).doesNotContain("答案：");
        assertThat(refreshed.answerJson()).contains("48");
        assertThat(service.searchQuestions("school-a", "teacher", "teacher-1", "颜色", 10))
                .extracting(QuestionBankItemResponse::questionText)
                .containsExactly(refreshed.questionText());
    }

    @Test
    void splitsAQuestionAtItsFirstCompletedPromptWhenSourceNotesHaveNoAnswerHeading() {
        InMemoryTeacherResourceStore resourceStore = new InMemoryTeacherResourceStore();
        InMemoryTeacherDocumentBlockStore blockStore = new InMemoryTeacherDocumentBlockStore();
        InMemoryKnowledgeQuestionBankStore questionStore = new InMemoryKnowledgeQuestionBankStore();
        resourceStore.save(document("doc-implicit-split", "teacher-1", "TEACHER_PRIVATE", "涂色问题"));
        blockStore.replaceActiveBlocks("school-a", "doc-implicit-split", List.of(block(
                "b-implicit", "doc-implicit-split", 1, "计数原理", "涂色问题",
                "如图，五个行政区域相邻不得同色，现有四种颜色可选，则不同着色方法共有多少种。首先确定中心区域，再分别讨论三色和四色。")));
        TeacherBlockQuestionImportService service = service(resourceStore, blockStore, questionStore);

        TeacherBlockQuestionImportResponse response =
                service.importFromTeacherResource("school-a", "teacher", "teacher-1", "doc-implicit-split");

        QuestionBankItemResponse question = response.importedQuestions().getFirst();
        assertThat(question.questionText()).doesNotContain("首先确定中心区域");
        assertThat(question.answerJson()).contains("首先确定中心区域");
    }

    @Test
    void splitsOneExtractedExamPageIntoIndependentlyTraceableNumberedQuestions() {
        InMemoryTeacherResourceStore resourceStore = new InMemoryTeacherResourceStore();
        InMemoryTeacherDocumentBlockStore blockStore = new InMemoryTeacherDocumentBlockStore();
        InMemoryKnowledgeQuestionBankStore questionStore = new InMemoryKnowledgeQuestionBankStore();
        resourceStore.save(document("doc-exam-page", "teacher-1", "TEACHER_PRIVATE", "2024 真题"));
        blockStore.replaceActiveBlocks("school-a", "doc-exam-page", List.of(block(
                "b-exam-page", "doc-exam-page", 1, "2024 真题", "选择题",
                "1  已知集合 A={x|x<3}，则 A∩B=（  ）\n2. 若 z=1+i，则 |z|=（  ）\n3. 已知向量 a=(1,0)，求 |a|。")));
        TeacherBlockQuestionImportService service = service(resourceStore, blockStore, questionStore);

        TeacherBlockQuestionImportResponse response =
                service.importFromTeacherResource("school-a", "teacher", "teacher-1", "doc-exam-page");

        assertThat(response.importedQuestionCount()).isEqualTo(3);
        assertThat(response.importedQuestions())
                .extracting(QuestionBankItemResponse::sourceBlockId)
                .containsExactly("b-exam-page#q1", "b-exam-page#q2", "b-exam-page#q3");
    }

    @Test
    void prefersHighConfidencePageTranscriptionWhenItAlreadyContainsAQualifiedQuestionSet() {
        InMemoryTeacherResourceStore resourceStore = new InMemoryTeacherResourceStore();
        InMemoryTeacherDocumentBlockStore blockStore = new InMemoryTeacherDocumentBlockStore();
        InMemoryKnowledgeQuestionBankStore questionStore = new InMemoryKnowledgeQuestionBankStore();
        resourceStore.save(document("doc-page-authority", "teacher-1", "TEACHER_PRIVATE", "2024 真题"));
        List<TeacherDocumentBlockResponse> blocks = new java.util.ArrayList<>();
        // This paragraph is deliberately plausible-looking but lost its formula. It must not enter a printable bank
        // when the same document already has a complete page-transcription source of truth.
        blocks.add(block("lost-formula", "doc-page-authority", 1, "2024 真题", "选择题", "若 ，则 ，"));
        for (int index = 1; index <= 10; index += 1) {
            blocks.add(pageBlock(
                    "page-" + index,
                    "doc-page-authority",
                    index + 1,
                    index,
                    index + "．已知函数 f(x)=x+" + index + "，求 f(0) 的值。（ ）"));
        }
        blockStore.replaceActiveBlocks("school-a", "doc-page-authority", blocks);

        TeacherBlockQuestionImportResponse response = service(resourceStore, blockStore, questionStore)
                .importFromTeacherResource("school-a", "teacher", "teacher-1", "doc-page-authority");

        assertThat(response.importedQuestionCount()).isEqualTo(10);
        assertThat(response.importedQuestions())
                .extracting(QuestionBankItemResponse::sourceBlockId)
                .allSatisfy(sourceBlockId -> assertThat(sourceBlockId).startsWith("page-"));
        assertThat(response.importedQuestions())
                .extracting(QuestionBankItemResponse::questionText)
                .noneMatch(questionText -> questionText.equals("若 ，则 ，"));
    }

    @Test
    void joinsAContinuationPageWithTheImmediatelyPrecedingNumberedStem() {
        InMemoryTeacherResourceStore resourceStore = new InMemoryTeacherResourceStore();
        InMemoryTeacherDocumentBlockStore blockStore = new InMemoryTeacherDocumentBlockStore();
        InMemoryKnowledgeQuestionBankStore questionStore = new InMemoryKnowledgeQuestionBankStore();
        resourceStore.save(document("doc-page-continuation", "teacher-1", "TEACHER_PRIVATE", "2024 真题"));
        List<TeacherDocumentBlockResponse> blocks = new java.util.ArrayList<>();
        for (int index = 1; index <= 10; index += 1) {
            blocks.add(pageBlock("page-" + index, "doc-page-continuation", index, index,
                    index + "．已知函数 f(x)=x+" + index + "，求 f(0) 的值。"));
        }
        blocks.add(pageBlock("page-19-head", "doc-page-continuation", 11, 11,
                "19. 已知双曲线 C：x²-y²=9（m0），点P₁(5,4)在C上，按如下方式递推构造点。"));
        blocks.add(pageBlock("page-19-asks", "doc-page-continuation", 12, 12,
                "记P_n的坐标为(x_n,y_n)。（1）若k=1/2，求x_2,y_2；（2）证明数列{x_n-y_n}是等比数列。"));
        blockStore.replaceActiveBlocks("school-a", "doc-page-continuation", blocks);

        TeacherBlockQuestionImportResponse response = service(resourceStore, blockStore, questionStore)
                .importFromTeacherResource("school-a", "teacher", "teacher-1", "doc-page-continuation");

        assertThat(response.importedQuestions())
                .extracting(QuestionBankItemResponse::questionText)
                .anySatisfy(questionText -> assertThat(questionText)
                        .contains("19. 已知双曲线", "求x_2,y_2", "证明数列"));
    }

    @Test
    void neverImportsAnAnalysisPageAsAnAtomicQuestionEvenWhenItContainsConditionWords() {
        InMemoryTeacherResourceStore resourceStore = new InMemoryTeacherResourceStore();
        InMemoryTeacherDocumentBlockStore blockStore = new InMemoryTeacherDocumentBlockStore();
        InMemoryKnowledgeQuestionBankStore questionStore = new InMemoryKnowledgeQuestionBankStore();
        resourceStore.save(document("doc-page-analysis", "teacher-1", "TEACHER_PRIVATE", "2024 真题"));
        blockStore.replaceActiveBlocks("school-a", "doc-page-analysis", List.of(pageBlock(
                "analysis-page", "doc-page-analysis", 1, 9,
                "【解析】 若函数 f(x) 在区间内有极值，则先求导并讨论符号。")));

        TeacherBlockQuestionImportResponse response = service(resourceStore, blockStore, questionStore)
                .importFromTeacherResource("school-a", "teacher", "teacher-1", "doc-page-analysis");

        assertThat(response.importedQuestionCount()).isZero();
        assertThat(response.skippedBlockCount()).isEqualTo(1);
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
    void rejectsParsedResourceUntilItsOwnerScopedVectorIndexIsReady() {
        InMemoryTeacherResourceStore resourceStore = new InMemoryTeacherResourceStore();
        InMemoryTeacherDocumentBlockStore blockStore = new InMemoryTeacherDocumentBlockStore();
        InMemoryKnowledgeQuestionBankStore questionStore = new InMemoryKnowledgeQuestionBankStore();
        resourceStore.save(new TeacherResourceDocumentResponse(
                "doc-awaiting-index",
                "school-a",
                "teacher-1",
                "feishu",
                "未索引涂色问题",
                "https://wiki.feishu.cn/docx/coloring-problem",
                null,
                "TEACHER_PRIVATE",
                "synced",
                "parsed",
                "pending",
                "waiting_rebuild",
                "md",
                List.of()));
        blockStore.replaceActiveBlocks("school-a", "doc-awaiting-index", List.of(block(
                "b-awaiting-index",
                "doc-awaiting-index",
                1,
                "计数原理",
                "涂色问题",
                "例1 已知五个相邻区域，现有四种颜色可选，求不同着色方法数。")));
        TeacherBlockQuestionImportService service = service(resourceStore, blockStore, questionStore);

        assertThatThrownBy(() -> service.importFromTeacherResource(
                "school-a", "teacher", "teacher-1", "doc-awaiting-index"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not ready for import");
        assertThat(service.searchQuestions("school-a", "teacher", "teacher-1", "涂色", 10)).isEmpty();
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
                "ready",
                "ready",
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

    /** Builds a page-backed block exactly like the DOCX page transcription path, including high confidence. */
    private static TeacherDocumentBlockResponse pageBlock(
            String blockId,
            String documentId,
            int blockOrder,
            int pageNo,
            String text) {
        return new TeacherDocumentBlockResponse(
                blockId,
                documentId,
                documentId + ":page:" + pageNo,
                "page_text",
                blockOrder,
                "2024 真题",
                "选择题",
                pageNo,
                Integer.toString(pageNo),
                "2024.docx",
                "question",
                text,
                text.toLowerCase(),
                "[]",
                "[]",
                "[]",
                "[]",
                blockId + "-checksum",
                0.98,
                "active");
    }
}
