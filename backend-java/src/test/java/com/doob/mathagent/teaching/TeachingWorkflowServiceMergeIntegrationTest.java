package com.doob.mathagent.teaching;

import static org.assertj.core.api.Assertions.assertThat;

import com.doob.mathagent.agent.service.AiChatResult;
import com.doob.mathagent.agent.service.InMemoryAgentTraceStore;
import com.doob.mathagent.infrastructure.ai.AiProviderCatalog;
import com.doob.mathagent.infrastructure.ai.AiProviderProperties;
import com.doob.mathagent.memory.service.StudentMemoryReuseService;
import com.doob.mathagent.resources.TextbookCatalogReader;
import com.doob.mathagent.resources.TextbookChunkReader;
import com.doob.mathagent.retrieval.LocalTextbookBm25SearchEngine;
import com.doob.mathagent.retrieval.NoopRetrievalAuditSink;
import com.doob.mathagent.retrieval.TextbookRetrievalService;
import com.doob.mathagent.teaching.dto.TeachingTaskRequest;
import com.doob.mathagent.teaching.service.InMemoryTeachingTaskStore;
import com.doob.mathagent.teaching.service.TeachingAiDraftProperties;
import com.doob.mathagent.teaching.service.TeachingAiDraftService;
import com.doob.mathagent.teaching.service.TeachingWorkflowService;
import com.doob.mathagent.teaching.vo.TeachingTaskResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TeachingWorkflowServiceMergeIntegrationTest {

    @TempDir
    Path tempDir;

    @Test
    void rendersLectureHandoutFromTheVerifiedQuestionInsteadOfInventingFollowUpSlides() throws Exception {
        Path root = createTextbookCorpus();
        AiProviderProperties properties = new AiProviderProperties();
        properties.getOpenai().setApiKey("test-openai-key");
        TeachingAiDraftService aiDraftService = new TeachingAiDraftService(
                request -> new AiChatResult("openai", "gpt-5.4", 24, 18, 42, "ok", """
                        {
                          "teacherExplanation": "【知识定位】函数定义域：先判断条件，再确定方法。\\n【题型识别】函数定义题要抓住定义域条件；pick method; prepare follow-up.\\n【方法步骤】first condition; second method; third follow up.\\n【例题详解】围绕函数定义域展开。\\n【答案与评分点】teacher only.\\n【易错提醒】watch the domain.\\n【课堂追问】how should the condition change?",
                          "studentHint": "【知识速记】函数定义域先看限制条件。\\n【题型识别】classify the task.\\n【例题任务】拆解步骤。\\n【练习任务】1. 写第一步。\\n【作答提醒】先判断再代入。",
                          "knowledgePoints": ["函数定义域", "定义域条件判断"],
                          "followUpQuestions": ["函数定义域条件变化后如何判断？"]
                        }
                        """),
                new AiProviderCatalog(properties),
                new TeachingAiDraftProperties());
        TeachingWorkflowService service = new TeachingWorkflowService(
                root,
                retrievalService(),
                new InMemoryTeachingTaskStore(),
                memoryReuseService(),
                aiDraftService,
                new InMemoryAgentTraceStore());

        TeachingTaskResponse response = service.submit(
                new TeachingTaskRequest("req-merge-lecture", "已知函数定义域，分析题型并完成讲评", "函数定义题型拆解", 2),
                new TeachingRequestContext("tenant-a", "teacher", "teacher-1", "device-1"));
        assertThat(response.lectureHandoutLatex())
                .contains("first condition", "second method", "third follow up")
                // A model follow-up is not a sourced exam item.  The 16:10 version must keep one verified prompt
                // per page rather than manufacturing a second slide merely because the JSON has a follow-up list.
                .contains("\\subsection*{第 1 题：例题}")
                .doesNotContain("\\subsection*{第 2 题 / 讲解单元}");
    }

    private StudentMemoryReuseService memoryReuseService() {
        return new StudentMemoryReuseService(new com.doob.mathagent.memory.service.InMemoryStudentMemoryStore());
    }

    private TextbookRetrievalService retrievalService() {
        return com.doob.mathagent.retrieval.TextbookRetrievalServiceFixture.service(
                new TextbookCatalogReader(),
                new TextbookChunkReader(),
                new LocalTextbookBm25SearchEngine(),
                new NoopRetrievalAuditSink());
    }

    private Path createTextbookCorpus() throws Exception {
        Path root = tempDir.resolve("processed_books");
        Path bookDir = root.resolve("book_a");
        Files.createDirectories(bookDir.resolve("jsonl"));
        Files.writeString(
                root.resolve("catalog.jsonl"),
                """
                {"doc_id":"book_a","book_name":"教材A","volume":"必修 第一册","book_root":"%s","manifest":"%s","chunk_count":1,"page_count":1,"ai_ok":false}
                """.formatted(bookDir.toString().replace("\\", "\\\\"), bookDir.resolve("manifest.json").toString().replace("\\", "\\\\")));
        Files.writeString(
                bookDir.resolve("jsonl/chunks.jsonl"),
                """
                {"chunk_id":"book_a_p101_text_001","doc_id":"book_a","book_name":"教材A","volume":"必修 第一册","chapter_path":["第三章 函数"],"page_no":101,"printed_page_no":"99","chunk_type":"page_summary","section_title":"函数定义","text":"定义域题通常先判断条件再定位方法。","formula_text":"","image_rel_paths":[],"source_page_image":"pages/p101.png"}
                """);
        return root;
    }
}
