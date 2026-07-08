package com.doob.mathagent.teacher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.doob.mathagent.knowledge.service.InMemoryKnowledgeQuestionBankStore;
import com.doob.mathagent.knowledge.service.KnowledgePointRecord;
import com.doob.mathagent.knowledge.service.KnowledgeRelationRecord;
import com.doob.mathagent.teacher.service.InMemoryTeacherDocumentBlockStore;
import com.doob.mathagent.teacher.service.InMemoryTeacherResourceStore;
import com.doob.mathagent.teacher.service.RecentTeacherResourceBlockSearchAuditStore;
import com.doob.mathagent.teacher.service.TeacherResourceBlockSearchAuditEvent;
import com.doob.mathagent.teacher.service.TeacherResourceBlockSearchService;
import com.doob.mathagent.teacher.service.TeacherResourceSearchFilter;
import com.doob.mathagent.teacher.vo.TeacherDocumentBlockResponse;
import com.doob.mathagent.teacher.vo.TeacherResourceBlockSearchResponse;
import com.doob.mathagent.teacher.vo.TeacherResourceDocumentResponse;
import com.doob.mathagent.vector.service.TestVectorIndexService;
import java.util.List;
import org.junit.jupiter.api.Test;

class TeacherResourceBlockSearchServiceTest {

    @Test
    void teacherSearchesOwnPrivateBlocksAndDoesNotSeeAnotherTeacherPrivateBlocks() {
        InMemoryTeacherResourceStore resourceStore = new InMemoryTeacherResourceStore();
        InMemoryTeacherDocumentBlockStore blockStore = new InMemoryTeacherDocumentBlockStore();
        resourceStore.save(document("doc-own", "teacher-1", "TEACHER_PRIVATE", "Own vector notes"));
        resourceStore.save(document("doc-other", "teacher-2", "TEACHER_PRIVATE", "Other private notes"));
        blockStore.replaceActiveBlocks("school-a", "doc-own", List.of(block(
                "b-own",
                "doc-own",
                1,
                "Space vector dot product method belongs to the teacher private handout.")));
        blockStore.replaceActiveBlocks("school-a", "doc-other", List.of(block(
                "b-other",
                "doc-other",
                1,
                "Space vector dot product method from another teacher private handout.")));
        TeacherResourceBlockSearchService service = com.doob.mathagent.teacher.TeacherResourceBlockSearchServiceFixture.service(resourceStore, blockStore);

        TeacherResourceBlockSearchResponse response = service.search(
                "school-a",
                "teacher",
                "teacher-1",
                "dot product",
                10);

        assertThat(response.hitCount()).isEqualTo(1);
        assertThat(response.hits()).extracting(TeacherResourceBlockSearchResponse.Hit::blockId)
                .containsExactly("b-own");
        assertThat(response.hits().getFirst().permissionScope()).isEqualTo("TEACHER_PRIVATE");
    }

    @Test
    void teacherCanSearchTenantSharedBlocksWithoutSeeingOtherPrivateBlocks() {
        InMemoryTeacherResourceStore resourceStore = new InMemoryTeacherResourceStore();
        InMemoryTeacherDocumentBlockStore blockStore = new InMemoryTeacherDocumentBlockStore();
        resourceStore.save(document("doc-shared", "admin-1", "MATH_VIP", "Shared vector bank"));
        resourceStore.save(document("doc-other-private", "teacher-2", "TEACHER_PRIVATE", "Other private bank"));
        blockStore.replaceActiveBlocks("school-a", "doc-shared", List.of(block(
                "b-shared",
                "doc-shared",
                1,
                "A shared Math VIP vector method can be reused by teachers.")));
        blockStore.replaceActiveBlocks("school-a", "doc-other-private", List.of(block(
                "b-other-private",
                "doc-other-private",
                1,
                "A private vector method must not leak to another teacher.")));
        TeacherResourceBlockSearchService service = com.doob.mathagent.teacher.TeacherResourceBlockSearchServiceFixture.service(resourceStore, blockStore);

        TeacherResourceBlockSearchResponse response = service.search(
                "school-a",
                "teacher",
                "teacher-1",
                "vector method",
                10);

        assertThat(response.hits()).extracting(TeacherResourceBlockSearchResponse.Hit::blockId)
                .containsExactly("b-shared");
        assertThat(response.hits().getFirst().permissionScope()).isEqualTo("MATH_VIP");
    }

