package com.doob.mathagent.teaching;

import static org.assertj.core.api.Assertions.assertThat;

import com.doob.mathagent.agent.service.AgentTraceRecord;
import com.doob.mathagent.agent.service.AgentTraceSearchCriteria;
import com.doob.mathagent.agent.service.InMemoryAgentTraceStore;
import com.doob.mathagent.knowledge.dto.QuestionBankItemCreateRequest;
import com.doob.mathagent.knowledge.service.InMemoryKnowledgeQuestionBankStore;
import com.doob.mathagent.knowledge.service.KnowledgeQuestionBankService;
import com.doob.mathagent.knowledge.vo.QuestionBankItemResponse;
import com.doob.mathagent.memory.service.InMemoryStudentMemoryStore;
import com.doob.mathagent.memory.service.StudentMemoryCommand;
import com.doob.mathagent.memory.service.StudentMemoryReuseService;
import com.doob.mathagent.memory.vo.StudentMemoryResponse;
import com.doob.mathagent.resources.TextbookCatalogReader;
import com.doob.mathagent.resources.TextbookChunkReader;
import com.doob.mathagent.resources.TextbookPageImageService;
import com.doob.mathagent.retrieval.LocalTextbookBm25SearchEngine;
import com.doob.mathagent.retrieval.NoopRetrievalAuditSink;
import com.doob.mathagent.retrieval.RedisTextbookSearchCacheProperties;
import com.doob.mathagent.retrieval.RetrievalRequestContext;
import com.doob.mathagent.retrieval.TextbookRetrievalService;
import com.doob.mathagent.retrieval.TextbookSearchCache;
import com.doob.mathagent.retrieval.TextbookSearchRequest;
import com.doob.mathagent.retrieval.TextbookSearchResponse;
import com.doob.mathagent.teaching.dto.TeachingTaskRequest;
import com.doob.mathagent.teaching.service.InMemoryTeachingTaskStore;
import com.doob.mathagent.teaching.service.TeachingHandoutTemplateProfile;
import com.doob.mathagent.teaching.service.TeachingHandoutTemplateService;
import com.doob.mathagent.teaching.service.TeachingWorkflowService;
import com.doob.mathagent.teaching.TeachingKnowledgePointPack;
import com.doob.mathagent.teaching.vo.TeachingTaskResponse;
import com.doob.mathagent.teacher.TeacherResourceBlockSearchServiceFixture;
import com.doob.mathagent.teacher.service.InMemoryTeacherDocumentBlockStore;
import com.doob.mathagent.teacher.service.InMemoryTeacherResourceStore;
import com.doob.mathagent.teacher.service.TeacherResourceBlockSearchService;
import com.doob.mathagent.teacher.search.TeacherResourceGraphAlignmentService;
import com.doob.mathagent.teacher.block.TeacherDocumentBlockResponse;
import com.doob.mathagent.teacher.search.TeacherResourceBlockSearchResponse;
import com.doob.mathagent.teacher.document.TeacherResourceDocumentResponse;
import com.doob.mathagent.vector.service.TestVectorIndexService;
import java.time.Duration;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TeachingWorkflowServiceTest {


    @TempDir
    Path tempDir;

    @Test
    void sourcePageAndAnalysisPageMirrorsKeepOnlyTheAnsweredAtomicQuestion() throws Exception {
        var method = TeachingWorkflowService.class.getDeclaredMethod(
                "deduplicateAtomicQuestionRows", java.util.Collection.class);
        method.setAccessible(true);
        QuestionBankItemResponse stemOnly = new QuestionBankItemResponse(
                "question-page", "tenant-a", "teacher-1", "TEACHER_PRIVATE",
                "第19题", "19. 已知数列 $a_n$ 满足递推关系，求通项公式。", "{}", "中等", "ACTIVE",
                "document-2024", "page-21#q19", "checksum-stem", List.of());
        QuestionBankItemResponse solvedMirror = new QuestionBankItemResponse(
                "analysis-page", "tenant-a", "teacher-1", "TEACHER_PRIVATE",
                "第19题解析", "19. 已知数列 a_n 满足递推关系，求通项公式。",
                "{\"answer\":\"由递推关系整理后得到通项\",\"steps\":[\"移项\",\"验证首项\"]}",
                "中等", "ACTIVE", "document-2024", "page-23#q19", "checksum-analysis", List.of());

        @SuppressWarnings("unchecked")
        List<QuestionBankItemResponse> actual = (List<QuestionBankItemResponse>) method.invoke(
                null, List.of(stemOnly, solvedMirror));

        // The result must be one printable prompt and it must retain its official answer rather than the bare stem.
        assertThat(actual).extracting(QuestionBankItemResponse::questionId).containsExactly("analysis-page");
    }

    @Test
    void fallbackPackPrefersTopicMatchedTeacherEvidenceOverUnrelatedPublicTextbook() throws Exception {
        var method = TeachingWorkflowService.class.getDeclaredMethod(
                "fallbackKnowledgePointPacks", TeachingTaskRequest.class, List.class);
        method.setAccessible(true);
        TeachingEvidence unrelatedTextbook = new TeachingEvidence(
                "PUBLIC_TEXTBOOK", "人教B版必修一数学 / 3.3 函数的应用(一)", "book-function", 136,
                "城镇化人口增长的一次函数应用。", "C:/approved/function-page.png");
        TeachingEvidence coloringResource = new TeachingEvidence(
                "TEACHER_RESOURCE", "七、涂色问题（长时间不考） / 涂色问题", "teacher-coloring", 0,
                "如图，一个地区分为5个行政区域，相邻区域不得使用同一颜色，现有四种颜色可供选择。",
                "C:/approved/coloring-map.jpg");

        @SuppressWarnings("unchecked")
        List<TeachingKnowledgePointPack> packs = (List<TeachingKnowledgePointPack>) method.invoke(
                null,
                new TeachingTaskRequest("fallback-coloring", "五个区域相邻不同色，四种颜色如何着色", "2013年涂色问题", 5),
                List.of(unrelatedTextbook, coloringResource));

        assertThat(packs).hasSize(1);
        assertThat(packs.getFirst().title()).contains("涂色问题");
        assertThat(packs.getFirst().supportingEvidence())
                .containsExactly(coloringResource)
                .allSatisfy(item -> assertThat(item.imagePath()).endsWith("coloring-map.jpg"));
    }

    @Test
    void fallbackPackDeduplicatesTheSameFeishuSourceAndKeepsItsAuthorizedImage() throws Exception {
        var method = TeachingWorkflowService.class.getDeclaredMethod(
                "fallbackKnowledgePointPacks", TeachingTaskRequest.class, List.class);
        method.setAccessible(true);
        TeachingEvidence imageBearingCopy = new TeachingEvidence(
                "TEACHER_RESOURCE",
                "七、涂色问题 / JdADd9Qc6o5JcbxdzsJcrn3qnJf / 2013年涂色问题",
                "block-with-image",
                0,
                "2013年涂色问题：相邻区域不得使用同一颜色；合计：24+48=72。",
                "C:/approved/coloring-map.jpg");
        TeachingEvidence textOnlyCopy = new TeachingEvidence(
                "TEACHER_RESOURCE",
                "七、涂色问题（长时间不考） / JdADd9Qc6o5JcbxdzsJcrn3qnJf",
                "block-text-only",
                0,
                "2013年涂色问题：相邻区域不得使用同一颜色；合计：24+48=72。");

        @SuppressWarnings("unchecked")
        List<TeachingKnowledgePointPack> packs = (List<TeachingKnowledgePointPack>) method.invoke(
                null,
                new TeachingTaskRequest("fallback-coloring-dedupe", "五个区域相邻不同色，四种颜色如何着色", "2013年涂色问题地图图片证据", 5),
                List.of(textOnlyCopy, imageBearingCopy));

        assertThat(packs).hasSize(1);
        assertThat(packs.getFirst().title()).doesNotContain("图片证据");
        assertThat(packs.getFirst().supportingEvidence())
                .containsExactly(imageBearingCopy)
                .allSatisfy(item -> assertThat(item.imagePath()).endsWith("coloring-map.jpg"));
    }

    @Test
    void fallbackPackRejectsColoringEvidenceWhoseColorCountConflictsWithTheQuestion() throws Exception {
        var method = TeachingWorkflowService.class.getDeclaredMethod(
                "fallbackKnowledgePointPacks", TeachingTaskRequest.class, List.class);
        method.setAccessible(true);
        TeachingEvidence sixColorVariation = new TeachingEvidence(
                "TEACHER_RESOURCE", "2013年涂色问题 / 例题：改版6种颜色", "six-colors", 0,
                "用6种不同的颜色给图中区域着色，分类结果为540。", "C:/approved/six-color-map.jpg");
        TeachingEvidence fourColorOriginal = new TeachingEvidence(
                "TEACHER_RESOURCE", "2013年涂色问题 / 原题：4种颜色", "four-colors", 0,
                "一个地区分为5个行政区域，相邻区域不得使用同一颜色，现有4种颜色，合计72。",
                "C:/approved/four-color-map.jpg");

        @SuppressWarnings("unchecked")
        List<TeachingKnowledgePointPack> packs = (List<TeachingKnowledgePointPack>) method.invoke(
                null,
                new TeachingTaskRequest("fallback-coloring-constraint", "5个行政区域相邻不同色，现有四种颜色，求着色方法数", "2013年涂色问题", 5),
                List.of(sixColorVariation, fourColorOriginal));

        assertThat(packs).hasSize(1);
        assertThat(packs.getFirst().supportingEvidence()).containsExactly(fourColorOriginal);
    }

    @Test
    void keepsSinglePointAiStudentHintsAndPracticeTasksBesideTheAuthorizedQuestionImage() throws Exception {
        var method = TeachingWorkflowService.class.getDeclaredMethod(
                "buildStudentKnowledgePointHandout", String.class, List.class, int.class, List.class, List.class, List.class);
        method.setAccessible(true);
        TeachingEvidence teacherFigure = new TeachingEvidence(
                "TEACHER_RESOURCE", "涂色问题", "teacher-coloring", 0,
                "如图，五个区域相邻不同色。", "C:/approved/coloring-map.jpg");
        TeachingKnowledgePointPack pack = new TeachingKnowledgePointPack(
                "涂色问题", List.of(teacherFigure),
                new TeachingEvidence("USER_PROVIDED", "用户题目 / 涂色问题", "user", 0, "五个区域相邻不同色，四种颜色如何着色？"),
                null);

        String latex = (String) method.invoke(null, "涂色问题", List.of(pack), 8,
                List.of("相邻区域异色；不相邻区域可以同色。", "已染色相邻区域用了 t 种不同颜色时，可选数为 4-t。"),
                List.of("先在图上标出相邻关系，再安排染色顺序。"),
                List.of("写出区域 1 的相邻区域：___。", "若出现不同分支，分别计数后相加。"));

        assertThat(latex).contains(
                "\\paragraph{知识速记}", "相邻区域异色", "可选数为$4-t$",
                "\\paragraph{识别信号}", "标出相邻关系", "\\paragraph{自检任务}", "分别计数后相加")
                .doesNotContain("答案与评分点", "参考答案");
        assertThat(latex.indexOf("\\subsection*{例题}"))
                .isLessThan(latex.indexOf("\\paragraph{自检任务}"));
    }

    @Test
    void printableTeacherEvidenceKeepsOnlyTheVerifiedCalculationInsteadOfRawOcr() throws Exception {
        var method = TeachingWorkflowService.class.getDeclaredMethod("compactEvidenceFact", String.class);
        method.setAccessible(true);

        String printable = (String) method.invoke(null,
                "七、涂色问题 这个是很久不考的考点；s 6 times 2 = 48 合计：24 + 48 = 72 我们要做的就是分类。");

        assertThat(printable).isEqualTo("资料分类结果：24 + 48 = 72");
    }

    @Test
    void printsTheQuestionStemOnceWithoutARepeatedSourceTitle() throws Exception {
        var method = TeachingWorkflowService.class.getDeclaredMethod(
                "appendTeacherQuestion",
                StringBuilder.class,
                int.class,
                String.class,
                TeachingEvidence.class,
                String.class,
                String.class,
                String.class,
                String.class);
        method.setAccessible(true);
        StringBuilder latex = new StringBuilder();
        TeachingEvidence question = new TeachingEvidence(
                "QUESTION_BANK",
                "赵礼显数学作业 1 / 难度：medium",
                "zhao-work-1",
                1,
                "在三棱柱 ABC-A1B1C1 中，求二面角的余弦值。");

        method.invoke(null, latex, 1, "例题", question, "", "", "", "先写出对应的空间关系。");

        assertThat(latex)
                .contains("在三棱柱", "求二面角的余弦值。")
                .doesNotContain("赵礼显数学作业 1");
    }

    @Test
    void bindsTopicMatchedTeacherFigureToQuestionBankKnowledgePoint() throws Exception {
        var method = TeachingWorkflowService.class.getDeclaredMethod(
                "buildKnowledgePointPacks", TeachingTaskRequest.class, List.class, List.class, List.class);
        method.setAccessible(true);
        TeachingTaskRequest request = new TeachingTaskRequest(
                "figure-bind", "五个区域相邻不同色，四种颜色如何着色", "2013年涂色问题", 6);
        TeachingEvidence question = new TeachingEvidence(
                "QUESTION_BANK", "如图五个区域相邻不同色", "question-1", 0,
                "五个区域相邻不得同色，四种颜色求着色方法数。", null);
        TeachingEvidence teacherFigure = new TeachingEvidence(
                "TEACHER_RESOURCE", "2013年涂色问题 / 原题", "block-1", 0,
                "如图，一个地区分为5个行政区域，现有四种颜色可供选择。", "C:/approved/coloring-map.jpg");

        @SuppressWarnings("unchecked")
        List<TeachingKnowledgePointPack> packs = (List<TeachingKnowledgePointPack>) method.invoke(
                null, request, List.of(), List.of(teacherFigure), List.of(question));

        assertThat(packs).hasSize(1);
        assertThat(packs.getFirst().supportingEvidence())
                .extracting(TeachingEvidence::imagePath)
                .containsExactly("C:/approved/coloring-map.jpg");
    }

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
        assertThat(response.workflowEvents())
                .extracting(TeachingWorkflowEvent::eventType)
                .contains("plan", "evidence", "generation", "render");
        assertThat(response.workflowEvents())
                .anySatisfy(event -> {
                    assertThat(event.sourceName()).isEqualTo("EvidenceCollector");
                    assertThat(event.title()).contains("并行");
                    assertThat(event.artifactRefs()).contains("PUBLIC_TEXTBOOK");
                    assertThat(event.summary()).contains("教材A");
                    assertThat(event.summary()).contains("下一步：以这些来源逐题核对知识点、题干与答案");
                });
        assertThat(response.evidence()).isNotEmpty();
        assertThat(response.evidence().getFirst().sourceScope()).isEqualTo("PUBLIC_TEXTBOOK");
        assertThat(response.draftSections().sourceRefs())
                .anySatisfy(ref -> assertThat(ref).startsWith("PUBLIC_TEXTBOOK:教材A").endsWith(":book_a_p101_text_001"));
        assertThat(response.draftSections().risks())
                .contains("student_answer_leakage_review_required", "lecture_cards_from_python_handout")
                .doesNotContain("source_grounding_missing");
        assertThat(response.handoutLatex()).contains("\\section{函数新概念：题型总览}");
        assertThat(response.teacherHandoutLatex()).contains(
                "\\section{函数新概念：题型总览}",
                "\\section{题型：函数新概念}",
                "\\subsection*{讲解}");
        assertThat(response.teacherHandoutLatex()).doesNotContain(
                "模板：", "题目入口", "讲评入口", "审题提醒", "题型入口", "知识入口",
                "课前定位", "来源依据", "讲评主线", "核心公式与方法卡", "16:10 横版讲解卡", "板书与二次反馈");
        assertThat(response.lectureHandoutLatex())
                .contains("\\section{课堂讲解}", "\\vspace")
                .doesNotContain("\\section{答案与评分点}", "教师手写区", "手写区", "板书留白", "MODEL_CALL", "JSON_PARSE", "tokens=");
        assertThat(response.teacherHandoutLatex()).doesNotContain(
                "来源 1", "公开教材", "用途：知识点定位与公式依据");
        assertThat(response.teacherHandoutLatex()).doesNotContain(
                "PDF 版式要求", "页眉展示主题和版本", "页脚展示页码", "页眉", "页脚", "版式", "颜色", "系统说明");
        assertThat(response.teacherHandoutLatex()).doesNotContain(
                "![p", "## 正文", "书名：", "formula_text", "source_page_image", "D(x_0)=\\{d");
        assertThat(response.studentHandoutLatex()).contains(
                "\\paragraph{知识速记}",
                "\\paragraph{识别信号}");
        assertThat(response.studentHandoutLatex()).doesNotContain("\\section{第 1 讲");
        assertThat(response.teacherHandoutLatex()).doesNotContain("\\section{核心方法}", "\\section{解题步骤}");
        assertThat(response.studentHandoutLatex()).doesNotContain("\\section{我的解答}", "\\section{订正记录}", "\\vspace{12em}");
        assertThat(response.studentHandoutLatex()).doesNotContain("版本：学生版", "页眉", "页脚", "颜色");
        assertThat(response.studentHandoutLatex()).doesNotContain("知识点归属");
        assertThat(response.interactiveSuggestions()).contains("继续追问定义 D(x_0)");
        assertThat(response.aiDraft().enabled()).isTrue();
        assertThat(response.teacherHandoutLatex()).doesNotContain("AI生成状态", "AI 讲义草稿");
        assertThat(response.memoryReuse().reused()).isFalse();
        assertThat(response.stageTimings()).extracting(TeachingTaskResponse.StageTiming::stage)
                .contains("memory_reuse", "textbook_retrieval", "react_trace", "ai_draft", "handout_generation");
    }

    @Test
    void rendersNamedKnowledgePointSectionsWithOneWorkedExampleAndVariationPerPoint() throws Exception {
        Path root = createTextbookCorpus();
        KnowledgeQuestionBankService questionBank = new KnowledgeQuestionBankService(new InMemoryKnowledgeQuestionBankStore());
        questionBank.createQuestion("tenant-a", "teacher", "teacher-1", new QuestionBankItemCreateRequest(
                "函数新概念：定义域判断",
                "已知新定义 D(x_0)，根据定义求 D(1) 的取值范围。",
                "{\"answer\":\"代入定义并整理范围\",\"steps\":[\"写出 D(1) 的定义\",\"按定义整理取值范围\"]}", "基础", "TEACHER_PRIVATE", List.of()));
        questionBank.createQuestion("tenant-a", "teacher", "teacher-1", new QuestionBankItemCreateRequest(
                "函数新概念：定义域变式",
                "若定义中的不等号改为大于等于，求 D(1)。",
                "{\"answer\":\"比较边界是否取到\"}", "提高", "TEACHER_PRIVATE", List.of()));
        questionBank.createQuestion("tenant-a", "teacher", "teacher-1", new QuestionBankItemCreateRequest(
                "分段函数：按区间代入",
                "已知分段函数，求 f(-1)、f(0) 与 f(2)。",
                "{\"answer\":\"先定位区间再代入\"}", "基础", "TEACHER_PRIVATE", List.of()));
        questionBank.createQuestion("tenant-a", "teacher", "teacher-1", new QuestionBankItemCreateRequest(
                "分段函数：分类讨论变式",
                "讨论参数变化后分段函数在不同区间的值。",
                "{\"answer\":\"按分界点分类讨论\"}", "提高", "TEACHER_PRIVATE", List.of()));
        TeachingWorkflowService service = new TeachingWorkflowService(
                root, retrievalService(), new InMemoryTeachingTaskStore(), memoryReuseService(),
                null, new InMemoryAgentTraceStore(),
                new TeachingHandoutTemplateService(), Optional.of(questionBank), Runnable::run);
        useCompletedPythonHandoutClient(service);

        TeachingTaskResponse created = service.submit(
                new TeachingTaskRequest("req-point-sections", "函数新概念与分段函数", "函数新概念与分段函数", 4),
                new TeachingRequestContext("tenant-a", "teacher", "teacher-1", "device-1"));
        TeachingTaskResponse response = service.get(
                created.taskId(), new TeachingRequestContext("tenant-a", "teacher", "teacher-1", "device-1"))
                .orElseThrow();

        assertThat(response.teacherHandoutLatex())
                .contains("\\section{题型：函数新概念}", "\\section{题型：分段函数}",
                        "\\paragraph{条件落点}", "\\paragraph{推导链条}", "\\paragraph{答案与评分点}",
                        "写出 D(1) 的定义", "按定义整理取值范围", "\\subsection*{第1题 例题}")
                .doesNotContain("\\section{本节目标}", "\\section{核心方法}", "\\section{解题步骤}", "例题详解",
                        "函数新概念：定义域判断", "函数新概念：定义域变式",
                        "分段函数：按区间代入", "分段函数：分类讨论变式");
        assertThat(response.studentHandoutLatex())
                .doesNotContain("核心方法", "解题步骤", "答案要点",
                        "函数新概念：定义域判断", "分段函数：按区间代入");
    }

    @Test
    void removesUnreadableLectureCardBeforeAddingProjectionPageBreak() throws Exception {
        var method = TeachingWorkflowService.class.getDeclaredMethod(
                "buildLectureHandoutLatex", TeachingTaskRequest.class, TeachingDraftSections.class);
        method.setAccessible(true);
        String lecture = (String) method.invoke(
                null,
                new TeachingTaskRequest("req-lecture-sanitize", "", "集合交并", 1),
                new TeachingDraftSections(
                        "",
                        "",
                        List.of("??????", "第 2 屏：保留这个有效讲解单元。"),
                        List.of(),
                        List.of(),
                        List.of()));

        assertThat(lecture)
                .contains("\\section{课堂讲解}", "第 1 题 / 讲解单元", "保留这个有效讲解单元")
                .doesNotContain("??????")
                .doesNotContain("第 2 题 / 讲解单元")
                .doesNotContain("\\clearpage");
    }

    /**
     * A source-only knowledge pack is valid input for the teacher renderer but cannot create a projection question.
     * The reviewed handout graph has already supplied lecture cards, so projection rendering must retain those cards
     * instead of persisting the empty workspace placeholder as a completed lecture version.
     */
    @Test
    void retainsReviewedLectureCardsWhenRetrievedPackHasNoPublishableQuestion() throws Exception {
        var method = TeachingWorkflowService.class.getDeclaredMethod(
                "buildLectureHandoutLatex", TeachingTaskRequest.class, List.class, TeachingDraftSections.class);
        method.setAccessible(true);
        TeachingKnowledgePointPack sourceOnlyPack = new TeachingKnowledgePointPack(
                "函数概念",
                List.of(new TeachingEvidence("PUBLIC_TEXTBOOK", "教材", "source-only", 3, "定义与性质。")),
                new TeachingEvidence(
                        "QUESTION_BANK", "缺图几何题", "unpublishable-question", 0,
                        "如图，在三棱柱中求二面角的余弦值。"),
                null);

        String lecture = (String) method.invoke(
                null,
                new TeachingTaskRequest("req-lecture-fallback", "函数概念", "函数概念", 1),
                List.of(sourceOnlyPack),
                new TeachingDraftSections(
                        "", "", List.of("第 1 屏：从定义出发说明函数概念。"), List.of(), List.of(), List.of()));

        assertThat(lecture)
                .contains("\\section{课堂讲解}", "从定义出发说明函数概念", "第 1 题 / 讲解单元")
                .doesNotContain("缺图几何题");
    }

    @Test
    void compactsEachLandscapeCardBeforeRenderingItsDedicatedPage() throws Exception {
        var method = TeachingWorkflowService.class.getDeclaredMethod(
                "teacherWideSlides", String.class, String.class, String.class, String.class, String.class, String.class);
        method.setAccessible(true);
        String dense = "第一步先配方并定位顶点；第二步比较顶点与区间端点；第三步按参数范围分类；"
                + "第四步逐类代入求值；第五步检查临界值；第六步写出完整分段表达式；"
                + "第七步说明每一类的取值位置和理由；第八步补充容易混淆的边界条件。";
        dense = dense.repeat(3);

        @SuppressWarnings("unchecked")
        List<String> cards = (List<String>) method.invoke(null, "已知函数在闭区间上的最小值", dense, dense, dense, dense, dense);

        assertThat(cards).allSatisfy(card -> assertThat(card.length()).isLessThanOrEqualTo(220));
    }

    @Test
    void rendersVerifiedLectureAsOneQuestionPageWithoutGenericIntroOrInternalUserLabel() throws Exception {
        var method = TeachingWorkflowService.class.getDeclaredMethod(
                "buildLectureHandoutLatex", TeachingTaskRequest.class, List.class, TeachingDraftSections.class);
        method.setAccessible(true);
        TeachingEvidence source = new TeachingEvidence(
                "TEACHER_RESOURCE",
                "涂色问题教师资料",
                "coloring-source",
                1,
                "先选颜色然后再涂色。三个互相相邻区域先分配三种颜色；三种颜色时有24种，四种颜色时有48种，合计72种。");
        TeachingEvidence question = new TeachingEvidence(
                "USER_PROVIDED",
                "用户题目 / 涂色问题",
                "coloring-question",
                0,
                "如图，一个地区分为5个行政区域，相邻区域不得使用同一颜色，现有4种颜色，求不同着色方法数。");
        TeachingKnowledgePointPack pack = new TeachingKnowledgePointPack(
                "涂色问题：分类计数",
                List.of(source),
                question,
                null);

        String lecture = (String) method.invoke(
                null,
                new TeachingTaskRequest("req-lecture-single-question", question.snippet(), "涂色问题", 1),
                List.of(pack),
                new TeachingDraftSections("", "", List.of(), List.of(), List.of(), List.of()));

        assertThat(lecture)
                .contains("\\section{课堂讲解}", "\\paragraph{课堂投屏}", "涂色问题", "\\vspace{8em}")
                .doesNotContain("用户题目", "投屏内容", "讲解单元", "先选颜色然后再涂色");
    }

    @Test
    void removesInternalUserQuestionTransportPrefixFromPrintableTitles() throws Exception {
        var method = TeachingWorkflowService.class.getDeclaredMethod("questionTitleWithoutDifficulty", TeachingEvidence.class);
        method.setAccessible(true);
        String title = (String) method.invoke(
                null,
                new TeachingEvidence("USER_PROVIDED", "用户题目 / 涂色问题", "user-question", 0, "题干"));

        assertThat(title).isEqualTo("涂色问题");
    }

    @Test
    void teacherEvidenceUsesAuthorizedExpandedWindowInsteadOfSearchSnippet() throws Exception {
        TeachingWorkflowService workflow = service(createTextbookCorpus());
        var method = TeachingWorkflowService.class.getDeclaredMethod(
                "toTeacherResourceEvidence", TeacherResourceBlockSearchResponse.Hit.class, TeachingRequestContext.class);
        method.setAccessible(true);
        TeacherResourceBlockSearchResponse.Hit hit = new TeacherResourceBlockSearchResponse.Hit(
                "teacher-document",
                "涂色问题教师资料",
                "feishu",
                "TEACHER_SHARED",
                "coloring-block",
                "markdown",
                1,
                "涂色问题",
                "2013 年涂色问题",
                1,
                "teacher://coloring",
                "analysis",
                List.of("涂色问题"),
                List.of("coloring-block"),
                "2013 年涂色问题先由相邻关系确定三个互相相邻的区域，再按使用颜色数分类计数，并逐一核验每对区域的公共边。"
                        + "板书时先写最小涂色组合，再分别讨论三种颜色和四种颜色；每一步都要回到图形关系核对。"
                        + "这一段仅说明审题和画图的顺序，用于验证长教师资料必须保留题目自己的数值结论，而不能被后续例题覆盖。"
                        + "三种颜色有 24 种，四种颜色有 48 种，合计 72 种。"
                        + "后续另一道六种颜色变式的合计 390 种不能作为本题答案。",
                "如图，一个地区分为5个行政区域……",
                1.0d,
                List.of(),
                List.of());

        TeachingEvidence evidence = (TeachingEvidence) method.invoke(
                workflow,
                hit,
                new TeachingRequestContext("tenant-a", "teacher", "teacher-1", "device-1"));

        assertThat(evidence.snippet()).contains("24", "48", "72").doesNotContain("390", "如图，一个地区分为5个行政区域");
        assertThat(evidence.sourceDocumentId()).isEqualTo("teacher-document");
    }

    @Test
    void mapsLegacyRagMirrorCitationToTheCurrentVisibleTeacherDocumentAndBlock() throws Exception {
        InMemoryTeacherResourceStore resourceStore = new InMemoryTeacherResourceStore();
        InMemoryTeacherDocumentBlockStore blockStore = new InMemoryTeacherDocumentBlockStore();
        String sourceIdentity = "feishu:docx:JdADd9Qc6o5JcbxdzsJcrn3qnJf";
        resourceStore.save(new TeacherResourceDocumentResponse(
                "legacy-mirror-document", "tenant-a", "legacy-importer", "feishu",
                "七、涂色问题（长时间不考） / JdADd9Qc6o5JcbxdzsJcrn3qnJf",
                "https://example.feishu.cn/docx/JdADd9Qc6o5JcbxdzsJcrn3qnJf", null,
                "MATH_VIP", "synced", "parsed", "embedded", "ready", "md", List.of(), "TEXT",
                "old-revision", "old-checksum", sourceIdentity));
        resourceStore.save(new TeacherResourceDocumentResponse(
                "current-visible-document", "tenant-a", "teacher-1", "feishu",
                "七、涂色问题 / JdADd9Qc6o5JcbxdzsJcrn3qnJf",
                "https://example.feishu.cn/docx/JdADd9Qc6o5JcbxdzsJcrn3qnJf", null,
                "TEACHER_PRIVATE", "synced", "parsed", "embedded", "ready", "md", List.of(), "TEXT",
                "current-revision", "current-checksum", sourceIdentity));
        blockStore.replaceActiveBlocks("tenant-a", "legacy-mirror-document", List.of(
                teacherBlockWithSourcePath(
                        "legacy-coloring-block", "legacy-mirror-document", "JdADd9Qc6o5JcbxdzsJcrn3qnJf.md",
                        "2013年涂色问题", "一个地区分为5个行政区域，现有四种颜色，合计：24+48=72。",
                        "same-question-checksum")));
        blockStore.replaceActiveBlocks("tenant-a", "current-visible-document", List.of(
                teacherBlockWithSourcePath(
                        "current-coloring-block", "current-visible-document", "JdADd9Qc6o5JcbxdzsJcrn3qnJf.md",
                        "2013年涂色问题", "一个地区分为5个行政区域，现有四种颜色，合计：24+48=72。",
                        "same-question-checksum")));
        TeachingWorkflowService workflow = new TeachingWorkflowService(
                createTextbookCorpus(), retrievalService(), new InMemoryTeachingTaskStore(), memoryReuseService(),
                null, new InMemoryAgentTraceStore(),
                new TeachingHandoutTemplateService(), Optional.empty(),
                Optional.of(TeacherResourceBlockSearchServiceFixture.service(resourceStore, blockStore)), Runnable::run);
        var method = TeachingWorkflowService.class.getDeclaredMethod(
                "toTeacherResourceEvidence", TeacherResourceBlockSearchResponse.Hit.class, TeachingRequestContext.class);
        method.setAccessible(true);

        TeachingEvidence evidence = (TeachingEvidence) method.invoke(workflow,
                new TeacherResourceBlockSearchResponse.Hit(
                        "legacy-mirror-document", "七、涂色问题（长时间不考） / JdADd9Qc6o5JcbxdzsJcrn3qnJf",
                        "feishu", "MATH_VIP", "legacy-coloring-block", "markdown", 1,
                        "七、涂色问题", "2013年涂色问题", 1, "JdADd9Qc6o5JcbxdzsJcrn3qnJf.md",
                        "question", List.of("涂色问题"), List.of("legacy-coloring-block"),
                        "一个地区分为5个行政区域，现有四种颜色，合计：24+48=72。", "涂色问题", 1.0d,
                        List.of(), List.of()),
                new TeachingRequestContext("tenant-a", "teacher", "teacher-1", "device-1"));

        assertThat(evidence.sourceDocumentId()).isEqualTo("current-visible-document");
        assertThat(evidence.chunkId()).isEqualTo("current-coloring-block");
    }

    @Test
    void omitsInspectableCitationWhenLegacyRagMirrorHasNoVisibleSameSource() throws Exception {
        InMemoryTeacherResourceStore resourceStore = new InMemoryTeacherResourceStore();
        InMemoryTeacherDocumentBlockStore blockStore = new InMemoryTeacherDocumentBlockStore();
        resourceStore.save(teacherResourceDocument(
                "unrelated-visible-document", "teacher-1", "TEACHER_PRIVATE", "其他专题资料"));
        blockStore.replaceActiveBlocks("tenant-a", "unrelated-visible-document", List.of(
                teacherBlock("unrelated-coloring-block", "unrelated-visible-document", 1,
                        "排列组合", "2013年涂色问题", 1, "同名小节但不是同一份教师资料。")));
        TeachingWorkflowService workflow = new TeachingWorkflowService(
                createTextbookCorpus(), retrievalService(), new InMemoryTeachingTaskStore(), memoryReuseService(),
                null, new InMemoryAgentTraceStore(),
                new TeachingHandoutTemplateService(), Optional.empty(),
                Optional.of(TeacherResourceBlockSearchServiceFixture.service(resourceStore, blockStore)), Runnable::run);
        var method = TeachingWorkflowService.class.getDeclaredMethod(
                "toTeacherResourceEvidence", TeacherResourceBlockSearchResponse.Hit.class, TeachingRequestContext.class);
        method.setAccessible(true);

        TeachingEvidence evidence = (TeachingEvidence) method.invoke(workflow,
                new TeacherResourceBlockSearchResponse.Hit(
                        "legacy-mirror-document", "七、涂色问题 / JdADd9Qc6o5JcbxdzsJcrn3qnJf",
                        "feishu", "MATH_VIP", "legacy-coloring-block", "markdown", 1,
                        "七、涂色问题", "2013年涂色问题", 1, "JdADd9Qc6o5JcbxdzsJcrn3qnJf.md",
                        "question", List.of("涂色问题"), List.of("legacy-coloring-block"),
                        "一个地区分为5个行政区域，现有四种颜色，合计：24+48=72。", "涂色问题", 1.0d,
                        List.of(), List.of()),
                new TeachingRequestContext("tenant-a", "teacher", "teacher-1", "device-1"));

        assertThat(evidence.sourceDocumentId()).isBlank();
        assertThat(evidence.chunkId()).isEqualTo("legacy-coloring-block");
    }

    @Test
    void removesWorkflowControlTextFromEveryPrintableHandout() throws Exception {
        TeachingWorkflowService service = service(createTextbookCorpus());
        TeachingTaskResponse response = service.submit(
                new TeachingTaskRequest(
                        "req-student-workflow-guard",
                        "函数新概念 D(x_0)",
                        "函数新概念讲解稿独立生成",
                        3),
                new TeachingRequestContext("tenant-a", "teacher", "teacher-1", "device-1"));

        assertThat(response.teacherHandoutLatex())
                .doesNotContain("教师版", "16:10 讲解版独立生成", "不从教师版截取", "题目入口", "审题提醒");
        assertThat(response.studentHandoutLatex())
                .doesNotContain("教师版", "16:10 讲解版独立生成", "不从教师版截取")
                .contains("\\paragraph{知识速记}", "\\paragraph{识别信号}");
    }

    @Test
    void retrievesIndependentEvidenceSourcesInParallelBeforeCollectingPack() throws Exception {
        Path root = createTextbookCorpus();
        CountDownLatch teacherResourceStarted = new CountDownLatch(1);
        AtomicBoolean textbookObservedTeacherResource = new AtomicBoolean(false);
        ExecutorService evidenceExecutor = Executors.newFixedThreadPool(2);
        try {
            TeachingWorkflowService service = new TeachingWorkflowService(
                    root,
                    new GateTextbookRetrievalService(teacherResourceStarted, textbookObservedTeacherResource),
                    new InMemoryTeachingTaskStore(),
                    memoryReuseService(),
                    null,
                    new InMemoryAgentTraceStore(),
                    new com.doob.mathagent.teaching.service.TeachingHandoutTemplateService(),
                    Optional.of(new GateQuestionBankService()),
                    Optional.of(new GateTeacherResourceBlockSearchService(teacherResourceStarted)),
                    Runnable::run);
            service.setEvidenceTaskExecutorForTesting(evidenceExecutor::execute);
            useCompletedPythonHandoutClient(service);

            service.submit(
                    new TeachingTaskRequest("req-parallel-evidence", "空间向量求线面角", "空间向量线面角", 3),
                    new TeachingRequestContext("tenant-a", "teacher", "teacher-1", "device-1"));

            assertThat(textbookObservedTeacherResource)
                    .as("textbook retrieval should observe teacher-resource retrieval starting before it returns")
                    .isTrue();
        } finally {
            evidenceExecutor.shutdownNow();
        }
    }

    @Test
    void collectsTeacherStudentAndLectureHandoutVersionsInParallelFromSharedOutline() {
        CountDownLatch allWritersStarted = new CountDownLatch(3);
        AtomicBoolean teacherObservedAllWriters = new AtomicBoolean(false);
        AtomicBoolean studentObservedAllWriters = new AtomicBoolean(false);
        AtomicBoolean lectureObservedAllWriters = new AtomicBoolean(false);

        TeachingHandoutVersions versions = TeachingHandoutVersionCollector.collect(
                () -> {
                    try {
                        allWritersStarted.countDown();
                        teacherObservedAllWriters.set(allWritersStarted.await(1, TimeUnit.SECONDS));
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException("Interrupted while waiting for handout writers", exception);
                    }
                    return "teacher-version";
                },
                () -> {
                    try {
                        allWritersStarted.countDown();
                        studentObservedAllWriters.set(allWritersStarted.await(1, TimeUnit.SECONDS));
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException("Interrupted while waiting for handout writers", exception);
                    }
                    return "student-version";
                },
                () -> {
                    try {
                        allWritersStarted.countDown();
                        lectureObservedAllWriters.set(allWritersStarted.await(1, TimeUnit.SECONDS));
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException("Interrupted while waiting for handout writers", exception);
                    }
                    return "lecture-version";
                });

        assertThat(teacherObservedAllWriters).isTrue();
        assertThat(studentObservedAllWriters).isTrue();
        assertThat(lectureObservedAllWriters).isTrue();
        assertThat(versions.teacherHandoutLatex()).isEqualTo("teacher-version");
        assertThat(versions.studentHandoutLatex()).isEqualTo("student-version");
        assertThat(versions.lectureHandoutLatex()).isEqualTo("lecture-version");
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
                .contains("可复用学习记录");
        assertThat(response.stageTimings()).extracting(TeachingTaskResponse.StageTiming::stage)
                .containsExactly("memory_reuse");
        assertThat(response.status()).isEqualTo(TeachingTaskStatus.FAILED);
        assertThat(response.errorMessage()).contains("教材、题库或教师资料证据");
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
    void savesOnlyTheRequestedCompletedHandoutVersionWithoutCreatingAnotherTask() throws Exception {
        Path root = createTextbookCorpus();
        TeachingWorkflowService service = service(root);
        TeachingRequestContext context = new TeachingRequestContext(
                "tenant-a", "teacher", "teacher-1", "device-1");
        TeachingTaskResponse original = service.submit(
                new TeachingTaskRequest("req-edit-version", "函数新概念 D(x_0)", "函数新概念分类讨论", 3),
                context);

        TeachingTaskResponse updated = service.updateHandoutVersion(
                original.taskId(),
                "student",
                "\\section{涂色问题练习}\n请先完成分类讨论。",
                context);

        assertThat(updated.taskId()).isEqualTo(original.taskId());
        assertThat(updated.studentHandoutLatex()).contains("请先完成分类讨论");
        assertThat(updated.teacherHandoutLatex()).isEqualTo(original.teacherHandoutLatex());
        assertThat(updated.lectureHandoutLatex()).isEqualTo(original.lectureHandoutLatex());
        assertThat(service.listRecent(context, 10)).extracting(TeachingTaskResponse::taskId)
                .containsExactly(original.taskId());
    }

    @Test
    void preservesFailedProgressAndResumesTheSameTaskId() throws Exception {
        Path root = createTextbookCorpus();
        InMemoryTeachingTaskStore taskStore = new InMemoryTeachingTaskStore();
        TeachingWorkflowService service = new TeachingWorkflowService(
                root,
                retrievalService(),
                taskStore,
                memoryReuseService(),
                null,
                new InMemoryAgentTraceStore(),
                new TeachingHandoutTemplateService(),
                Optional.empty(),
                Runnable::run);
        service.setTeachingHandoutAiClientForTesting(
                TeachingHandoutAiClientFixture.failing(new IllegalStateException("temporary Python handout outage")));
        TeachingRequestContext context = new TeachingRequestContext("tenant-a", "teacher", "teacher-1", "device-1");

        TeachingTaskResponse failed = service.submit(
                new TeachingTaskRequest("req-resume-failed", "函数新概念 D(x_0)", "函数新定义", 3),
                context);
        TeachingTaskResponse failedSnapshot = service.get(failed.taskId(), context).orElseThrow();

        assertThat(failedSnapshot.status()).isEqualTo(TeachingTaskStatus.FAILED);
        assertThat(failedSnapshot.nodes()).isNotEmpty();
        assertThat(failedSnapshot.workflowEvents()).isNotEmpty();
        assertThat(failedSnapshot.stageTimings()).isNotEmpty();
        assertThat(failedSnapshot.evidence()).isNotEmpty();
        assertThat(failedSnapshot.errorMessage()).contains("temporary Python handout outage");

        TeachingWorkflowService recoverableService = new TeachingWorkflowService(
                root,
                retrievalService(),
                taskStore,
                memoryReuseService(),
                null,
                new InMemoryAgentTraceStore(),
                new TeachingHandoutTemplateService(),
                Optional.empty(),
                Runnable::run);
        useCompletedPythonHandoutClient(recoverableService);
        TeachingTaskResponse resumed = recoverableService.resume(failed.taskId(), context);

        assertThat(resumed.taskId()).isEqualTo(failed.taskId());
        assertThat(resumed.clientRequestId()).isEqualTo(failed.clientRequestId());
        assertThat(resumed.status()).isEqualTo(TeachingTaskStatus.COMPLETED);
        assertThat(resumed.teacherHandoutLatex()).isNotBlank();
    }

    /** A fatal model-client failure must become a recoverable task state instead of leaving history stuck on RUNNING. */
    @Test
    void persistsARecoverableFailureWhenTheAsyncTaskThrowsAnError() throws Exception {
        Path root = createTextbookCorpus();
        TeachingWorkflowService service = new TeachingWorkflowService(
                root, retrievalService(), new InMemoryTeachingTaskStore(), memoryReuseService(),
                null, new InMemoryAgentTraceStore(),
                new TeachingHandoutTemplateService(), Optional.empty(), Runnable::run);
        service.setTeachingHandoutAiClientForTesting(
                TeachingHandoutAiClientFixture.failing(new AssertionError("fatal Python handout client failure")));
        TeachingRequestContext context = new TeachingRequestContext("tenant-a", "teacher", "teacher-1", "device-1");

        TeachingTaskResponse created = service.submit(
                new TeachingTaskRequest("req-fatal-draft", "函数定义", "函数新定义", 3), context);

        TeachingTaskResponse snapshot = service.get(created.taskId(), context).orElseThrow();
        assertThat(snapshot.status()).isEqualTo(TeachingTaskStatus.FAILED);
        assertThat(snapshot.errorMessage()).contains("fatal Python handout client failure");
        assertThat(snapshot.nodes()).isNotEmpty();
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
                null,
                new InMemoryAgentTraceStore(),
                new com.doob.mathagent.teaching.service.TeachingHandoutTemplateService(),
                Optional.of(questionBankService),
                Runnable::run);
        service.setTeachingHandoutAiClientForTesting(TeachingHandoutAiClientFixture.fromDocuments(
                "【知识定位】双曲线定义与参数关系基础题。\n"
                        + "【方法步骤】步骤：1. 由 $2a=6$ 得 $a=3$。\n"
                        + "【答案与评分点】$c=5$，$b^2=16$；评分点：补充1：写出参数关系得分。\n"
                        + "【易错提醒】补充1：注意 $b^2$ 不是 b。",
                "【知识速记】双曲线参数关系。\n"
                        + "【题型识别】先由条件确定参数。\n"
                        + "【例题任务】写出 $2a=6$ 后求 $a$。\n"
                        + "【练习任务】1. 用同类条件完成参数计算。",
                "双曲线参数关系投影讲解。",
                List.of("双曲线定义与参数关系"),
                List.of()));

        TeachingRequestContext context = new TeachingRequestContext("tenant-a", "teacher", "teacher-1", "device-1");
        TeachingTaskResponse created = service.submit(
                new TeachingTaskRequest("req-question-bank-handout", "双曲线参数怎么求", "双曲线定义与参数关系", 3),
                context);
        TeachingTaskResponse response = service.get(created.taskId(), context).orElseThrow();

        assertThat(response.evidence())
                .anySatisfy(item -> assertThat(item.sourceScope()).isEqualTo("QUESTION_BANK"));
        assertThat(response.teacherHandoutLatex())
                .contains("双曲线定义与参数关系", "$c=5$", "$b^2=16$",
                        "步骤：1. 由 $2a=6$ 得 $a=3$", "补充1：注意 $b^2$ 不是 b", "\\paragraph{答案与评分点}")
                .doesNotContain("\"answer\"", "\"steps\"", "\"scoring\"", "\"extraNote\"",
                        "双曲线定义与参数关系基础题 / 难度：A 基础", "教师版保留完整答案");
        assertThat(response.studentHandoutLatex())
                .contains("\\section{题型：双曲线定义与参数关系}", "\\paragraph{知识速记}", "\\paragraph{自检任务}", "\\vspace{18em}")
                .doesNotContain("作答区", "手写区", "留白区", "推导区", "板书区");
        assertThat(response.studentHandoutLatex())
                .doesNotContain("答案要点", "answer", "steps", "extraNote", "c=5", "scoring", "评分", "得分");
    }

    @Test
    void excludesUnrelatedQuestionBankEvidenceBeforeItCanPolluteHandout() throws Exception {
        Path root = createTextbookCorpus();
        KnowledgeQuestionBankService questionBankService = new KnowledgeQuestionBankService(new InMemoryKnowledgeQuestionBankStore());
        questionBankService.createQuestion(
                "tenant-a",
                "teacher",
                "teacher-1",
                new QuestionBankItemCreateRequest(
                        "圆锥与二面角最大值",
                        "1. 在圆锥中求二面角和异面直线夹角的最大值。\n"
                                + "2. 已知二次函数，求闭区间上的最小值。",
                        "{}",
                        "medium",
                        "TEACHER_PRIVATE",
                        List.of()));
        TeachingWorkflowService service = new TeachingWorkflowService(
                root,
                retrievalService(),
                new InMemoryTeachingTaskStore(),
                memoryReuseService(),
                null,
                new InMemoryAgentTraceStore(),
                new TeachingHandoutTemplateService(),
                Optional.of(questionBankService),
                Runnable::run);
        useCompletedPythonHandoutClient(service);

        TeachingTaskResponse response = service.submit(
                new TeachingTaskRequest(
                        "req-unrelated-question-bank",
                        "已知 f(x)=x^2-2ax+1，求它在 [0,2] 上的最小值。",
                        "含参数的一元二次函数最值",
                        3),
                new TeachingRequestContext("tenant-a", "teacher", "teacher-1", "device-1"));

        assertThat(response.evidence())
                .noneMatch(item -> "QUESTION_BANK".equals(item.sourceScope()))
                .noneMatch(item -> item.snippet().contains("圆锥"));
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
                null,
                new InMemoryAgentTraceStore(),
                new com.doob.mathagent.teaching.service.TeachingHandoutTemplateService(),
                Optional.of(questionBankService),
                Runnable::run);
        useCompletedPythonHandoutClient(service);

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
        assertThat(response.teacherHandoutLatex()).doesNotContain("四棱柱线面角基础题");
        assertThat(response.studentHandoutLatex()).doesNotContain("四棱柱", "建立空间直角坐标系", "法向量");
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
                null,
                new InMemoryAgentTraceStore(),
                new com.doob.mathagent.teaching.service.TeachingHandoutTemplateService(),
                Optional.empty(),
                Optional.of(TeacherResourceBlockSearchServiceFixture.service(resourceStore, blockStore)),
                Runnable::run);
        useCompletedPythonHandoutClient(service);

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
        assertThat(response.teacherHandoutLatex()).contains("\\section{题型：双曲线}")
                .doesNotContain("\\section{本节目标}", "核心方法", "解题步骤")
                .doesNotContain("圆锥曲线专题讲义 / 圆锥曲线 / 双曲线",
                        "题型方法、教师沉淀与讲义补充", "source_page_image", "## 正文");
        assertThat(response.studentHandoutLatex())
                .doesNotContain("双曲线定义与渐近线：到两个定点距离差的绝对值为常数", "渐近线可以辅助判断图形与解题方向");
    }

    @Test
    void bindsTeacherRagEvidenceToKnowledgePointWhenSourceUsesAnEquivalentSpecificTerm() throws Exception {
        var method = TeachingWorkflowService.class.getDeclaredMethod(
                "supportingEvidenceForPoint", String.class, List.class, List.class);
        method.setAccessible(true);
        TeachingEvidence teacherEvidence = new TeachingEvidence(
                "TEACHER_RESOURCE",
                "函数新概念精讲 / 定义域",
                "teacher-definition-domain",
                8,
                "判断新定义表达式的定义域时，先列出分母不为零、根式内非负等限制条件。");
        TeachingEvidence broadButWrongEvidence = new TeachingEvidence(
                "TEACHER_RESOURCE",
                "函数基础 / 定义域",
                "generic-definition-domain",
                3,
                "函数定义域的一般限制条件。");

        @SuppressWarnings("unchecked")
        List<TeachingEvidence> supports = (List<TeachingEvidence>) method.invoke(
                null,
                "函数新概念：定义域判断",
                List.of(),
                List.of(teacherEvidence, broadButWrongEvidence));

        assertThat(supports).containsExactly(teacherEvidence);
    }

    @Test
    void storesCoursewareAgentTraceForRealAiDraftRuns() throws Exception {
        Path root = createTextbookCorpus();
        InMemoryAgentTraceStore traceStore = new InMemoryAgentTraceStore();
        TeachingWorkflowService service = new TeachingWorkflowService(
                root,
                retrievalService(),
                new InMemoryTeachingTaskStore(),
                memoryReuseService(),
                null,
                traceStore);
        service.setTeachingHandoutAiClientForTesting(TeachingHandoutAiClientFixture.fromDraft(
                "【知识定位】先读清 $D(x_0)$ 的定义。\n"
                        + "【方法步骤】1. 写出定义中的自变量位置。\n"
                        + "2. 用 $$c^2=a^2+b^2$$ 这类参数关系示范公式排版，再把 -1 代入。\n"
                        + "【答案与评分点】关键是代入位置与定义域检查。",
                "【知识速记】先找到 $D(x_0)$ 里的自变量位置，记住 c²=a²+b² 这类公式要先写清。\n"
                        + "【练习任务】- 先写出定义：___\n- 独立完成 D(0)：___",
                List.of("函数新定义", "定义域", "参数关系 c²=a²+b²"),
                List.of("已知条件与目标：逐步说明定义、代入与结论。")));

        TeachingTaskResponse response = service.submit(
                new TeachingTaskRequest("req-ai-trace", "已知函数 f(x) 的定义域为 R，求 D(-1)", "理解函数新定义题", 2),
                new TeachingRequestContext("tenant-a", "teacher", "teacher-1", "device-1"));

        List<AgentTraceRecord> traces = traceStore.search(new AgentTraceSearchCriteria(
                "tenant-a", "teacher", "teacher-1", "CoursewareAgent", "COMPLETED", 10));

        assertThat(traces).hasSize(2);
        AgentTraceRecord trace = traces.stream()
                .filter(candidate -> response.taskId().equals(candidate.planId()))
                .findFirst()
                .orElseThrow();
        assertThat(trace.planId()).isEqualTo(response.taskId());
        assertThat(trace.actualUsage().totalTokens()).isEqualTo(34);
        assertThat(trace.evidenceRefs()).isNotNull();
        assertThat(trace.message()).contains("Teaching AI draft structured");
        assertThat(trace.diagnosticEvents()).extracting(AgentTraceRecord.DiagnosticEvent::eventType)
                .containsExactly("PYTHON_HANDOUT_TEACHER_WRITER");
        assertThat(response.teacherHandoutLatex()).contains("\\section{题型：函数新概念}", "\\subsection*{讲解}", "\\subsection*{注意}")
                .doesNotContain("\\section{本节目标}", "题目入口", "讲评入口", "审题提醒", "模板：", "16:10 横版讲解卡", "来源依据");
        assertThat(response.lectureHandoutLatex())
                .contains("\\section{课堂讲解}", "\\subsection*{第 1 题 / 讲解单元}", "\\vspace{14em}")
                .doesNotContain("教师手写区", "手写区", "板书留白");
        assertThat(response.teacherHandoutLatex()).contains("$D(x_0)$", "$$c^2=a^2+b^2$$", "$c^2=a^2+b^2$");
        assertThat(response.teacherHandoutLatex()).contains("写出定义中的自变量位置");
        assertThat(response.teacherHandoutLatex()).doesNotContain("\\$D", "c\\textasciicircum{}2");
        assertThat(response.draftSections().teacherExplanation())
                .contains("$D(x_0)$", "【方法步骤】")
                .doesNotContain("PDF 版式要求", "页眉", "页脚", "讲评色");
        assertThat(response.draftSections().studentWorksheet())
                .contains("【知识速记】", "$c^2=a^2+b^2$")
                .doesNotContain("答案：", "JSON_PARSE", "参考答案");
        assertThat(response.draftSections().lectureCards())
                .isNotEmpty()
                .first()
                .asString()
                .contains("已知条件与目标");
        assertThat(response.draftSections().exercises())
                .isNotEmpty()
                .allSatisfy(item -> assertThat(item).doesNotContain("参考答案", "得分"));
        assertThat(response.draftSections().risks())
                .contains("student_answer_leakage_review_required", "lecture_cards_from_python_handout");
        assertThat(response.draftReview().status()).isEqualTo("NEEDS_ATTENTION");
        assertThat(response.draftReview().findings())
                .extracting(finding -> finding.reviewerCode())
                .contains("StudentLeakageReviewer")
                .doesNotContain("LectureCardReviewer");
        assertThat(response.draftReview().findings())
                .anySatisfy(finding -> {
                    assertThat(finding.reviewerCode()).isEqualTo("StudentLeakageReviewer");
                    assertThat(finding.severity()).isEqualTo("warning");
                    assertThat(finding.sectionCode()).isEqualTo("studentWorksheet");
                });
        assertThat(response.draftReview().patches())
                .extracting(patch -> patch.targetSectionCode())
                .contains("studentWorksheet")
                .doesNotContain("lectureCards");
        assertThat(response.studentHandoutLatex()).doesNotContain("\\section{第 1 讲");
        assertThat(response.studentHandoutLatex()).doesNotContain("\\section{我的解答}", "\\section{订正记录}", "\\vspace{12em}");
        assertThat(response.studentHandoutLatex()).contains("\\paragraph{知识速记}", "$D(x_0)$", "$c^2=a^2+b^2$");
        assertThat(response.studentHandoutLatex()).contains("\\begin{itemize}");
        assertThat(response.studentHandoutLatex()).doesNotContain("【答案与评分点】", "答案：", "得分", "___");
        assertThat(response.teacherHandoutLatex())
                .doesNotContain("tokens=", "\\paragraph{模型}", "PDF 版式要求", "页眉", "页脚", "讲评色", "MODEL_CALL", "JSON_PARSE");
        assertThat(response.studentHandoutLatex())
                .doesNotContain("tokens=", "PDF 版式要求", "页眉", "页脚", "讲评色", "MODEL_CALL", "JSON_PARSE", "参考答案",
                        "生成后保存一次教师版编辑并导出 PDF", "教师版");
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
        TeachingWorkflowService service = new TeachingWorkflowService(
                root,
                retrievalService(),
                new InMemoryTeachingTaskStore(),
                memoryReuseService(),
                null,
                new InMemoryAgentTraceStore());
        service.setTeachingHandoutAiClientForTesting(TeachingHandoutAiClientFixture.fromDraft(
                "【知识定位】反比例函数通常写作 $y=k/x$，其中 $k\\ne 0$。\n"
                        + "【题型识别】看到图像上一点就代入解析式。\n"
                        + "【方法步骤】1. 设 $y=k/x$。\n2. 代入点坐标求 $k$。\n"
                        + "【例题详解】已知点在图像上，代入 $y=k/x$ 建立方程。\n"
                        + "【答案与评分点】教师版保留 $y=k/x$ 的代入方程和最终答案。\n"
                        + "【易错提醒】不要把 k 的符号判断反。",
                "【知识速记】反比例函数可写为 $y=k/x$，先判断 $k\\ne 0$。\n"
                        + "【题型识别】看到点坐标就尝试代入。\n"
                        + "【例题任务】已知一点在图像上，先写出解析式空格。\n"
                        + "【练习任务】1. 写出 $y=k/x$ 的定义式。\n2. 判断点是否在图像上。\n"
                        + "【作答提醒】先写公式，再代入。",
                List.of("反比例函数 $y=k/x$", "k 的几何意义", "点在图像上就满足解析式"),
                List.of("反比例函数投影讲解。")));

        TeachingTaskResponse response = service.submit(
                new TeachingTaskRequest("req-normalize-fraction", "函数新概念 D(x_0)", "反比例函数从概念到基础题型", 2),
                new TeachingRequestContext("tenant-a", "teacher", "teacher-1", "device-1"));

        assertThat(response.teacherHandoutLatex())
                .contains("$y=\\frac{k}{x}$")
                .doesNotContain("$y=k/x$");
        assertThat(response.studentHandoutLatex())
                .contains("$y=\\frac{k}{x}$")
                .doesNotContain("$y=k/x$", "教师版保留", "最终答案", "答案与评分点");
        assertThat(response.studentHandoutLatex())
                .contains("\\paragraph{知识速记}", "\\paragraph{识别信号}");
    }

    private TeachingWorkflowService service(Path root) {
        return service(root, memoryReuseService());
    }

    private TeachingWorkflowService service(Path root, StudentMemoryReuseService memoryReuseService) {
        TeachingWorkflowService service = new TeachingWorkflowService(
                root,
                retrievalService(),
                new InMemoryTeachingTaskStore(),
                memoryReuseService,
                null,
                new InMemoryAgentTraceStore());
        service.setTeachingHandoutAiClientForTesting(TeachingHandoutAiClientFixture.completed());
        return service;
    }

    private static void useCompletedPythonHandoutClient(TeachingWorkflowService service) {
        service.setTeachingHandoutAiClientForTesting(TeachingHandoutAiClientFixture.completed());
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

    /** Creates a block whose source path/checksum model the stable identity retained across Feishu mirror syncs. */
    private static TeacherDocumentBlockResponse teacherBlockWithSourcePath(
            String blockId,
            String documentId,
            String sourcePath,
            String section,
            String text,
            String checksum) {
        return new TeacherDocumentBlockResponse(
                blockId,
                documentId,
                sourcePath + ":1",
                "markdown",
                1,
                "七、涂色问题",
                section,
                1,
                "1",
                sourcePath,
                "question",
                text,
                text.toLowerCase(),
                "[]",
                "[]",
                "[]",
                "[]",
                checksum,
                1.0,
                "active");
    }

    private static String escape(Path path) {
        return path.toString().replace("\\", "\\\\");
    }

    private static final class GateTextbookRetrievalService extends TextbookRetrievalService {
        private final CountDownLatch secondaryRetrievalsStarted;
        private final AtomicBoolean observedParallelPeers;

        private GateTextbookRetrievalService(
                CountDownLatch secondaryRetrievalsStarted,
                AtomicBoolean observedParallelPeers) {
            super(
                    new TextbookCatalogReader(),
                    new TextbookChunkReader(),
                    new LocalTextbookBm25SearchEngine(),
                    new NoopRetrievalAuditSink(),
                    new DisabledTextbookSearchCache(),
                    new RedisTextbookSearchCacheProperties(false, "math-agent:test:parallel-evidence", Duration.ofMinutes(1), Duration.ofMinutes(1)),
                    TeacherResourceGraphAlignmentService.disabled(),
                    new TextbookPageImageService(new TextbookCatalogReader()),
                    TestVectorIndexService.successful(
                            new InMemoryTeacherResourceStore(),
                            new InMemoryTeacherDocumentBlockStore()));
            this.secondaryRetrievalsStarted = secondaryRetrievalsStarted;
            this.observedParallelPeers = observedParallelPeers;
        }

        @Override
        public TextbookSearchResponse search(
                Path processedBooksRoot,
                TextbookSearchRequest request,
                RetrievalRequestContext requestContext) {
            try {
                observedParallelPeers.set(secondaryRetrievalsStarted.await(1, TimeUnit.SECONDS));
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while waiting for parallel evidence retrievals", exception);
            }
            return new TextbookSearchResponse("textbook-query", request.query(), request.limit(), "gate", 0, List.of());
        }
    }

    private static final class GateQuestionBankService extends KnowledgeQuestionBankService {

        private GateQuestionBankService() {
            super(new InMemoryKnowledgeQuestionBankStore());
        }

        @Override
        public List<QuestionBankItemResponse> searchQuestions(
                String tenantId,
                String viewerRole,
                String viewerSubjectId,
                String query,
                int limit) {
            return List.of();
        }
    }

    private static final class GateTeacherResourceBlockSearchService extends TeacherResourceBlockSearchService {
        private final CountDownLatch secondaryRetrievalsStarted;

        private GateTeacherResourceBlockSearchService(CountDownLatch secondaryRetrievalsStarted) {
            super(
                    new InMemoryTeacherResourceStore(),
                    new InMemoryTeacherDocumentBlockStore(),
                    event -> {
                    },
                    TestVectorIndexService.successful(
                            new InMemoryTeacherResourceStore(),
                            new InMemoryTeacherDocumentBlockStore()));
            this.secondaryRetrievalsStarted = secondaryRetrievalsStarted;
        }

        @Override
        public TeacherResourceBlockSearchResponse search(
                String tenantId,
                String viewerRole,
                String viewerSubjectId,
                String query,
                int limit,
                String endpoint) {
            secondaryRetrievalsStarted.countDown();
            return new TeacherResourceBlockSearchResponse("teacher-query", query, limit, "gate", 0, List.of());
        }
    }

    private static final class DisabledTextbookSearchCache implements TextbookSearchCache {
        @Override
        public Optional<CachedTextbookSearch> find(String cacheKey) {
            return Optional.empty();
        }

        @Override
        public void put(String cacheKey, CachedTextbookSearch value, Duration ttl) {
            // This test cache is intentionally disabled; the gate service overrides search directly.
        }
    }
}
