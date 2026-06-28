package com.doob.mathagent.teaching;

import com.doob.mathagent.retrieval.RetrievalRequestContext;
import com.doob.mathagent.retrieval.TextbookRetrievalService;
import com.doob.mathagent.retrieval.TextbookSearchHit;
import com.doob.mathagent.retrieval.TextbookSearchRequest;
import com.doob.mathagent.retrieval.TextbookSearchResponse;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * 教学任务 DAG 编排服务：把用户学习目标、资源复用、教材检索、ReAct 解题和讲义生成串成可恢复任务。
 */
@Service
public class TeachingWorkflowService {

    private final Path processedBooksRoot;
    private final TextbookRetrievalService retrievalService;
    private final TeachingTaskStore taskStore;

    /**
     * 创建教学编排服务。
     *
     * @param processedBooksRoot 教材 processed_books 根目录。
     * @param retrievalService 教材 BM25-first 检索服务。
     * @param taskStore 任务存储，用于恢复和隔离。
     */
    public TeachingWorkflowService(
            Path processedBooksRoot,
            TextbookRetrievalService retrievalService,
            TeachingTaskStore taskStore) {
        this.processedBooksRoot = processedBooksRoot.toAbsolutePath().normalize();
        this.retrievalService = retrievalService;
        this.taskStore = taskStore;
    }