    @Test
    void adminSearchesTenantBlocksAcrossOwners() {
        InMemoryTeacherResourceStore resourceStore = new InMemoryTeacherResourceStore();
        InMemoryTeacherDocumentBlockStore blockStore = new InMemoryTeacherDocumentBlockStore();
        resourceStore.save(document("doc-teacher", "teacher-1", "TEACHER_PRIVATE", "Teacher private bank"));
        resourceStore.save(document("doc-admin", "admin-1", "PUBLIC_TEXTBOOK", "Admin public bank"));
        blockStore.replaceActiveBlocks("school-a", "doc-teacher", List.of(block(
                "b-teacher",
                "doc-teacher",
                1,
                "Teacher vector examples for inspection.")));
        blockStore.replaceActiveBlocks("school-a", "doc-admin", List.of(block(
                "b-admin",
                "doc-admin",
                2,
                "Public vector examples for inspection.")));
        TeacherResourceBlockSearchService service = com.doob.mathagent.teacher.TeacherResourceBlockSearchServiceFixture.service(resourceStore, blockStore);

        TeacherResourceBlockSearchResponse response = service.search(
                "school-a",
                "admin",
                "admin-1",
                "vector examples",
                10);

        assertThat(response.hits()).extracting(TeacherResourceBlockSearchResponse.Hit::blockId)
                .containsExactly("b-admin", "b-teacher");
    }

    @Test
    void recordsSearchAuditEventWithoutLocalPathsOrSecrets() {
        InMemoryTeacherResourceStore resourceStore = new InMemoryTeacherResourceStore();
        InMemoryTeacherDocumentBlockStore blockStore = new InMemoryTeacherDocumentBlockStore();
        RecentTeacherResourceBlockSearchAuditStore auditStore = new RecentTeacherResourceBlockSearchAuditStore(5);
        resourceStore.save(document("doc-own", "teacher-1", "TEACHER_PRIVATE", "Own vector notes"));
        blockStore.replaceActiveBlocks("school-a", "doc-own", List.of(block(
                "b-own",
                "doc-own",
                1,
                "Space vector dot product method belongs to the teacher private handout.")));
        TeacherResourceBlockSearchService service = new TeacherResourceBlockSearchService(
                resourceStore,
                blockStore,
                auditStore,
                TestVectorIndexService.successful(resourceStore, blockStore));

        TeacherResourceBlockSearchResponse response = service.search(
                "school-a",
                "teacher",
                "teacher-1",
                "dot product",
                10,
                "/api/mcp/tools/search_teacher_resource_evidence/call");

        TeacherResourceBlockSearchAuditEvent event = auditStore.findByQueryId(response.queryId()).orElseThrow();
        assertThat(event.tenantId()).isEqualTo("school-a");
        assertThat(event.subjectType()).isEqualTo("teacher");
        assertThat(event.subjectId()).isEqualTo("teacher-1");
        assertThat(event.endpoint()).isEqualTo("/api/mcp/tools/search_teacher_resource_evidence/call");
        assertThat(event.hits()).extracting(TeacherResourceBlockSearchAuditEvent.Hit::blockId)
                .containsExactly("b-own");
        assertThat(event.toString()).doesNotContain("C:/math");
        assertThat(event.toString()).doesNotContain("mcp_secret");
    }

