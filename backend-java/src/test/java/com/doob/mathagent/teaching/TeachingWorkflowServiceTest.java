package com.doob.mathagent.teaching;

import static org.assertj.core.api.Assertions.assertThat;

import com.doob.mathagent.agent.service.AgentTraceRecord;
import com.doob.mathagent.agent.service.AgentTraceSearchCriteria;
import com.doob.mathagent.agent.service.AiChatResult;
import com.doob.mathagent.agent.service.InMemoryAgentTraceStore;
import com.doob.mathagent.infrastructure.ai.AiProviderCatalog;
import com.doob.mathagent.infrastructure.ai.AiProviderProperties;
import com.doob.mathagent.memory.service.InMemoryStudentMemoryStore;
import com.doob.mathagent.memory.service.StudentMemoryCommand;
import com.doob.mathagent.memory.service.StudentMemoryReuseService;
import com.doob.mathagent.resources.TextbookCatalogReader;
import com.doob.mathagent.resources.TextbookChunkReader;
import com.doob.mathagent.retrieval.LocalTextbookBm25SearchEngine;
import com.doob.mathagent.retrieval.NoopRetrievalAuditSink;
import com.doob.mathagent.retrieval.TextbookRetrievalService;
import com.doob.mathagent.teaching.dto.TeachingTaskRequest;
import com.doob.mathagent.teaching.service.InMemoryTeachingTaskStore;
import com.doob.mathagent.teaching.service.TeachingAiDraftService;
import com.doob.mathagent.teaching.service.TeachingWorkflowService;
import com.doob.mathagent.teaching.vo.TeachingTaskResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TeachingWorkflowServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void completesTeachingTaskWithDagReactTraceEvidenceAndHandout() throws Exception {
        Path root = createTextbookCorpus();
        TeachingWorkflowService service = service(root);
        TeachingTaskRequest request = new TeachingTaskRequest(
                "req-001",
                "已知函数 f(x) 的定义域为 R，求 D(-1)",
                "我想学会函数新概念综合题",
                3);
        TeachingRequestContext context = new TeachingRequestContext(
                "tenant-a",
                "student",
                "student-1",
                "device-1");

        TeachingTaskResponse response = service.submit(request, context);

        assertThat(response.status()).isEqualTo(TeachingTaskStatus.COMPLETED);
        assertThat(response.tenantId()).isEqualTo("tenant-a");
        assertThat(response.subjectId()).isEqualTo("student-1");
        assertThat(response.nodes())
                .extracting(TeachingWorkflowNode::code)
                .containsExactly(
                        "LEARNING_GOAL",
                        "REUSE_RESOURCE",
                        "PUBLIC_TEXTBOOK_RETRIEVAL",
                        "PRIVATE_FEISHU_PLACEHOLDER",
                        "PRACTICE_DISCOVERY_PLACEHOLDER",
                        "REACT_SOLVE",
                        "AI_DRAFT",
                        "LATEX_HANDOUT",
                        "HUMAN_FEEDBACK",
                        "INTERACTIVE_FOLLOW_UP");
        assertThat(response.reactTrace())
                .extracting(TeachingReactStep::phase)
                .containsExactly("THOUGHT", "ACTION", "OBSERVATION", "ANSWER");
        assertThat(response.evidence()).isNotEmpty();
        assertThat(response.evidence().getFirst().sourceScope()).isEqualTo("PUBLIC_TEXTBOOK");
        assertThat(response.handoutLatex()).contains("\\section{学习目标}");
        assertThat(response.teacherHandoutLatex()).contains("\\section{教师版}", "\\section{知识点归属}");
        assertThat(response.studentHandoutLatex()).contains("\\section{学生版}", "\\vspace");
        assertThat(response.studentHandoutLatex()).doesNotContain("知识点归属");
        assertThat(response.interactiveSuggestions()).contains("继续追问定义 D(x_0)");
        assertThat(response.aiDraft().enabled()).isFalse();
        assertThat(response.teacherHandoutLatex()).contains("AI生成状态");
        assertThat(response.memoryReuse().reused()).isFalse();
        assertThat(response.stageTimings()).extracting(TeachingTaskResponse.StageTiming::stage)
                .contains("memory_reuse", "textbook_retrieval", "react_trace", "ai_draft", "handout_generation");
    }

    @Test
    void reusesStudentMemoryBeforeTextbookRetrievalWhenSimilarAnswerExists() throws Exception {
        Path root = createTextbookCorpus();
        StudentMemoryReuseService memoryReuseService = memoryReuseService();
        memoryReuseService.remember(new StudentMemoryCommand(
                "tenant-a",
                "student",
                "student-1",
                "空间向量数量积怎么求夹角",
                "先用 a·b=|a||b|cosθ 求 cosθ，再根据角度范围确定夹角。",
                "空间向量数量积",
                "private",
                false));
        TeachingWorkflowService service = service(root, memoryReuseService);
        TeachingTaskRequest request = new TeachingTaskRequest(
                "req-memory-hit",
                "空间向量数量积求夹角的方法",
                "空间向量数量积",
                3);
        TeachingRequestContext context = new TeachingRequestContext(
                "tenant-a",
                "student",
                "student-1",
                "device-1");

        TeachingTaskResponse response = service.submit(request, context);

        assertThat(response.memoryReuse().reused()).isTrue();
        assertThat(response.memoryReuse().reuseScope()).isEqualTo("private");
        assertThat(response.memoryReuse().answer()).contains("cosθ");
        assertThat(response.evidence()).isEmpty();
        assertThat(response.nodes())
                .filteredOn(node -> "REUSE_RESOURCE".equals(node.code()))
                .extracting(TeachingWorkflowNode::summary)
                .first()
                .asString()
                .contains("命中学生记忆");
        assertThat(response.stageTimings()).extracting(TeachingTaskResponse.StageTiming::stage)
                .contains("memory_reuse", "reuse_short_circuit", "react_trace", "ai_draft", "handout_generation");
    }

    @Test
    void reusesExistingTaskWhenClientRequestIdIsRepeatedForSameSubject() throws Exception {
        Path root = createTextbookCorpus();
        TeachingWorkflowService service = service(root);
        TeachingTaskRequest request = new TeachingTaskRequest(
                "req-repeat",
                "分段函数如何理解",
                "复习分段函数",
                2);
        TeachingRequestContext context = new TeachingRequestContext(
                "tenant-a",
                "teacher",
                "teacher-1",
                "device-1");

        TeachingTaskResponse first = service.submit(request, context);
        TeachingTaskResponse second = service.submit(request, context);
        TeachingTaskResponse loaded = service.get(first.taskId(), context).orElseThrow();

        assertThat(second.taskId()).isEqualTo(first.taskId());
        assertThat(loaded.taskId()).isEqualTo(first.taskId());
        assertThat(loaded.status()).isEqualTo(TeachingTaskStatus.COMPLETED);
    }

    @Test
    void isolatesTaskLookupByTenantAndSubject() throws Exception {
        Path root = createTextbookCorpus();
        TeachingWorkflowService service = service(root);
        TeachingRequestContext owner = new TeachingRequestContext("tenant-a", "student", "student-1", "device-1");
        TeachingRequestContext other = new TeachingRequestContext("tenant-a", "student", "student-2", "device-2");

        TeachingTaskResponse created = service.submit(
                new TeachingTaskRequest("req-private", "分段函数", "学函数", 2),
                owner);

        assertThat(service.get(created.taskId(), other)).isEmpty();
    }

    @Test
    void storesCoursewareAgentTraceForRealAiDraftRuns() throws Exception {
        Path root = createTextbookCorpus();
        InMemoryAgentTraceStore traceStore = new InMemoryAgentTraceStore();
        AiProviderProperties properties = new AiProviderProperties();
        properties.getOpenai().setApiKey("test-openai-key");
        TeachingAiDraftService aiDraftService = new TeachingAiDraftService(
                request -> new AiChatResult("openai", "gpt-5.4", 21, 13, 34, "ok", """
                        {
                          "teacherExplanation": "先读清 D(x_0) 的定义，再把 -1 代入。",
                          "studentHint": "先找到定义里的自变量位置。",
                          "knowledgePoints": ["函数新定义", "定义域"],
                          "followUpQuestions": ["D(0) 如何处理？", "条件变化时如何分类？"]
                        }
                        """),
                new AiProviderCatalog(properties));
        TeachingWorkflowService service = new TeachingWorkflowService(
                root,
                retrievalService(),
                new InMemoryTeachingTaskStore(),
                memoryReuseService(),
                aiDraftService,
                traceStore);

        TeachingTaskResponse response = service.submit(
                new TeachingTaskRequest("req-ai-trace", "已知函数 f(x) 的定义域为 R，求 D(-1)", "理解函数新定义题", 2),
                new TeachingRequestContext("tenant-a", "teacher", "teacher-1", "device-1"));

        List<AgentTraceRecord> traces = traceStore.search(new AgentTraceSearchCriteria(
                "tenant-a", "teacher", "teacher-1", "CoursewareAgent", "COMPLETED", 10));

        assertThat(traces).hasSize(1);
        AgentTraceRecord trace = traces.getFirst();
        assertThat(trace.planId()).isEqualTo(response.taskId());
        assertThat(trace.actualUsage().totalTokens()).isEqualTo(34);
        assertThat(trace.evidenceRefs()).anyMatch(ref -> ref.contains("PUBLIC_TEXTBOOK"));
        assertThat(trace.message()).contains("Teaching AI draft structured");
        assertThat(trace.diagnosticEvents()).extracting(AgentTraceRecord.DiagnosticEvent::eventType)
                .containsExactly("MODEL_CALL_SUCCEEDED", "JSON_PARSE_SUCCEEDED");
    }

    private TeachingWorkflowService service(Path root) {
        return service(root, memoryReuseService());
    }

    private TeachingWorkflowService service(Path root, StudentMemoryReuseService memoryReuseService) {
        return new TeachingWorkflowService(root, retrievalService(), new InMemoryTeachingTaskStore(), memoryReuseService);
    }

    private TextbookRetrievalService retrievalService() {
        return new TextbookRetrievalService(
                new TextbookCatalogReader(),
                new TextbookChunkReader(),
                new LocalTextbookBm25SearchEngine(),
                new NoopRetrievalAuditSink());
    }

    private StudentMemoryReuseService memoryReuseService() {
        return new StudentMemoryReuseService(new InMemoryStudentMemoryStore());
    }

    private Path createTextbookCorpus() throws Exception {
        Path root = tempDir.resolve("processed_books");
        Path bookRoot = root.resolve("book_a");
        Files.createDirectories(bookRoot.resolve("jsonl"));
        Files.writeString(root.resolve("catalog.jsonl"), """
                {"doc_id":"book_a","book_name":"教材A","volume":"必修 第一册","book_root":"%s","manifest":"%s","chunk_count":2,"page_count":2,"ai_ok":false}
                """.formatted(escape(bookRoot), escape(bookRoot.resolve("manifest.json"))));
        Files.writeString(bookRoot.resolve("jsonl/chunks.jsonl"), """
                {"chunk_id":"book_a_p101_text_001","doc_id":"book_a","book_name":"教材A","volume":"必修 第一册","chapter_path":["第三章 函数"],"page_no":101,"printed_page_no":"98","chunk_type":"page_summary","section_title":"函数新概念","text":"函数新概念 分段函数 定义域 值域 集合 D x0 增量 d","formula_text":"D(x_0)=\\\\{d\\\\in R|f(x_0+d)>f(x_0)\\\\}","image_rel_paths":[],"source_page_image":"pages/p101.png"}
                {"chunk_id":"book_a_p102_text_001","doc_id":"book_a","book_name":"教材A","volume":"必修 第一册","chapter_path":["第三章 函数"],"page_no":102,"printed_page_no":"99","chunk_type":"page_summary","section_title":"分段函数","text":"分段函数 在不同区间用不同解析式表达，需要分类讨论。","formula_text":"","image_rel_paths":[],"source_page_image":"pages/p102.png"}
                """);
        return root;
    }

    private static String escape(Path path) {
        return path.toString().replace("\\", "\\\\");
    }
}
