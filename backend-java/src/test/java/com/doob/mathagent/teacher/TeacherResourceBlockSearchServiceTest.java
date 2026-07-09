package com.doob.mathagent.teacher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.doob.mathagent.knowledge.service.InMemoryKnowledgeQuestionBankStore;
import com.doob.mathagent.knowledge.service.KnowledgePointRecord;
import com.doob.mathagent.knowledge.service.KnowledgeRelationRecord;
import com.doob.mathagent.resources.TextbookCatalogReader;
import com.doob.mathagent.resources.TextbookChunkReader;
import com.doob.mathagent.resources.TextbookResourceProperties;
import com.doob.mathagent.retrieval.LocalTextbookBm25SearchEngine;
import com.doob.mathagent.retrieval.TextbookRetrievalService;
import com.doob.mathagent.retrieval.TextbookRetrievalServiceFixture;
import com.doob.mathagent.teacher.service.InMemoryTeacherDocumentBlockStore;
import com.doob.mathagent.teacher.service.InMemoryTeacherResourceStore;
import com.doob.mathagent.teacher.search.audit.RecentTeacherResourceBlockSearchAuditStore;
import com.doob.mathagent.teacher.service.TeacherResourceAssetService;
import com.doob.mathagent.teacher.search.audit.TeacherResourceBlockSearchAuditEvent;
import com.doob.mathagent.teacher.service.TeacherResourceBlockSearchService;
import com.doob.mathagent.teacher.search.TeacherResourceGraphAlignmentService;
import com.doob.mathagent.teacher.search.TeacherResourceSearchFilter;
import com.doob.mathagent.teacher.block.TeacherDocumentBlockResponse;
import com.doob.mathagent.teacher.search.TeacherResourceBlockSearchResponse;
import com.doob.mathagent.teacher.document.TeacherResourceDocumentResponse;
import com.doob.mathagent.vector.service.TestVectorIndexService;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TeacherResourceBlockSearchServiceTest {

    @TempDir
    Path tempDir;

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
    void specifiedTextbookLibraryUsesRealProcessedBooksCorpusInsteadOfTeacherStoreDerivativeRows() throws Exception {
        Path processedBooksRoot = createProcessedBooksCorpus(
                tempDir.resolve("processed-books"),
                "real-textbook-doc",
                "教材导数单调性",
                List.of("导数", "单调性"),
                12,
                "闭区间单调性要先看定义域和端点，再列符号表，不能只盯导数零点。");
        InMemoryTeacherResourceStore resourceStore = new InMemoryTeacherResourceStore();
        InMemoryTeacherDocumentBlockStore blockStore = new InMemoryTeacherDocumentBlockStore();
        resourceStore.save(new TeacherResourceDocumentResponse(
                "doc-imported-public-textbook",
                "school-a",
                "admin-1",
                "public_textbook",
                "Old imported textbook derivative",
                null,
                "C:/workspace/runtime-authored/public-textbook-derivative",
                "PUBLIC_TEXTBOOK",
                "synced",
                "parsed",
                "ready",
                "ready",
                List.of()));
        blockStore.replaceActiveBlocks("school-a", "doc-imported-public-textbook", List.of(detailedBlock(
                "b-imported-public-textbook",
                "doc-imported-public-textbook",
                1,
                "old/textbook-derivative.md",
                "reference",
                "导数",
                "旧导入块",
                "这是旧 teacher block store 里的教材导入块，本次 real textbook 检索不应优先依赖它。")));
        TextbookRetrievalService textbookService = TextbookRetrievalServiceFixture.service(
                new TextbookCatalogReader(),
                new TextbookChunkReader(),
                new LocalTextbookBm25SearchEngine(),
                event -> {
                });
        TeacherResourceBlockSearchService service = new TeacherResourceBlockSearchService(
                resourceStore,
                blockStore,
                new RecentTeacherResourceBlockSearchAuditStore(10),
                TestVectorIndexService.successful(resourceStore, blockStore),
                TeacherResourceGraphAlignmentService.disabled(),
                TeacherResourceAssetService.disabled(),
                textbookService,
                null,
                new TextbookResourceProperties(processedBooksRoot));

        TeacherResourceBlockSearchResponse response = service.search(
                "school-a",
                "teacher",
                "teacher-1",
                "指定库是textbook，想找闭区间单调性为什么必须先看端点和定义域",
                5,
                "/api/teacher/resources/search",
                TeacherResourceSearchFilter.of(null, null, List.of("textbook"), null));

        assertThat(response.hits()).isNotEmpty();
        assertThat(response.hits().getFirst().documentId()).isEqualTo("real-textbook-doc");
        assertThat(response.hits().getFirst().blockId()).isEqualTo("real-textbook-doc-p12-1");
        assertThat(response.hits().getFirst().sourceType()).isEqualTo("public_textbook");
        assertThat(response.hits().getFirst().permissionScope()).isEqualTo("PUBLIC_TEXTBOOK");
        assertThat(response.hits().getFirst().sourcePath()).contains("textbook://real-textbook-doc/page/12");
        assertThat(response.hits()).extracting(TeacherResourceBlockSearchResponse.Hit::blockId)
                .doesNotContain("b-imported-public-textbook");
    }

    @Test
    void mixedSearchAlsoUsesRealProcessedBooksCorpusInsteadOfTeacherStoreDerivativeRows() throws Exception {
        Path processedBooksRoot = createProcessedBooksCorpus(
                tempDir.resolve("processed-books-mixed"),
                "real-textbook-doc",
                "教材导数单调性",
                List.of("导数", "单调性"),
                12,
                "闭区间单调性要先看定义域和端点，再列符号表，不能只盯导数零点。");
        InMemoryTeacherResourceStore resourceStore = new InMemoryTeacherResourceStore();
        InMemoryTeacherDocumentBlockStore blockStore = new InMemoryTeacherDocumentBlockStore();
        resourceStore.save(new TeacherResourceDocumentResponse(
                "doc-imported-public-textbook",
                "school-a",
                "admin-1",
                "public_textbook",
                "Old imported textbook derivative",
                null,
                "C:/workspace/runtime-authored/public-textbook-derivative",
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
                "feishu",
                "概率讲法模板",
                null,
                "C:/workspace/runtime-authored/03-feishu-method-probability",
                "TEACHER_PRIVATE",
                "synced",
                "parsed",
                "ready",
                "ready",
                List.of()));
        blockStore.replaceActiveBlocks("school-a", "doc-imported-public-textbook", List.of(detailedBlock(
                "b-imported-public-textbook",
                "doc-imported-public-textbook",
                1,
                "old/textbook-derivative.md",
                "reference",
                "导数",
                "旧导入块",
                "这是旧 teacher block store 里的教材导入块，本次 mixed real textbook 检索不应优先依赖它。")));
        blockStore.replaceActiveBlocks("school-a", "doc-feishu", List.of(detailedBlock(
                "b-feishu",
                "doc-feishu",
                1,
                "讲法模板.md",
                "method",
                "概率",
                "先分模型",
                "先追问抽取过程是否独立且可重复，再决定是二项分布还是超几何分布。")));
        TextbookRetrievalService textbookService = TextbookRetrievalServiceFixture.service(
                new TextbookCatalogReader(),
                new TextbookChunkReader(),
                new LocalTextbookBm25SearchEngine(),
                event -> {
                });
        TeacherResourceBlockSearchService service = new TeacherResourceBlockSearchService(
                resourceStore,
                blockStore,
                new RecentTeacherResourceBlockSearchAuditStore(10),
                TestVectorIndexService.successful(resourceStore, blockStore),
                TeacherResourceGraphAlignmentService.disabled(),
                TeacherResourceAssetService.disabled(),
                textbookService,
                null,
                new TextbookResourceProperties(processedBooksRoot));

        TeacherResourceBlockSearchResponse response = service.search(
                "school-a",
                "teacher",
                "teacher-1",
                "闭区间单调性为什么必须先看端点和定义域",
                5);

        assertThat(response.hits()).isNotEmpty();
        assertThat(response.hits().getFirst().documentId()).isEqualTo("real-textbook-doc");
        assertThat(response.hits().getFirst().blockId()).isEqualTo("real-textbook-doc-p12-1");
        assertThat(response.hits()).extracting(TeacherResourceBlockSearchResponse.Hit::blockId)
                .doesNotContain("b-imported-public-textbook");
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
                TeacherResourceSearchFilter.of(null, null, List.of("gaokao"), null));

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
    void legacyStrategyAliasNowResolvesToTwoStageMainline() {
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
                TeacherResourceSearchFilter.EMPTY);

        assertThat(response.retrievalMode()).isEqualTo("two_stage_doc_block");
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
                TeacherResourceSearchFilter.of(null, null, List.of("QQ_BUNDLE"), null));

        assertThat(response.hits()).extracting(TeacherResourceBlockSearchResponse.Hit::documentId)
                .containsExactly("doc-qq");
        assertThat(response.hits().getFirst().sourceType()).isEqualTo("qq_bundle");
    }

    @Test
    void explicitQuestionIntentBeatsLessonSiblingInsideFilteredQqBundleDocument() {
        InMemoryTeacherResourceStore resourceStore = new InMemoryTeacherResourceStore();
        InMemoryTeacherDocumentBlockStore blockStore = new InMemoryTeacherDocumentBlockStore();
        resourceStore.save(new TeacherResourceDocumentResponse(
                "doc-qq-question",
                "school-a",
                "teacher-1",
                "qq_bundle",
                "Runtime QQ bundle original question pack",
                null,
                "C:/workspace/runtime-authored/qq-question-pack",
                "MATH_VIP",
                "synced",
                "parsed",
                "ready",
                "ready",
                List.of()));
        blockStore.replaceActiveBlocks("school-a", "doc-qq-question", List.of(
                detailedBlock(
                        "b-qq-lesson",
                        "doc-qq-question",
                        1,
                        "qq_bundle/专题讲解.md",
                        "lesson",
                        "空间向量",
                        "专题总述",
                        "专题讲解先梳理空间向量整包讲法，强调线面角与向量角的联系，属于讲义总述。"),
                detailedBlock(
                        "b-qq-question",
                        "doc-qq-question",
                        2,
                        "qq_bundle/原题题面.md",
                        "question",
                        "空间向量",
                        "原题题面",
                        "原题题面要求先定位专题包里的题目原文，只呈现题干与条件，不展开答案解析。"),
                detailedBlock(
                        "b-qq-analysis",
                        "doc-qq-question",
                        3,
                        "qq_bundle/答案解析.md",
                        "analysis",
                        "空间向量",
                        "答案解析",
                        "答案解析说明解题路线与点评，应该排在原题题面之后。")));
        TeacherResourceBlockSearchService service = TeacherResourceBlockSearchServiceFixture.service(resourceStore, blockStore);

        TeacherResourceBlockSearchResponse response = service.search(
                "school-a",
                "teacher",
                "teacher-1",
                "帮我先定位这套专题包里的原题题面，不要讲义总述，也不要答案解析",
                5,
                "/api/teacher/resources/search",
                TeacherResourceSearchFilter.of(null, null, List.of("qq_bundle"), null));

        assertThat(response.hits()).isNotEmpty();
        assertThat(response.hits().getFirst().documentId()).isEqualTo("doc-qq-question");
        assertThat(response.hits().getFirst().blockId()).isEqualTo("b-qq-question");
        assertThat(response.hits().getFirst().blockRole()).isEqualTo("question");
    }

    @Test
    void negatedAnalysisCueDoesNotBeatLessonWhenTeacherExplicitlyRejectsAnswerBlock() {
        InMemoryTeacherResourceStore resourceStore = new InMemoryTeacherResourceStore();
        InMemoryTeacherDocumentBlockStore blockStore = new InMemoryTeacherDocumentBlockStore();
        resourceStore.save(new TeacherResourceDocumentResponse(
                "doc-qq-lesson",
                "school-a",
                "teacher-1",
                "qq_bundle",
                "Runtime QQ bundle lesson pack",
                null,
                "C:/workspace/runtime-authored/qq-lesson-pack",
                "MATH_VIP",
                "synced",
                "parsed",
                "ready",
                "ready",
                List.of()));
        blockStore.replaceActiveBlocks("school-a", "doc-qq-lesson", List.of(
                detailedBlock(
                        "b-qq-lesson",
                        "doc-qq-lesson",
                        1,
                        "qq_bundle/专题讲解.md",
                        "lesson",
                        "空间向量",
                        "整体讲法",
                        "专题讲解先梳理线面角整体讲法和课堂推进顺序。"),
                detailedBlock(
                        "b-qq-analysis",
                        "doc-qq-lesson",
                        2,
                        "qq_bundle/答案解析.md",
                        "analysis",
                        "空间向量",
                        "答案解析",
                        "答案解析强调单题步骤与讲评，不适合放在专题总述前面.")));
        TeacherResourceBlockSearchService service = TeacherResourceBlockSearchServiceFixture.service(resourceStore, blockStore);

        TeacherResourceBlockSearchResponse response = service.search(
                "school-a",
                "teacher",
                "teacher-1",
                "优先找专题讲解块，不要直接跳到答案解析，题面也不要排前面",
                5,
                "/api/teacher/resources/search",
                TeacherResourceSearchFilter.of(null, null, List.of("qq_bundle"), null));

        assertThat(response.hits()).isNotEmpty();
        assertThat(response.hits().getFirst().blockId()).isEqualTo("b-qq-lesson");
        assertThat(response.hits().getFirst().blockRole()).isEqualTo("lesson");
    }

    @Test
    void classroomCommentaryCourseCueKeepsLessonAheadOfSingleQuestionAnalysis() {
        InMemoryTeacherResourceStore resourceStore = new InMemoryTeacherResourceStore();
        InMemoryTeacherDocumentBlockStore blockStore = new InMemoryTeacherDocumentBlockStore();
        resourceStore.save(new TeacherResourceDocumentResponse(
                "doc-qq-commentary-course",
                "school-a",
                "teacher-1",
                "qq_bundle",
                "Runtime QQ commentary lesson pack",
                null,
                "C:/workspace/runtime-authored/qq-commentary-course-pack",
                "MATH_VIP",
                "synced",
                "parsed",
                "ready",
                "ready",
                List.of()));
        blockStore.replaceActiveBlocks("school-a", "doc-qq-commentary-course", List.of(
                detailedBlock(
                        "b-course-lesson",
                        "doc-qq-commentary-course",
                        1,
                        "qq_bundle/topic-lesson.md",
                        "lesson",
                        "空间向量",
                        "专题整体讲法",
                        "专题讲解块说明开篇目标、适用课型和整体课堂推进顺序。"),
                detailedBlock(
                        "b-course-analysis",
                        "doc-qq-commentary-course",
                        2,
                        "qq_bundle/answer-analysis.md",
                        "analysis",
                        "空间向量",
                        "单题答案解析",
                        "答案解析块记录某一道题的步骤、讲评和验算提醒。")));
        TeacherResourceBlockSearchService service = TeacherResourceBlockSearchServiceFixture.service(resourceStore, blockStore);

        TeacherResourceBlockSearchResponse response = service.search(
                "school-a",
                "teacher",
                "teacher-1",
                "专题讲评课要先找整体讲法入口，而不是某一道题的解析，指定库是qq_bundle",
                5,
                "/api/teacher/resources/search",
                TeacherResourceSearchFilter.of(null, null, List.of("qq_bundle"), null));

        assertThat(response.hits()).isNotEmpty();
        assertThat(response.hits().getFirst().blockId()).isEqualTo("b-course-lesson");
        assertThat(response.hits().getFirst().blockRole()).isEqualTo("lesson");
    }

    @Test
    void negatedQuestionCueDoesNotBeatAnalysisWhenTeacherRejectsPromptBlock() {
        InMemoryTeacherResourceStore resourceStore = new InMemoryTeacherResourceStore();
        InMemoryTeacherDocumentBlockStore blockStore = new InMemoryTeacherDocumentBlockStore();
        resourceStore.save(new TeacherResourceDocumentResponse(
                "doc-gaokao-analysis",
                "school-a",
                "teacher-1",
                "gaokao",
                "Runtime gaokao analysis pack",
                null,
                "C:/workspace/runtime-authored/gaokao-analysis-pack",
                "MATH_VIP",
                "synced",
                "parsed",
                "ready",
                "ready",
                List.of()));
        blockStore.replaceActiveBlocks("school-a", "doc-gaokao-analysis", List.of(
                detailedBlock(
                        "b-gaokao-question",
                        "doc-gaokao-analysis",
                        1,
                        "2024高考真题.md",
                        "question",
                        "圆锥曲线",
                        "原题题面",
                        "原题题面只给出切线与面积最值问题的条件。"),
                detailedBlock(
                        "b-gaokao-analysis",
                        "doc-gaokao-analysis",
                        2,
                        "解析.md",
                        "analysis",
                        "圆锥曲线",
                        "变量设置",
                        "解析里先讲变量如何设置，再展开面积最值的推导路线。")));
        TeacherResourceBlockSearchService service = TeacherResourceBlockSearchServiceFixture.service(resourceStore, blockStore);

        TeacherResourceBlockSearchResponse response = service.search(
                "school-a",
                "teacher",
                "teacher-1",
                "优先找解析或讲评块，题面不能排在前面，返回最贴近的证据块即可",
                5,
                "/api/teacher/resources/search",
                TeacherResourceSearchFilter.of(null, null, List.of("gaokao"), null));

        assertThat(response.hits()).isNotEmpty();
        assertThat(response.hits().getFirst().blockId()).isEqualTo("b-gaokao-analysis");
        assertThat(response.hits().getFirst().blockRole()).isEqualTo("analysis");
    }

    @Test
    void specifiedLibraryReturnsEmptyWhenOnlyWeakGenericWithinLibraryNoiseMatches() {
        InMemoryTeacherResourceStore resourceStore = new InMemoryTeacherResourceStore();
        InMemoryTeacherDocumentBlockStore blockStore = new InMemoryTeacherDocumentBlockStore();
        resourceStore.save(new TeacherResourceDocumentResponse(
                "doc-feishu-generic",
                "school-a",
                "teacher-1",
                "feishu",
                "Teacher method notes",
                null,
                "C:/workspace/runtime-authored/feishu-generic-pack",
                "TEACHER_PRIVATE",
                "synced",
                "parsed",
                "ready",
                "ready",
                List.of()));
        blockStore.replaceActiveBlocks("school-a", "doc-feishu-generic", List.of(
                detailedBlock(
                        "b-feishu-method",
                        "doc-feishu-generic",
                        1,
                        "feishu/method.md",
                        "method",
                        "General method",
                        "Classroom prompt",
                        "This teacher method note explains how to open class, organize review rhythm, and prepare examples."),
                detailedBlock(
                        "b-feishu-tip",
                        "doc-feishu-generic",
                        2,
                        "feishu/tip.md",
                        "tip",
                        "General reminder",
                        "Classroom notice",
                        "This tip reminds the teacher to keep the explanation structured and the board clean.")));
        TeacherResourceBlockSearchService service = TeacherResourceBlockSearchServiceFixture.service(resourceStore, blockStore);

        TeacherResourceBlockSearchResponse response = service.search(
                "school-a",
                "teacher",
                "teacher-1",
                "Need a teacher reference about campus safety drill checklist for student evacuation",
                5,
                "/api/teacher/resources/search",
                TeacherResourceSearchFilter.of(null, null, List.of("feishu"), null));

        assertThat(response.hits()).isEmpty();
    }

    @Test
    void specifiedLibraryKeepsLowScorePositiveWhenRoleAndStructureAnchorsAreClear() {
        InMemoryTeacherResourceStore resourceStore = new InMemoryTeacherResourceStore();
        InMemoryTeacherDocumentBlockStore blockStore = new InMemoryTeacherDocumentBlockStore();
        resourceStore.save(new TeacherResourceDocumentResponse(
                "doc-feishu-boardwork",
                "school-a",
                "teacher-1",
                "feishu",
                "Probability boardwork flow",
                null,
                "C:/workspace/runtime-authored/feishu-boardwork-pack",
                "TEACHER_PRIVATE",
                "synced",
                "parsed",
                "ready",
                "ready",
                List.of()));
        blockStore.replaceActiveBlocks("school-a", "doc-feishu-boardwork", List.of(
                detailedBlock(
                        "b-feishu-method",
                        "doc-feishu-boardwork",
                        1,
                        "feishu/method.md",
                        "method",
                        "Probability method",
                        "Overall teaching route",
                        "Start by deciding whether replacement is allowed, then classify the counting model."),
                detailedBlock(
                        "b-feishu-boardwork",
                        "doc-feishu-boardwork",
                        2,
                        "feishu/boardwork.md",
                        "boardwork",
                        "Probability boardwork",
                        "Blackboard sequence",
                        "Boardwork should first compare the sampling process, then write the branching structure on the blackboard.")));
        TeacherResourceBlockSearchService service = TeacherResourceBlockSearchServiceFixture.service(resourceStore, blockStore);

        TeacherResourceBlockSearchResponse response = service.search(
                "school-a",
                "teacher",
                "teacher-1",
                "Need the boardwork order for probability sampling comparison, not the generic method overview",
                5,
                "/api/teacher/resources/search",
                TeacherResourceSearchFilter.of(null, null, List.of("feishu"), null));

        assertThat(response.hits()).isNotEmpty();
        assertThat(response.hits().getFirst().blockId()).isEqualTo("b-feishu-boardwork");
        assertThat(response.hits().getFirst().blockRole()).isEqualTo("boardwork");
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

    private static Path createProcessedBooksCorpus(
            Path processedBooksRoot,
            String docId,
            String bookName,
            List<String> chapterPath,
            int pageNo,
            String text) throws Exception {
        Files.createDirectories(processedBooksRoot);
        Path bookRoot = processedBooksRoot.resolve(docId);
        Path aiChunkDir = bookRoot.resolve("jsonl_ai");
        Files.createDirectories(aiChunkDir);
        String catalogLine = """
                {"doc_id":"%s","book_name":"%s","volume":"必修一","book_root":"%s","manifest":"%s","chunk_count":1,"page_count":1,"ai_ok":true}
                """.formatted(docId, bookName, docId, docId + "/manifest.json").strip();
        Files.writeString(
                processedBooksRoot.resolve("catalog.jsonl"),
                catalogLine + System.lineSeparator(),
                StandardCharsets.UTF_8);
        String chapterJson = chapterPath.stream()
                .map(value -> "\"" + value + "\"")
                .collect(java.util.stream.Collectors.joining(","));
        String chunkLine = """
                {"chunk_id":"%s-p%d-1","doc_id":"%s","book_name":"%s","volume":"必修一","chapter_path":[%s],"page_no":%d,"printed_page_no":"%d","chunk_type":"text","section_title":"闭区间单调性","text":"%s","formula_text":"","image_rel_paths":[],"source_page_image":"pages/p%03d.png"}
                """.formatted(
                docId,
                pageNo,
                docId,
                bookName,
                chapterJson,
                pageNo,
                pageNo,
                text,
                pageNo).strip();
        Files.writeString(
                aiChunkDir.resolve("chunks.jsonl"),
                chunkLine + System.lineSeparator(),
                StandardCharsets.UTF_8);
        return processedBooksRoot;
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