    @Test
    void defaultsToTwoStageRetrievalAndReranksAnalysisBlockInsideCorrectDocument() {
        InMemoryTeacherResourceStore resourceStore = new InMemoryTeacherResourceStore();
        InMemoryTeacherDocumentBlockStore blockStore = new InMemoryTeacherDocumentBlockStore();
        resourceStore.save(document("doc-qa", "teacher-1", "MATH_VIP", "Vector angle bundle"));
        resourceStore.save(document("doc-lesson", "teacher-1", "MATH_VIP", "Vector angle lesson"));
        blockStore.replaceActiveBlocks("school-a", "doc-qa", List.of(
                detailedBlock(
                        "b-question",
                        "doc-qa",
                        1,
                        "qq_bundle/question.md",
                        "question",
                        "Space vector",
                        "Angle",
                        "Question: vector angle prompt asks for the included angle."),
                detailedBlock(
                        "b-analysis",
                        "doc-qa",
                        2,
                        "qq_bundle/analysis.md",
                        "analysis",
                        "Space vector",
                        "Angle analysis",
                        "Analysis: use the dot product and norm formula to solve the vector angle."),
                detailedBlock(
                        "b-lesson",
                        "doc-qa",
                        3,
                        "qq_bundle/lesson.md",
                        "lesson",
                        "Space vector",
                        "Method summary",
                        "Lesson note: summarize the vector angle method.")));
        blockStore.replaceActiveBlocks("school-a", "doc-lesson", List.of(detailedBlock(
                "b-generic",
                "doc-lesson",
                1,
                "handout/lesson.md",
                "lesson",
                "Space vector",
                "Angle",
                "Lesson note: vector angle method overview with no worked analysis.")));
        TeacherResourceBlockSearchService service = TeacherResourceBlockSearchServiceFixture.service(resourceStore, blockStore);

        TeacherResourceBlockSearchResponse response = service.search(
                "school-a",
                "teacher",
                "teacher-1",
                "vector angle analysis",
                5);

        assertThat(response.retrievalMode()).isEqualTo("two_stage_doc_block");
        assertThat(response.hits()).isNotEmpty();
        assertThat(response.hits().getFirst().documentId()).isEqualTo("doc-qa");
        assertThat(response.hits().getFirst().blockId()).isEqualTo("b-analysis");
        assertThat(response.hits().getFirst().blockRole()).isEqualTo("analysis");
        assertThat(response.hits().getFirst().sourcePath()).contains("analysis.md");
        assertThat(response.hits().getFirst().evidenceBlockIds())
                .contains("b-question", "b-analysis", "b-lesson");
        assertThat(response.hits().getFirst().evidenceText())
                .contains("included angle")
                .contains("dot product")
                .contains("method");
    }

