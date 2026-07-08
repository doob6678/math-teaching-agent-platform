package com.doob.mathagent.teaching;

import static org.assertj.core.api.Assertions.assertThat;

import com.doob.mathagent.agent.service.AgentTraceRecord;
import com.doob.mathagent.agent.service.AgentTraceSearchCriteria;
import com.doob.mathagent.agent.service.AiChatResult;
import com.doob.mathagent.agent.service.InMemoryAgentTraceStore;
import com.doob.mathagent.infrastructure.ai.AiProviderCatalog;
import com.doob.mathagent.infrastructure.ai.AiProviderProperties;
import com.doob.mathagent.knowledge.dto.QuestionBankItemCreateRequest;
import com.doob.mathagent.knowledge.service.InMemoryKnowledgeQuestionBankStore;
import com.doob.mathagent.knowledge.service.KnowledgeQuestionBankService;
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
import com.doob.mathagent.teaching.service.TeachingAiDraftProperties;
import com.doob.mathagent.teaching.service.TeachingWorkflowService;
import com.doob.mathagent.teaching.vo.TeachingTaskResponse;
import com.doob.mathagent.teacher.TeacherResourceBlockSearchServiceFixture;
import com.doob.mathagent.teacher.service.InMemoryTeacherDocumentBlockStore;
import com.doob.mathagent.teacher.service.InMemoryTeacherResourceStore;
import com.doob.mathagent.teacher.vo.TeacherDocumentBlockResponse;
import com.doob.mathagent.teacher.vo.TeacherResourceDocumentResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
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
                        "QUESTION_BANK_RETRIEVAL",
                        "TEACHER_RESOURCE_RETRIEVAL",
                        "REACT_SOLVE",
                        "HANDOUT_TEMPLATE",
                        "AI_DRAFT",
                        "LATEX_HANDOUT",
                        "HUMAN_FEEDBACK",
                        "INTERACTIVE_FOLLOW_UP");
        assertThat(response.selectedTemplate()).isNotNull();
        assertThat(response.selectedTemplate().templateCode()).isEqualTo("default_standard");
        assertThat(response.selectedTemplate().category()).isEqualTo("基础讲义");
        assertThat(response.selectedTemplate().difficultyBands()).contains("基础", "提高");
        assertThat(response.reactTrace()).isEmpty();
        assertThat(response.evidence()).isNotEmpty();
        assertThat(response.evidence().getFirst().sourceScope()).isEqualTo("PUBLIC_TEXTBOOK");
        assertThat(response.handoutLatex()).contains("\\section{课前定位}");
        assertThat(response.teacherHandoutLatex()).contains(
                "\\section{课前定位}",
                "模板：",
                "\\section{讲评主线}",
                "\\section{核心公式与方法卡}",
                "\\section{典型例题与讲评入口}",
                "\\section{16:10 横版讲解卡}",
                "\\section{板书与二次反馈}");
        assertThat(response.lectureHandoutLatex())
                .contains("\\section{16:10 横版讲解卡}", "\\vspace")
                .doesNotContain("\\section{答案与评分点}", "教师手写区", "手写区", "板书留白", "MODEL_CALL", "JSON_PARSE", "tokens=");
        assertThat(response.teacherHandoutLatex()).contains(
                "来源 1",
                "公开教材",
                "用途：知识点定位与公式依据");
        assertThat(response.teacherHandoutLatex()).doesNotContain(
                "PDF 版式要求", "页眉展示主题和版本", "页脚展示页码", "页眉", "页脚", "版式", "颜色", "系统说明");
        assertThat(response.teacherHandoutLatex()).doesNotContain(
                "![p", "## 正文", "书名：", "formula_text", "source_page_image", "D(x_0)=\\{d");
        assertThat(response.studentHandoutLatex()).contains(
                "\\section{知识速记}",
                "\\section{连续编号练习}",
                "\\section{注意}");
        assertThat(response.studentHandoutLatex()).doesNotContain("\\section{第 1 讲");
        assertThat(response.teacherHandoutLatex()).contains("\\subsection*{方法步骤}");
        assertThat(response.studentHandoutLatex()).doesNotContain("\\section{我的解答}", "\\section{订正记录}", "\\vspace{12em}");
        assertThat(response.studentHandoutLatex()).doesNotContain("版本：学生版", "页眉", "页脚", "颜色");
        assertThat(response.studentHandoutLatex()).doesNotContain("知识点归属");
        assertThat(response.interactiveSuggestions()).contains("继续追问定义 D(x_0)");
        assertThat(response.aiDraft().enabled()).isFalse();
        assertThat(response.teacherHandoutLatex()).doesNotContain("AI生成状态", "AI 讲义草稿");
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
    void teacherQuestionBankEvidenceCreatesStudentSafePracticeAndTeacherAnswers() throws Exception {
        Path root = createTextbookCorpus();
        KnowledgeQuestionBankService questionBankService = new KnowledgeQuestionBankService(new InMemoryKnowledgeQuestionBankStore());
        questionBankService.createQuestion(
                "tenant-a",
                "teacher",
                "teacher-1",
                new QuestionBankItemCreateRequest(
                        "双曲线定义与参数关系基础题",
                        "已知双曲线焦距为 $10$，且 $2a=6$，求 $a,c,b^2$。",
                        "{\"answer\":\"a=3,c=5,b^2=16\","
                                + "\"steps\":[\"由 2a=6 得 a=3\",\"由焦距 10 得 c=5\"],"
                                + "\"scoring\":{\"formula\":\"写出参数关系得分\"},"
                                + "\"extraNote\":\"注意 b^2 不是 b\"}",
                        "A 基础",
                        "tenant",
                        List.of()));
        InMemoryTeachingTaskStore taskStore = new InMemoryTeachingTaskStore();
        TeachingWorkflowService service = new TeachingWorkflowService(
                root,
                retrievalService(),
                taskStore,
                memoryReuseService(),
                TeachingAiDraftServiceFixture.disabled(),
                new InMemoryAgentTraceStore(),
                new com.doob.mathagent.teaching.service.TeachingHandoutTemplateService(),
                Optional.of(questionBankService),
                Runnable::run);

        TeachingRequestContext context = new TeachingRequestContext("tenant-a", "teacher", "teacher-1", "device-1");
        TeachingTaskResponse created = service.submit(
                new TeachingTaskRequest("req-question-bank-handout", "双曲线参数怎么求", "双曲线定义与参数关系", 3),
                context);
        TeachingTaskResponse response = service.get(created.taskId(), context).orElseThrow();

        assertThat(response.evidence())
                .anySatisfy(item -> assertThat(item.sourceScope()).isEqualTo("QUESTION_BANK"));
        assertThat(response.teacherHandoutLatex())
                .contains("A ", "双曲线定义与参数关系基础题", "$c=5$", "$b^2=16$",
                        "步骤：1. 由 $2a=6$ 得 $a=3$", "评分点：补充1：写出参数关系得分",
                        "补充1：注意 $b^2$ 不是 b", "课前定位", "易错提醒")
                .doesNotContain("\"answer\"", "\"steps\"", "\"scoring\"", "\"extraNote\"",
                        "双曲线定义与参数关系基础题 / 难度：A 基础", "教师版保留完整答案");
        assertThat(response.studentHandoutLatex())
                .contains("\\section{", "A ", "\\vspace{6em}")
                .doesNotContain("作答区", "手写区", "留白区", "推导区", "板书区");
        assertThat(response.studentHandoutLatex())
                .doesNotContain("答案要点", "answer", "steps", "extraNote", "c=5", "scoring", "评分", "得分");
    }

    @Test
    void teachingTaskExpandsNaturalTopicWhenSearchingQuestionBank() throws Exception {
        Path root = createTextbookCorpus();
        KnowledgeQuestionBankService questionBankService = new KnowledgeQuestionBankService(new InMemoryKnowledgeQuestionBankStore());
        questionBankService.createQuestion(
                "tenant-a",
                "admin",
                "admin-1",
                new QuestionBankItemCreateRequest(
                        "赵礼显数学 四棱柱线面角基础题",
                        "如图，在四棱柱中求线面角，并说明垂直关系。",
                        "{\"answer\":\"建立空间直角坐标系，求法向量后计算夹角\"}",
                        "medium",
                        "MATH_VIP",
                        List.of()));
        TeachingWorkflowService service = new TeachingWorkflowService(
                root,
                retrievalService(),
                new InMemoryTeachingTaskStore(),
                memoryReuseService(),
                TeachingAiDraftServiceFixture.disabled(),
                new InMemoryAgentTraceStore(),
                new com.doob.mathagent.teaching.service.TeachingHandoutTemplateService(),
                Optional.of(questionBankService),
                Runnable::run);

        TeachingTaskResponse created = service.submit(
                new TeachingTaskRequest("req-space-vector-qbank", "生成空间向量大题讲义", "学会空间向量线面角大题", 3),
                new TeachingRequestContext("tenant-a", "teacher", "teacher-1", "device-1"));
        TeachingTaskResponse response = service.get(
                created.taskId(),
                new TeachingRequestContext("tenant-a", "teacher", "teacher-1", "device-1")).orElseThrow();

        assertThat(response.evidence())
                .anySatisfy(item -> {
                    assertThat(item.sourceScope()).isEqualTo("QUESTION_BANK");
                    assertThat(item.sourceTitle()).contains("四棱柱线面角基础题");
                });
        assertThat(response.teacherHandoutLatex()).contains("四棱柱线面角基础题");
        assertThat(response.studentHandoutLatex()).contains("四棱柱");
    }

    @Test
    void teacherResourceEvidenceBackfillsHandoutWhenQuestionBankHasNoMatch() throws Exception {
        Path root = createTextbookCorpus();
        InMemoryTeacherResourceStore resourceStore = new InMemoryTeacherResourceStore();
        InMemoryTeacherDocumentBlockStore blockStore = new InMemoryTeacherDocumentBlockStore();
        resourceStore.save(teacherResourceDocument(
                "teacher-doc-hyperbola",
                "teacher-1",
                "MATH_VIP",
                "圆锥曲线专题讲义"));
        blockStore.replaceActiveBlocks("tenant-a", "teacher-doc-hyperbola", List.of(
                teacherBlock(
                        "hyperbola-block-1",
                        "teacher-doc-hyperbola",
                        1,
                        "圆锥曲线",
                        "双曲线",
                        12,
                        "双曲线定义与渐近线：到两个定点距离差的绝对值为常数；渐近线可以辅助判断图形与解题方向。")));
        TeachingWorkflowService service = new TeachingWorkflowService(
                root,
                retrievalService(),
                new InMemoryTeachingTaskStore(),
                memoryReuseService(),
                TeachingAiDraftServiceFixture.disabled(),
                new InMemoryAgentTraceStore(),
                new com.doob.mathagent.teaching.service.TeachingHandoutTemplateService(),
                Optional.empty(),
                Optional.of(TeacherResourceBlockSearchServiceFixture.service(resourceStore, blockStore)),
                Runnable::run);

        TeachingTaskResponse created = service.submit(
                new TeachingTaskRequest("req-teacher-resource-handout", "", "双曲线定义与渐近线", 3),
                new TeachingRequestContext("tenant-a", "teacher", "teacher-1", "device-1"));
        TeachingTaskResponse response = service.get(
                created.taskId(),
                new TeachingRequestContext("tenant-a", "teacher", "teacher-1", "device-1")).orElseThrow();

        assertThat(response.evidence())
                .anySatisfy(item -> {
                    assertThat(item.sourceScope()).isEqualTo("TEACHER_RESOURCE");
                    assertThat(item.sourceTitle()).contains("圆锥曲线专题讲义", "双曲线");
                });
        assertThat(response.nodes())
                .filteredOn(node -> "TEACHER_RESOURCE_RETRIEVAL".equals(node.code()))
                .singleElement()
                .satisfies(node -> assertThat(node.summary()).contains("命中教师资料证据 1 条"));
        assertThat(response.teacherHandoutLatex())
                .contains("教师资料", "圆锥曲线专题讲义 / 圆锥曲线 / 双曲线", "题型方法、教师沉淀与讲义补充")
                .doesNotContain("source_page_image", "## 正文");
        assertThat(response.studentHandoutLatex())
                .doesNotContain("双曲线定义与渐近线：到两个定点距离差的绝对值为常数", "渐近线可以辅助判断图形与解题方向");
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
                          "teacherExplanation": "【知识定位】先读清 $D(x_0)$ 的定义。\\nPDF 版式要求：页眉展示主题和版本，页脚展示页码，教师版使用讲评色。\\n【方法步骤】1. 写出定义中的自变量位置。\\n2. 用 $$c^2=a^2+b^2$$ 这类参数关系示范公式排版，再把 -1 代入。\\n【答案与评分点】关键是代入位置与定义域检查。",
                          "studentHint": "【知识速记】先找到 $D(x_0)$ 里的自变量位置，记住 c²=a²+b² 这类公式要先写清。\\n【答案与评分点】答案：把 -1 代入即可得分。\\nJSON_PARSE_SUCCEEDED tokens=34\\n【练习任务】- 先写出定义：___\\n- 独立完成 D(0)：___",
                          "knowledgePoints": ["函数新定义", "定义域", "参数关系 c²=a²+b²", "MODEL_CALL_SUCCEEDED tokens=34"],
                          "followUpQuestions": ["D(0) 如何处理？", "条件变化时如何分类？", "参考答案：D(0)=1"]
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
        assertThat(trace.evidenceRefs()).isNotNull();
        assertThat(trace.message()).contains("Teaching AI draft structured");
        assertThat(trace.diagnosticEvents()).extracting(AgentTraceRecord.DiagnosticEvent::eventType)
                .containsExactly("MODEL_CALL_SUCCEEDED", "JSON_PARSE_SUCCEEDED");
        assertThat(response.teacherHandoutLatex()).contains("典型例题与讲评入口", "核心公式与方法卡", "易错提醒与课堂追问", "16:10 横版讲解卡", "板书与二次反馈");
        assertThat(response.lectureHandoutLatex())
                .contains("16:10 横版讲解卡", "\\vspace")
                .doesNotContain("教师手写区", "手写区", "板书留白");
        assertThat(response.teacherHandoutLatex()).contains("$D(x_0)$", "$$c^2=a^2+b^2$$", "$c^2=a^2+b^2$");
        assertThat(response.teacherHandoutLatex()).contains("\\begin{enumerate}", "\\item 写出定义中的自变量位置");
        assertThat(response.teacherHandoutLatex()).doesNotContain("\\$D", "c\\textasciicircum{}2");
        assertThat(response.studentHandoutLatex()).contains("\\section{连续编号练习}");
        assertThat(response.studentHandoutLatex()).doesNotContain("\\section{第 1 讲");
        assertThat(response.studentHandoutLatex()).doesNotContain("\\section{我的解答}", "\\section{订正记录}", "\\vspace{12em}");
        assertThat(response.studentHandoutLatex()).contains("\\section{知识速记}", "\\section{例题任务}", "$D(x_0)$", "$c^2=a^2+b^2$");
        assertThat(response.studentHandoutLatex()).contains("\\begin{itemize}", "\\underline{\\hspace{4em}}");
        assertThat(response.studentHandoutLatex()).doesNotContain("【答案与评分点】", "答案：", "得分", "___");
        assertThat(response.teacherHandoutLatex())
                .doesNotContain("tokens=", "\\paragraph{模型}", "PDF 版式要求", "页眉", "页脚", "讲评色", "MODEL_CALL", "JSON_PARSE");
        assertThat(response.studentHandoutLatex())
                .doesNotContain("tokens=", "PDF 版式要求", "页眉", "页脚", "讲评色", "MODEL_CALL", "JSON_PARSE", "参考答案");
        assertThat(response.nodes())
                .filteredOn(node -> "AI_DRAFT".equals(node.code()))
                .singleElement()
                .satisfies(node -> assertThat(node.summary())
                        .contains("人工审校")
                        .doesNotContain("当前模型", "重试", "诊断事件", "gpt", "qwen", "tokens"));
    }

    @Test
    void normalizesSlashFractionsAndKeepsStudentWorksheetDense() throws Exception {
        Path root = createTextbookCorpus();
        AiProviderProperties properties = new AiProviderProperties();
        properties.getOpenai().setApiKey("test-openai-key");
        TeachingAiDraftService aiDraftService = new TeachingAiDraftService(
                request -> new AiChatResult("openai", "gpt-5.4", 30, 24, 54, "ok", """
                        {
                          "teacherExplanation": "【知识定位】反比例函数通常写作 $y=k/x$，其中 $k\\ne 0$。\\n【题型识别】看到图像上一点就代入解析式。\\n【方法步骤】1. 设 $y=k/x$。\\n2. 代入点坐标求 $k$。\\n【例题详解】已知点在图像上，代入 $y=k/x$ 建立方程。\\n【答案与评分点】教师版保留 $y=k/x$ 的代入方程和最终答案。\\n【易错提醒】不要把 k 的符号判断反。\\n【课堂追问】若点在第三象限，k 的符号如何判断？",
                          "studentHint": "【知识速记】反比例函数可写为 $y=k/x$，先判断 $k\\ne 0$。\\n【题型识别】看到点坐标就尝试代入。\\n【例题任务】已知一点在图像上，先写出解析式空格。\\n【练习任务】1. 写出 $y=k/x$ 的定义式。\\n2. 判断点是否在图像上。\\n【作答提醒】先写公式，再代入。",
                          "knowledgePoints": ["反比例函数 $y=k/x$", "k 的几何意义", "点在图像上就满足解析式"],
                          "followUpQuestions": ["基础：写出 $y=k/x$ 的适用条件。", "提高：给一点坐标，列出求 k 的方程。"]
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
                new TeachingTaskRequest("req-normalize-fraction", "", "反比例函数从概念到基础题型", 2),
                new TeachingRequestContext("tenant-a", "teacher", "teacher-1", "device-1"));

        assertThat(response.teacherHandoutLatex())
                .contains("$y=\\frac{k}{x}$")
                .doesNotContain("$y=k/x$");
        assertThat(response.studentHandoutLatex())
                .contains("$y=\\frac{k}{x}$")
                .doesNotContain("$y=k/x$", "教师版保留", "最终答案", "答案与评分点");
        assertThat(response.studentHandoutLatex().split("\\\\item ", -1).length - 1)
                .isGreaterThanOrEqualTo(6);
    }

    private TeachingWorkflowService service(Path root) {
        return service(root, memoryReuseService());
    }

    private TeachingWorkflowService service(Path root, StudentMemoryReuseService memoryReuseService) {
        return new TeachingWorkflowService(
                root,
                retrievalService(),
                new InMemoryTeachingTaskStore(),
                memoryReuseService,
                TeachingAiDraftServiceFixture.disabled(),
                new InMemoryAgentTraceStore());
    }

    private TextbookRetrievalService retrievalService() {
        return com.doob.mathagent.retrieval.TextbookRetrievalServiceFixture.service(
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

    private static TeacherResourceDocumentResponse teacherResourceDocument(
            String documentId,
            String ownerSubjectId,
            String permissionScope,
            String title) {
        return new TeacherResourceDocumentResponse(
                documentId,
                "tenant-a",
                ownerSubjectId,
                "local_path",
                title,
                null,
                "C:/math/" + documentId,
                permissionScope,
                "synced",
                "parsed",
                "embedded",
                "ready",
                List.of());
    }

    private static TeacherDocumentBlockResponse teacherBlock(
            String blockId,
            String documentId,
            int blockOrder,
            String chapter,
            String section,
            Integer pageNo,
            String text) {
        return new TeacherDocumentBlockResponse(
                blockId,
                documentId,
                documentId + ":" + blockOrder,
                "text",
                blockOrder,
                chapter,
                section,
                pageNo,
                pageNo == null ? null : pageNo.toString(),
                text,
                text.toLowerCase(),
                "[]",
                "[]",
                blockId + "-checksum",
                1.0,
                "active");
    }

    private static String escape(Path path) {
        return path.toString().replace("\\", "\\\\");
    }
}