    /**
     * 提交教学任务；同一主体同一 clientRequestId 重复提交时直接返回已有任务。
     */
    public TeachingTaskResponse submit(TeachingTaskRequest request, TeachingRequestContext context) {
        TeachingRequestContext normalizedContext = context.normalize();
        String ownerKey = normalizedContext.ownerKey();
        String idempotencyKey = normalizedContext.idempotencyKey(request.clientRequestId());
        Optional<TeachingTaskResponse> existing = taskStore.findByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) {
            return existing.get();
        }
        TeachingTaskResponse created = execute(request, normalizedContext);
        return taskStore.save(ownerKey, idempotencyKey, created);
    }

    /**
     * 按 taskId 查询当前主体拥有的教学任务。
     */
    public Optional<TeachingTaskResponse> get(String taskId, TeachingRequestContext context) {
        return taskStore.findByTaskIdAndOwnerKey(taskId, context.normalize().ownerKey());
    }

    /**
     * 执行固定 DAG：学习目标识别、资源复用、公开教材检索、私有飞书占位、练习题占位、ReAct、LaTeX 讲义、交互建议。
     */
    private TeachingTaskResponse execute(TeachingTaskRequest request, TeachingRequestContext context) {
        TextbookSearchResponse retrieval = retrievalService.search(
                processedBooksRoot,
                new TextbookSearchRequest(retrievalQuery(request), request.evidenceLimit()),
                new RetrievalRequestContext(
                        context.tenantId(),
                        context.subjectType(),
                        context.subjectId(),
                        null,
                        context.deviceId(),
                        "teaching-workflow",
                        "/api/teaching/tasks"));
        List<TeachingEvidence> evidence = retrieval.hits().stream()
                .map(this::toEvidence)
                .toList();
        List<TeachingWorkflowNode> nodes = buildNodes(request, evidence);
        List<TeachingReactStep> reactTrace = buildReactTrace(request, evidence);
        return new TeachingTaskResponse(
                UUID.randomUUID().toString(),
                request.clientRequestId(),
                context.tenantId(),
                context.subjectType(),
                context.subjectId(),
                TeachingTaskStatus.COMPLETED,
                request.questionText(),
                request.learningGoal(),
                nodes,
                reactTrace,
                evidence,
                buildHandoutLatex(request, evidence),
                List.of("继续追问定义 D(x_0)", "生成同类练习题", "把讲义导出为 PDF"),
                null);
    }

    /**
     * 构造教材检索 query，把用户想学什么和题目文本合并，优先复用公开教材证据。
     */
    private static String retrievalQuery(TeachingTaskRequest request) {
        return request.learningGoal() + " " + request.questionText();
    }

    /**
     * 把教材检索命中转换为教学证据，明确标注 PUBLIC_TEXTBOOK 作用域。
     */
    private TeachingEvidence toEvidence(TextbookSearchHit hit) {
        return new TeachingEvidence(
                "PUBLIC_TEXTBOOK",
                hit.bookName() + " / " + hit.sectionTitle(),
                hit.chunkId(),
                hit.pageNo(),
                hit.textSnippet());
    }

    /**
     * 构造固定 DAG 节点输出；飞书、练习题和互动讲义先作为可观测占位节点。
     */
    private static List<TeachingWorkflowNode> buildNodes(TeachingTaskRequest request, List<TeachingEvidence> evidence) {
        return List.of(
                node("LEARNING_GOAL", "学习目标识别", "识别用户想学：" + request.learningGoal()),
                node("REUSE_RESOURCE", "历史资源复用", "按租户和用户隔离检查历史任务，当前命中同 clientRequestId 时复用。"),
                node("PUBLIC_TEXTBOOK_RETRIEVAL", "公开教材检索", "命中公开教材证据 " + evidence.size() + " 条。"),
                node("PRIVATE_FEISHU_PLACEHOLDER", "私有飞书文档", "预留 tenantId + subjectId + docScope 隔离的飞书资料检索节点。"),
                node("PRACTICE_DISCOVERY_PLACEHOLDER", "练习题发现", "预留同知识点练习题和错题库召回节点。"),
                node("REACT_SOLVE", "ReAct 解题", "基于证据生成 thought/action/observation/answer 轨迹。"),
                node("LATEX_HANDOUT", "LaTeX 讲义", "生成可导出为 PDF 的讲义草稿。"),
                node("INTERACTIVE_FOLLOW_UP", "交互追问", "给出继续追问、练习和导出建议。"));
    }

    /**
     * 创建已完成 DAG 节点。
     */
    private static TeachingWorkflowNode node(String code, String name, String summary) {
        return new TeachingWorkflowNode(code, name, "completed", summary);
    }

    /**
     * 构造最小 ReAct 轨迹，后续接入大模型后保留同样结构用于审计和回放。
     */
    private static List<TeachingReactStep> buildReactTrace(TeachingTaskRequest request, List<TeachingEvidence> evidence) {
        String observation = evidence.isEmpty()
                ? "未命中教材证据，需要降级到教师提示词和基础知识。"
                : "观察到教材证据：" + evidence.getFirst().sourceTitle();
        return List.of(
                new TeachingReactStep("THOUGHT", "先明确用户想学什么，再找可复用资源和公开教材证据。", null),
                new TeachingReactStep("ACTION", "调用 search_textbook 检索教材定义、例题和相关章节。", "search_textbook"),
                new TeachingReactStep("OBSERVATION", observation, null),
                new TeachingReactStep("ANSWER", "整理为分步讲解和 LaTeX 讲义草稿。", null));
    }

    /**
     * 生成 LaTeX 讲义草稿；当前阶段输出结构，后续会接入更强的排版和 PDF 渲染。
     */
    private static String buildHandoutLatex(TeachingTaskRequest request, List<TeachingEvidence> evidence) {
        String evidenceSnippet = evidence.isEmpty() ? "暂无教材证据。" : escapeLatex(evidence.getFirst().snippet());
        return """
                \\section{学习目标}
                %% 用户想学什么
                %s

                \\section{题目}
                %% 原始题目
                %s

                \\section{证据与讲解}
                %% 公开教材证据，私有资料需按 tenantId/subjectId 隔离后再引用
                %s

                \\section{互动练习}
                继续追问定义 D(x_0)，再生成同类练习题。
                """.formatted(
                escapeLatex(request.learningGoal()),
                escapeLatex(request.questionText()),
                evidenceSnippet);
    }

    /**
     * 最小 LaTeX 转义，避免用户输入中的特殊字符破坏讲义结构。
     */
    private static String escapeLatex(String value) {
        return value == null ? "" : value
                .replace("\\", "\\textbackslash{}")
                .replace("_", "\\_")
                .replace("{", "\\{")
                .replace("}", "\\}");
    }
}