    @Test
    void chineseQueryWithoutWhitespaceStillProducesUsefulLexicalTermsForDocumentRecall() {
        InMemoryTeacherResourceStore resourceStore = new InMemoryTeacherResourceStore();
        InMemoryTeacherDocumentBlockStore blockStore = new InMemoryTeacherDocumentBlockStore();
        resourceStore.save(new TeacherResourceDocumentResponse(
                "doc-textbook",
                "school-a",
                "admin-1",
                "local_path",
                "runtime-public-textbook-derivative",
                null,
                "C:/workspace/runtime-authored/01-public-textbook-derivative",
                "PUBLIC_TEXTBOOK",
                "synced",
                "parsed",
                "ready",
                "ready",
                List.of()));
        resourceStore.save(new TeacherResourceDocumentResponse(
                "doc-feishu",
                "school-a",
                "teacher-1",
                "local_path",
                "runtime-feishu-method-probability",
                null,
                "C:/workspace/runtime-authored/03-feishu-method-probability",
                "TEACHER_PRIVATE",
                "synced",
                "parsed",
                "ready",
                "ready",
                List.of()));
        blockStore.replaceActiveBlocks("school-a", "doc-textbook", List.of(detailedBlock(
                "b-textbook",
                "doc-textbook",
                1,
                "教材-导数参数讨论.md",
                "reference",
                "导数参数讨论",
                "参数分类入口",
                "参数出现时先判断讨论区间会不会穿过端点，再决定符号表怎么列，这样学生不会把分段讨论写散。")));
        blockStore.replaceActiveBlocks("school-a", "doc-feishu", List.of(detailedBlock(
                "b-feishu",
                "doc-feishu",
                1,
                "讲法模板.md",
                "method",
                "二项分布与超几何分布讲法模板",
                "先分模型",
                "先追问抽取过程是否独立且可重复，再决定是二项分布还是超几何分布，不要先背公式名字。")));
        TeacherResourceBlockSearchService service = TeacherResourceBlockSearchServiceFixture.service(resourceStore, blockStore);

        TeacherResourceBlockSearchResponse response = service.search(
                "school-a",
                "admin",
                "admin-1",
                "参数题里讨论区间可能穿过端点符号表应该怎么引导学生列",
                5);

        assertThat(response.hits()).isNotEmpty();
        assertThat(response.hits().getFirst().documentId()).isEqualTo("doc-textbook");
        assertThat(response.hits().getFirst().blockId()).isEqualTo("b-textbook");
        assertThat(response.hits().getFirst().sourceType()).isEqualTo("public_textbook");
    }

    @Test
    void analysisCueWinsOverGenericExamCueInsideSameDocument() {
        InMemoryTeacherResourceStore resourceStore = new InMemoryTeacherResourceStore();
        InMemoryTeacherDocumentBlockStore blockStore = new InMemoryTeacherDocumentBlockStore();
        resourceStore.save(new TeacherResourceDocumentResponse(
                "doc-gaokao",
                "school-a",
                "teacher-1",
                "local_path",
                "runtime-gaokao-conic",
                null,
                "C:/workspace/runtime-authored/04-gaokao-conic",
                "MATH_VIP",
                "synced",
                "parsed",
                "ready",
                "ready",
                List.of()));
        blockStore.replaceActiveBlocks("school-a", "doc-gaokao", List.of(
                detailedBlock(
                        "b-question",
                        "doc-gaokao",
                        1,
                        "2024高考真题.md",
                        "question",
                        "2024 高考真题",
                        "椭圆切线题",
                        "已知椭圆上一点的切线与坐标轴围成三角形，求面积最小值。"),
                detailedBlock(
                        "b-analysis",
                        "doc-gaokao",
                        2,
                        "解析.md",
                        "analysis",
                        "真题解析",
                        "变量怎么设",
                        "先把切点参数化，让变量有几何意义，再去写面积式，别一上来盯着斜率硬算。")));
        TeacherResourceBlockSearchService service = TeacherResourceBlockSearchServiceFixture.service(resourceStore, blockStore);

        TeacherResourceBlockSearchResponse response = service.search(
                "school-a",
                "teacher",
                "teacher-1",
                "椭圆切线面积最值这类真题，解析里变量应该先怎么设才顺",
                5,
                "/api/teacher/resources/search",
                TeacherResourceSearchFilter.of(null, null, List.of("gaokao"), null),
                "two_stage_doc_block");

        assertThat(response.hits()).isNotEmpty();
        assertThat(response.hits().getFirst().blockId()).isEqualTo("b-analysis");
        assertThat(response.hits().getFirst().blockRole()).isEqualTo("analysis");
    }

