package com.doob.mathagent.student;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.doob.mathagent.agent.service.AiChatGateway;
import com.doob.mathagent.agent.service.AiChatResult;
import com.doob.mathagent.infrastructure.ai.AiProviderCatalog;
import com.doob.mathagent.infrastructure.ai.AiProviderProperties;
import com.doob.mathagent.infrastructure.security.RequestSubject;
import com.doob.mathagent.knowledge.service.InMemoryKnowledgeQuestionBankStore;
import com.doob.mathagent.knowledge.service.KnowledgeGraphSpineService;
import com.doob.mathagent.knowledge.service.KnowledgePointRecord;
import com.doob.mathagent.resources.TextbookCatalogReader;
import com.doob.mathagent.resources.TextbookChunkReader;
import com.doob.mathagent.resources.TextbookResourceProperties;
import com.doob.mathagent.retrieval.LocalTextbookBm25SearchEngine;
import com.doob.mathagent.retrieval.TextbookRetrievalService;
import com.doob.mathagent.student.dto.StudentExplanationRequest;
import com.doob.mathagent.student.service.StudentExplanationService;
import com.doob.mathagent.student.vo.StudentExplanationResponse;
import com.doob.mathagent.teacher.service.InMemoryTeacherDocumentBlockStore;
import com.doob.mathagent.teacher.service.InMemoryTeacherResourceStore;
import com.doob.mathagent.teacher.service.TeacherResourceBlockSearchService;
import com.doob.mathagent.teacher.vo.TeacherDocumentBlockResponse;
import com.doob.mathagent.teacher.vo.TeacherResourceBlockSearchResponse;
import com.doob.mathagent.teacher.vo.TeacherResourceDocumentResponse;
import com.doob.mathagent.vector.service.TestVectorIndexService;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StudentExplanationServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void studentExplanationUsesTextbookAndGraphSourcesWithoutTeacherPrivateResources() throws Exception {
        StudentExplanationService service = serviceWithResources(tempDir);
        StudentExplanationRequest request = request("space vector dot product dihedral angle", true, true, true);

        StudentExplanationResponse response = service.explain(
                request,
                new RequestSubject("default", "student", "student-1", "device-1"));

        assertThat(response.viewerRole()).isEqualTo("student");
        assertThat(response.studentId()).isEqualTo("student-1");
        assertThat(response.cards()).extracting(StudentExplanationResponse.ExplanationCard::cardKey)
                .contains("problem_understanding", "knowledge_points", "method_hint", "step_by_step", "source_links");
        assertThat(response.sources()).extracting(StudentExplanationResponse.ExplanationSource::sourceUri)
                .anyMatch(uri -> uri.startsWith("textbook://book-vector/page/12#chunk="))
                .anyMatch(uri -> uri.equals("math-agent://knowledge/graph-spine/v0.1#node=spine-vector-dot"))
                .noneMatch(uri -> uri.startsWith("teacher-resource://"));
        assertThat(response.workflowStages())
                .anySatisfy(stage -> {
                    assertThat(stage.stageKey()).isEqualTo("search_teacher_resources");
                    assertThat(stage.status()).isEqualTo("skipped");
                });
    }

    @Test
    void teacherExplanationCanIncludeVisibleTeacherResourceSources() throws Exception {
        StudentExplanationService service = serviceWithResources(tempDir);
        StudentExplanationRequest request = request("space vector dot product dihedral angle", true, true, true);

        StudentExplanationResponse response = service.explain(
                request,
                new RequestSubject("default", "teacher", "teacher-1", "device-1"));

        assertThat(response.viewerRole()).isEqualTo("teacher");
        assertThat(response.studentId()).isNull();
        assertThat(response.sources()).extracting(StudentExplanationResponse.ExplanationSource::sourceUri)
                .anyMatch(uri -> uri.equals("teacher-resource://teacher-doc-1/block/block-1"));
        assertThat(response.workflowStages())
                .anySatisfy(stage -> {
                    assertThat(stage.stageKey()).isEqualTo("search_teacher_resources");
                    assertThat(stage.status()).isEqualTo("completed");
                });
    }

    @Test
    void teacherResourceSearchFailureDoesNotFailTheWholeExplanation() throws Exception {
        StudentExplanationService service = serviceWithFailingTeacherResourceSearch(tempDir);
        StudentExplanationRequest request = request("space vector dot product dihedral angle", true, true, true);

        StudentExplanationResponse response = service.explain(
                request,
                new RequestSubject("default", "admin", "admin-1", "device-1"));

        assertThat(response.viewerRole()).isEqualTo("admin");
        assertThat(response.cards()).isNotEmpty();
        assertThat(response.sources()).extracting(StudentExplanationResponse.ExplanationSource::sourceUri)
                .anyMatch(uri -> uri.startsWith("textbook://book-vector/page/12#chunk="))
                .noneMatch(uri -> uri.startsWith("teacher-resource://"));
        assertThat(response.workflowStages())
                .anySatisfy(stage -> {
                    assertThat(stage.stageKey()).isEqualTo("search_teacher_resources");
                    assertThat(stage.status()).isEqualTo("failed");
                    assertThat(stage.detail()).contains("teacher search unavailable");
                })
                .anySatisfy(stage -> {
                    assertThat(stage.stageKey()).isEqualTo("assemble_cards");
                    assertThat(stage.status()).isEqualTo("completed");
                });
    }

    @Test
    void imageOnlyRequestRequiresRealVisionOrQuestionText() throws Exception {
        StudentExplanationService service = serviceWithResources(tempDir);
        StudentExplanationRequest request = new StudentExplanationRequest(
                null,
                null,
                null,
                "question.png",
                "image/png",
                1024L,
                false,
                false,
                false,
                null,
                null);

        assertThatThrownBy(() -> service.explain(
                        request,
                        new RequestSubject("default", "student", "student-1", "device-1")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("requires successful real vision analysis");
    }

    @Test
    void aiStructuredCardsReplaceFallbackAndExposeTokenUsage() throws Exception {
        StudentExplanationService service = serviceWithResources(
                tempDir,
                modelRequest -> new AiChatResult(
                        modelRequest.providerName(),
                        modelRequest.modelCode(),
                        21,
                        19,
                        40,
                        "structured cards",
                        """
                                {"cards":[
                                  {"cardKey":"problem_understanding","title":"Problem","summary":"Find the dihedral angle with vectors.","items":["Build coordinates"],"sourceUris":[],"renderMode":"text"},
                                  {"cardKey":"knowledge_points","title":"Knowledge","summary":"Dot product computes angles.","items":["space vector dot product"],"sourceUris":["math-agent://knowledge/graph-spine/v0.1#node=spine-vector-dot"],"renderMode":"text"},
                                  {"cardKey":"step_by_step","title":"Steps","summary":"Use \\\\[\\\\cos\\\\theta=\\\\frac{a\\\\cdot b}{|a||b|}\\\\] after normal vectors.","items":["Build \\\\(xOy\\\\) coordinate system","Find normal vectors"],"sourceUris":["textbook://book-vector/page/12#chunk=chunk-vector-1"],"renderMode":"formula"},
                                  {"cardKey":"source_links","title":"Sources","summary":"Only backend sources are used.","items":["textbook"],"sourceUris":["textbook://book-vector/page/12#chunk=chunk-vector-1","fake://invented"],"renderMode":"source_list"}
                                ]}
                                """));
        StudentExplanationRequest request = request("space vector dot product dihedral angle", true, true, false);

        StudentExplanationResponse response = service.explain(request, new RequestSubject("school-a", "student", "student-001", "dev-device"));

        assertThat(response.aiDraft().enabled()).isTrue();
        assertThat(response.aiDraft().structured()).isTrue();
        assertThat(response.aiDraft().modelCode()).isEqualTo("gpt-5.4");
        assertThat(response.aiDraft().totalTokens()).isEqualTo(40);
        assertThat(response.cards()).extracting(StudentExplanationResponse.ExplanationCard::title)
                .contains("Steps");
        StudentExplanationResponse.ExplanationCard steps = response.cards().stream()
                .filter(card -> "step_by_step".equals(card.cardKey()))
                .findFirst()
                .orElseThrow();
        assertThat(steps.summary()).contains("$$").doesNotContain("\\[", "\\]");
        assertThat(steps.items()).anySatisfy(item ->
                assertThat(item).contains("$xOy$").doesNotContain("\\(", "\\)"));
        assertThat(response.cards()).flatExtracting(StudentExplanationResponse.ExplanationCard::sourceUris)
                .contains("textbook://book-vector/page/12#chunk=chunk-vector-1")
                .doesNotContain("fake://invented");
    }

    @Test
    void repairsMojibakeAiCardFieldsBeforeReturningCards() throws Exception {
        String title = mojibake("\u9898\u76ee\u5206\u6790");
        String summary = mojibake("\u5df2\u77e5\u51fd\u6570 f(x)=x^2-4x+3\uff0c\u5148\u6c42\u96f6\u70b9\u518d\u5224\u65ad\u5355\u8c03\u6027\u3002");
        String item = mojibake("\u89e3\u65b9\u7a0b $x^2-4x+3=0$");
        StudentExplanationService service = serviceWithResources(
                tempDir,
                modelRequest -> new AiChatResult(
                        modelRequest.providerName(),
                        modelRequest.modelCode(),
                        11,
                        17,
                        28,
                        "structured mojibake cards",
                        """
                                {"cards":[
                                  {"cardKey":"problem_understanding","title":%s,"summary":%s,"items":[%s],"sourceUris":[],"renderMode":"formula"},
                                  {"cardKey":"knowledge_points","title":"Knowledge","summary":"Quadratic function basics.","items":["zero point"],"sourceUris":[],"renderMode":"text"},
                                  {"cardKey":"step_by_step","title":"Steps","summary":"Solve and inspect monotonicity.","items":["factor"],"sourceUris":[],"renderMode":"formula"},
                                  {"cardKey":"source_links","title":"Sources","summary":"Only backend sources are used.","items":["none"],"sourceUris":[],"renderMode":"source_list"}
                                ]}
                                """.formatted(jsonString(title), jsonString(summary), jsonString(item))));
        StudentExplanationRequest request = request("quadratic function zero points", true, true, false);

        StudentExplanationResponse response = service.explain(request, new RequestSubject("school-a", "student", "student-001", "dev-device"));

        StudentExplanationResponse.ExplanationCard card = response.cards().getFirst();
        assertThat(card.title()).isEqualTo("\u9898\u76ee\u5206\u6790");
        assertThat(card.summary()).contains("\u5df2\u77e5\u51fd\u6570", "\u96f6\u70b9", "\u5355\u8c03\u6027");
        assertThat(card.items().getFirst()).contains("\u89e3\u65b9\u7a0b", "$x^2-4x+3=0$");
        assertThat(card.title() + card.summary() + card.items().getFirst()).doesNotContain("\u00e9", "\u00e4");
    }

    @Test
    void malformedAiJsonFailsWithoutInventingFallbackCards() throws Exception {
        StudentExplanationService service = serviceWithResources(
                tempDir,
                modelRequest -> new AiChatResult(
                        modelRequest.providerName(),
                        modelRequest.modelCode(),
                        5,
                        5,
                        10,
                        "bad json",
                        "not-json"));
        StudentExplanationRequest request = request("space vector dot product dihedral angle", true, true, false);

        assertThatThrownBy(() -> service.explain(
                        request,
                        new RequestSubject("school-a", "student", "student-001", "dev-device")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("AI card JSON parse failed");
    }

    @Test
    void requestMustContainQuestionTextOrImageMetadata() throws Exception {
        StudentExplanationService service = serviceWithResources(tempDir);
        StudentExplanationRequest request = request(" ", true, true, false);

        assertThatThrownBy(() -> service.explain(request, new RequestSubject("school-a", "student", "student-001", "dev-device")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("questionText or image metadata");
    }

    /**
     * Creates a normalized explanation request.
     */
    private static StudentExplanationRequest request(
            String questionText,
            boolean searchTextbook,
            boolean searchGraph,
            boolean searchTeacher) {
        return new StudentExplanationRequest(
                null,
                questionText,
                null,
                null,
                null,
                null,
                searchTextbook,
                searchGraph,
                searchTeacher,
                5,
                5);
    }

    private static String mojibake(String value) {
        return new String(value.getBytes(StandardCharsets.UTF_8), StandardCharsets.ISO_8859_1);
    }

    private static String jsonString(String value) {
        StringBuilder escaped = new StringBuilder("\"");
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            switch (current) {
                case '"' -> escaped.append("\\\"");
                case '\\' -> escaped.append("\\\\");
                case '\b' -> escaped.append("\\b");
                case '\f' -> escaped.append("\\f");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                default -> {
                    if (current < 0x20 || current >= 0x7f) {
                        escaped.append("\\u%04x".formatted((int) current));
                    } else {
                        escaped.append(current);
                    }
                }
            }
        }
        return escaped.append('"').toString();
    }

    /**
     * Creates the real service graph with temporary JSONL textbook files and in-memory stores.
     */
    private static StudentExplanationService serviceWithResources(Path root) throws Exception {
        return serviceWithResources(root, null);
    }

    /**
     * Creates the real service graph with a configurable AI gateway.
     */
    private static StudentExplanationService serviceWithResources(Path root, AiChatGateway aiChatGateway) throws Exception {
        writeTextbookCorpus(root);
        InMemoryKnowledgeQuestionBankStore knowledgeStore = new InMemoryKnowledgeQuestionBankStore();
        knowledgeStore.saveKnowledgePoint(new KnowledgePointRecord(
                "spine-vector-dot",
                "default",
                "seed",
                "PUBLIC_TEXTBOOK",
                "space vector dot product",
                "space vector / solid geometry",
                "active",
                "display_spine_v0.1;nodeType=TOPIC"));
        InMemoryTeacherResourceStore resourceStore = new InMemoryTeacherResourceStore();
        resourceStore.save(new TeacherResourceDocumentResponse(
                "teacher-doc-1",
                "default",
                "teacher-1",
                "feishu",
                "Space vector handout",
                "https://my.feishu.cn/docx/vector",
                null,
                "TEACHER_PRIVATE",
                "registered",
                "parsed",
                "ready",
                "ready",
                "md",
                List.of()));
        InMemoryTeacherDocumentBlockStore blockStore = new InMemoryTeacherDocumentBlockStore();
        blockStore.replaceActiveBlocks("default", "teacher-doc-1", List.of(new TeacherDocumentBlockResponse(
                "block-1",
                "teacher-doc-1",
                "docx-block-1",
                "markdown",
                1,
                "space vector",
                "dihedral angle",
                3,
                "3",
                "space vector dot product dihedral angle normal vector coordinate system",
                "space vector dot product dihedral angle normal vector coordinate system",
                "[]",
                "[]",
                "checksum-1",
                0.98,
                "active")));
        if (aiChatGateway == null) {
            return StudentExplanationServiceFixture.deterministic(
                    new TextbookResourceProperties(root),
                    com.doob.mathagent.retrieval.TextbookRetrievalServiceFixture.service(
                            new TextbookCatalogReader(),
                            new TextbookChunkReader(),
                            new LocalTextbookBm25SearchEngine(),
                            event -> {
                            }),
                    new KnowledgeGraphSpineService(knowledgeStore),
                    com.doob.mathagent.teacher.TeacherResourceBlockSearchServiceFixture.service(resourceStore, blockStore));
        }
        return StudentExplanationServiceFixture.service(
                new TextbookResourceProperties(root),
                com.doob.mathagent.retrieval.TextbookRetrievalServiceFixture.service(
                        new TextbookCatalogReader(),
                        new TextbookChunkReader(),
                        new LocalTextbookBm25SearchEngine(),
                        event -> {
                        }),
                new KnowledgeGraphSpineService(knowledgeStore),
                com.doob.mathagent.teacher.TeacherResourceBlockSearchServiceFixture.service(resourceStore, blockStore),
                aiChatGateway,
                aiProviderCatalog(),
                null,
                null);
    }

    private static StudentExplanationService serviceWithFailingTeacherResourceSearch(Path root) throws Exception {
        writeTextbookCorpus(root);
        InMemoryKnowledgeQuestionBankStore knowledgeStore = new InMemoryKnowledgeQuestionBankStore();
        knowledgeStore.saveKnowledgePoint(new KnowledgePointRecord(
                "spine-vector-dot",
                "default",
                "seed",
                "PUBLIC_TEXTBOOK",
                "space vector dot product",
                "space vector / solid geometry",
                "active",
                "display_spine_v0.1;nodeType=TOPIC"));
        InMemoryTeacherResourceStore resourceStore = new InMemoryTeacherResourceStore();
        InMemoryTeacherDocumentBlockStore blockStore = new InMemoryTeacherDocumentBlockStore();
        TeacherResourceBlockSearchService throwingSearchService = new TeacherResourceBlockSearchService(
                resourceStore,
                blockStore,
                event -> {
                },
                TestVectorIndexService.successful(resourceStore, blockStore)) {
            @Override
            public TeacherResourceBlockSearchResponse search(
                    String tenantId,
                    String viewerRole,
                    String viewerSubjectId,
                    String query,
                    int limit,
                    String endpoint) {
                throw new IllegalStateException("teacher search unavailable");
            }
        };
        return StudentExplanationServiceFixture.deterministic(
                new TextbookResourceProperties(root),
                com.doob.mathagent.retrieval.TextbookRetrievalServiceFixture.service(
                        new TextbookCatalogReader(),
                        new TextbookChunkReader(),
                        new LocalTextbookBm25SearchEngine(),
                        event -> {
                        }),
                new KnowledgeGraphSpineService(knowledgeStore),
                throwingSearchService);
    }

    /**
     * Returns a provider catalog with a configured default model for AI composition tests.
     */
    private static AiProviderCatalog aiProviderCatalog() {
        AiProviderProperties properties = new AiProviderProperties();
        properties.setOpenai(new AiProviderProperties.Provider(
                "openai",
                "https://api.openai.com",
                "test-key",
                "gpt-5.4"));
        return new AiProviderCatalog(properties);
    }

    /**
     * Writes a minimal processed_books-compatible corpus for real reader and BM25 tests.
     */
    private static void writeTextbookCorpus(Path root) throws Exception {
        Path bookRoot = root.resolve("book-vector");
        Path chunksRoot = bookRoot.resolve("jsonl_ai");
        Files.createDirectories(chunksRoot);
        Files.writeString(root.resolve("catalog.jsonl"), """
                {"doc_id":"book-vector","book_name":"Vector Textbook","volume":"selective","book_root":"book-vector","manifest":"manifest.json","chunk_count":1,"page_count":20,"ai_ok":true}
                """, StandardCharsets.UTF_8);
        Files.writeString(chunksRoot.resolve("chunks.jsonl"), """
                {"chunk_id":"chunk-vector-1","doc_id":"book-vector","book_name":"Vector Textbook","volume":"selective","chapter_path":["space vector","solid geometry"],"page_no":12,"printed_page_no":"12","chunk_type":"text","section_title":"space vector dot product","text":"space vector dot product can compute line angles, plane angles, and dihedral angles with normal vectors.","formula_text":"cos theta = (a dot b)/(|a||b|)","image_rel_paths":[],"source_page_image":"pages/book-vector-012.png"}
                """, StandardCharsets.UTF_8);
    }
}
