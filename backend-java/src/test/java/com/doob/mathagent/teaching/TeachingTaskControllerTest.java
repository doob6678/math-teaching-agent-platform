package com.doob.mathagent.teaching;

import static org.assertj.core.api.Assertions.assertThat;

import com.doob.mathagent.infrastructure.security.RequestSubjectResolver;
import com.doob.mathagent.memory.service.InMemoryStudentMemoryStore;
import com.doob.mathagent.memory.service.StudentMemoryReuseService;
import com.doob.mathagent.resources.TextbookCatalogReader;
import com.doob.mathagent.resources.TextbookChunkReader;
import com.doob.mathagent.retrieval.LocalTextbookBm25SearchEngine;
import com.doob.mathagent.retrieval.NoopRetrievalAuditSink;
import com.doob.mathagent.retrieval.TextbookRetrievalService;
import com.doob.mathagent.teaching.controller.TeachingTaskController;
import com.doob.mathagent.teaching.dto.TeachingTaskRequest;
import com.doob.mathagent.teaching.service.InMemoryTeachingTaskStore;
import com.doob.mathagent.teaching.service.TeachingWorkflowService;
import com.doob.mathagent.teaching.vo.TeachingTaskResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TeachingTaskControllerTest {

    @TempDir
    Path tempDir;

    @Test
    void exposesSubmitAndResumeContractForTeachingTask() throws Exception {
        Path root = createTextbookCorpus();
        TextbookRetrievalService retrievalService = new TextbookRetrievalService(
                new TextbookCatalogReader(),
                new TextbookChunkReader(),
                new LocalTextbookBm25SearchEngine(),
                new NoopRetrievalAuditSink());
        TeachingWorkflowService service = new TeachingWorkflowService(
                root,
                retrievalService,
                new InMemoryTeachingTaskStore(),
                new StudentMemoryReuseService(new InMemoryStudentMemoryStore()));
        TeachingTaskController controller = new TeachingTaskController(service, RequestSubjectResolver.localDevelopment());
        TeachingTaskRequest request = new TeachingTaskRequest(
                "client-001",
                "我想学 D(-1) 怎么求",
                "理解函数新定义题",
                3);

        TeachingTaskResponse submitted = controller.submit(request, null);
        TeachingTaskResponse loaded = controller.get(submitted.taskId(), null);

        assertThat(loaded.taskId()).isEqualTo(submitted.taskId());
        assertThat(loaded.status()).isEqualTo(TeachingTaskStatus.COMPLETED);
        assertThat(loaded.handoutLatex()).contains("\\section{证据与讲解}");
    }

    private Path createTextbookCorpus() throws Exception {
        Path root = tempDir.resolve("processed_books");
        Path bookRoot = root.resolve("book_a");
        Files.createDirectories(bookRoot.resolve("jsonl"));
        Files.writeString(root.resolve("catalog.jsonl"), """
                {"doc_id":"book_a","book_name":"教材A","volume":"必修 第一册","book_root":"%s","manifest":"%s","chunk_count":1,"page_count":1,"ai_ok":false}
                """.formatted(escape(bookRoot), escape(bookRoot.resolve("manifest.json"))));
        Files.writeString(bookRoot.resolve("jsonl/chunks.jsonl"), """
                {"chunk_id":"book_a_p101_text_001","doc_id":"book_a","book_name":"教材A","volume":"必修 第一册","chapter_path":["第三章 函数"],"page_no":101,"printed_page_no":"98","chunk_type":"page_summary","section_title":"函数新概念","text":"函数新概念 分段函数 定义域 值域 集合 D x0 增量 d","formula_text":"D(x_0)=\\\\{d\\\\in R|f(x_0+d)>f(x_0)\\\\}","image_rel_paths":[],"source_page_image":"pages/p101.png"}
                """);
        return root;
    }

    private static String escape(Path path) {
        return path.toString().replace("\\", "\\\\");
    }
}