    @Test
    void graphNormalizedQueryCanPromoteCorrectDocumentEvenWhenSurfaceWordsDrift() {
        InMemoryTeacherResourceStore resourceStore = new InMemoryTeacherResourceStore();
        InMemoryTeacherDocumentBlockStore blockStore = new InMemoryTeacherDocumentBlockStore();
        InMemoryKnowledgeQuestionBankStore knowledgeStore = new InMemoryKnowledgeQuestionBankStore();
        knowledgeStore.saveKnowledgePoint(new KnowledgePointRecord(
                "kp-module-sequence",
                "school-a",
                null,
                "MATH_VIP",
                "数列",
                "数列",
                "active",
                "display_spine_v0.1; nodeType=MODULE"));
        knowledgeStore.saveKnowledgePoint(new KnowledgePointRecord(
                "kp-topic-sequence-sum",
                "school-a",
                null,
                "MATH_VIP",
                "数列求通项与求和",
                "数列/数列求通项与求和",
                "active",
                "display_spine_v0.1; nodeType=TOPIC"));
        knowledgeStore.saveKnowledgeRelation(new KnowledgeRelationRecord(
                "rel-sequence-topic",
                "school-a",
                "kp-module-sequence",
                "kp-topic-sequence-sum",
                "CONTAINS_TOPIC",
                "display_spine_v0.1; 数列包含数列求通项与求和",
                "active"));
        resourceStore.save(document("doc-sequence", "teacher-1", "MATH_VIP", "General method handout"));
        resourceStore.save(document("doc-probability", "teacher-1", "MATH_VIP", "General method handout B"));
        blockStore.replaceActiveBlocks("school-a", "doc-sequence", List.of(new TeacherDocumentBlockResponse(
                "b-sequence",
                "doc-sequence",
                "doc-sequence:1",
                "text",
                1,
                "General notes",
                "Method drift",
                null,
                null,
                "handout/method.md",
                "method",
                "先比较前 n 项和与通项，再决定是否做错位相减。",
                "先比较前 n 项和与通项，再决定是否做错位相减。",
                "[]",
                "[]",
                "[\"kp-topic-sequence-sum\"]",
                "[\"数列求通项与求和\",\"数列\"]",
                "b-sequence-checksum",
                1.0,
                "active")));
        blockStore.replaceActiveBlocks("school-a", "doc-probability", List.of(new TeacherDocumentBlockResponse(
                "b-probability",
                "doc-probability",
                "doc-probability:1",
                "text",
                1,
                "General notes",
                "Method drift",
                null,
                null,
                "handout/method.md",
                "method",
                "先看抽样是否放回，再决定概率模型。",
                "先看抽样是否放回，再决定概率模型。",
                "[]",
                "[]",
                "[]",
                "[]",
                "b-probability-checksum",
                1.0,
                "active")));
        TeacherResourceBlockSearchService service =
                TeacherResourceBlockSearchServiceFixture.service(resourceStore, blockStore, knowledgeStore);

        TeacherResourceBlockSearchResponse response = service.search(
                "school-a",
                "teacher",
                "teacher-1",
                "数列方法怎么讲",
                5);

        assertThat(response.hits()).isNotEmpty();
        assertThat(response.hits().getFirst().documentId()).isEqualTo("doc-sequence");
        assertThat(response.hits().getFirst().graphTags()).contains("数列");
    }

    @Test
    void canStillRunLegacyBlockHybridForBaselineComparison() {
        InMemoryTeacherResourceStore resourceStore = new InMemoryTeacherResourceStore();
        InMemoryTeacherDocumentBlockStore blockStore = new InMemoryTeacherDocumentBlockStore();
        resourceStore.save(document("doc-own", "teacher-1", "TEACHER_PRIVATE", "Own vector notes"));
        blockStore.replaceActiveBlocks("school-a", "doc-own", List.of(detailedBlock(
                "b-own",
                "doc-own",
                1,
                "handout/reference.md",
                "reference",
                "Vectors",
                "Dot product",
                "Space vector dot product method belongs to the teacher private handout.")));
        TeacherResourceBlockSearchService service = TeacherResourceBlockSearchServiceFixture.service(resourceStore, blockStore);

        TeacherResourceBlockSearchResponse response = service.search(
                "school-a",
                "teacher",
                "teacher-1",
                "dot product",
                10,
                "/api/teacher/resources/search",
                TeacherResourceSearchFilter.EMPTY,
                "legacy_block_hybrid");

        assertThat(response.retrievalMode()).isEqualTo("legacy_block_hybrid");
        assertThat(response.hits()).extracting(TeacherResourceBlockSearchResponse.Hit::blockId)
                .containsExactly("b-own");
    }

    @Test
    void sourceTypeFilterMatchesInferredLibraryForLegacyLocalPathRows() {
        InMemoryTeacherResourceStore resourceStore = new InMemoryTeacherResourceStore();
        InMemoryTeacherDocumentBlockStore blockStore = new InMemoryTeacherDocumentBlockStore();
        resourceStore.save(new TeacherResourceDocumentResponse(
                "doc-qq",
                "school-a",
                "teacher-1",
                "local_path",
                "Runtime QQ bundle package",
                null,
                "C:/workspace/runtime-authored/02-qq-bundle-vector",
                "MATH_VIP",
                "synced",
                "parsed",
                "ready",
                "ready",
                List.of()));
        resourceStore.save(new TeacherResourceDocumentResponse(
                "doc-mock",
                "school-a",
                "teacher-1",
                "local_path",
                "Runtime mock package",
                null,
                "C:/workspace/runtime-authored/05-mock-sequence",
                "TEACHER_PRIVATE",
                "synced",
                "parsed",
                "ready",
                "ready",
                List.of()));
        blockStore.replaceActiveBlocks("school-a", "doc-qq", List.of(detailedBlock(
                "b-qq",
                "doc-qq",
                1,
                "专题讲解.md",
                "lesson",
                "Vectors",
                "Angle",
                "Vector angle bundle analysis and lesson summary.")));
        blockStore.replaceActiveBlocks("school-a", "doc-mock", List.of(detailedBlock(
                "b-mock",
                "doc-mock",
                1,
                "模拟题.md",
                "question",
                "Sequence",
                "Sum",
                "Sequence mock question and answer.")));
        TeacherResourceBlockSearchService service = TeacherResourceBlockSearchServiceFixture.service(resourceStore, blockStore);

        TeacherResourceBlockSearchResponse response = service.search(
                "school-a",
                "teacher",
                "teacher-1",
                "vector angle analysis",
                10,
                "/api/teacher/resources/search",
                TeacherResourceSearchFilter.of(null, null, List.of("QQ_BUNDLE"), null),
                "two_stage_doc_block");

        assertThat(response.hits()).extracting(TeacherResourceBlockSearchResponse.Hit::documentId)
                .containsExactly("doc-qq");
        assertThat(response.hits().getFirst().sourceType()).isEqualTo("qq_bundle");
    }

    @Test
    void studentCannotSearchTeacherResourceBlocks() {
        TeacherResourceBlockSearchService service = TeacherResourceBlockSearchServiceFixture.service(
                new InMemoryTeacherResourceStore(),
                new InMemoryTeacherDocumentBlockStore());

        assertThatThrownBy(() -> service.search("school-a", "student", "student-1", "vector", 10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("teacher or admin");
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
                "local_path",
                title,
                null,
                "C:/math/" + documentId,
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
            String text) {
        return detailedBlock(
                blockId,
                documentId,
                blockOrder,
                "notes/" + blockId + ".md",
                "reference",
                "Vectors",
                "Dot product",
                text);
    }

    private static TeacherDocumentBlockResponse detailedBlock(
            String blockId,
            String documentId,
            int blockOrder,
            String sourcePath,
            String blockRole,
            String chapter,
            String section,
            String text) {
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
                sourcePath,
                blockRole,
                text,
                text.toLowerCase(),
                "[]",
                "[]",
                "[]",
                "[]",
                blockId + "-checksum",
                1.0,
                "active");
    }
}
