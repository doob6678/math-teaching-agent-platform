package com.doob.mathagent.agent.service;

import com.doob.mathagent.agent.service.PythonMigratedWorkloadClient.AnimatedLessonResult;
import com.doob.mathagent.agent.vo.AgentRunExecuteResponse;
import com.doob.mathagent.agent.vo.MultiAgentWritingResponse;
import com.doob.mathagent.agent.worker.AgentWorkerRabbitConfiguration;
import com.doob.mathagent.agent.worker.AgentWorkerTask;
import com.doob.mathagent.agent.worker.AgentWorkerTaskDispatchService;
import com.doob.mathagent.agent.worker.AgentWorkerTaskStore;
import com.doob.mathagent.infrastructure.security.RequestSubject;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

/**
 * 「动画讲题」workload 的 Java 控制面：签发 durable 任务、执行 Worker 命令并持久化 Python 结果。
 *
 * <p>边界（AGENTS.md）：Java 只做鉴权、排队、持久化与产物分发；题干到分镜/视频的一切教学语义
 * 由 Python worker 独占，本服务原样保存 worker 响应 JSON，不补写、不改写字段。</p>
 *
 * <p>落库选型（2026-09-09 调研结论）：agent_worker_task.workflow_id 存在指向
 * multi_agent_writing_workflow 的外键，任务必须先有所有权行；因此复用讲义同款「workflow 行 +
 * agent_worker_task + outbox」通道（零扩表）。结果 JSON 落在 multi_agent_writing_workflow
 * 现有的 metadata_json 列里、以单条 stageCode=animated_lesson 的 stage 承载 generatedContent——
 * 与讲义把每个 Python stage 完整 JSON 存进同一列的做法一致（MyBatisMultiAgentWritingWorkflowStore
 * 的 merge 语义按 stageCode 幂等替换，重复投递不会堆叠）。不再另写 result.json 文件层，避免双写漂移；
 * 大产物（mp4/storyboard）由 worker 写共享目录，Java 只按响应路径流式分发。</p>
 */
@Service
public class AnimatedLessonService {

    /** Python AnimatedLessonRunRequest 对 problemText 的契约区间。 */
    private static final int PROBLEM_MIN_CHARS = 10;
    private static final int PROBLEM_MAX_CHARS = 20_000;
    /** 与 worker LESSON_ID_RE 同值：产物目录名进文件路径与子进程参数，必须限制为小写 slug。 */
    private static final Pattern LESSON_ID_PATTERN = Pattern.compile("^[a-z0-9][a-z0-9-]{0,62}$");
    /** worker 产物目录名固定以 animated-lessons 为段，Java 用它把容器绝对路径重挂到本机可见根。 */
    private static final String ARTIFACT_DIRECTORY_MARKER = "animated-lessons/";

    private final AgentWorkerTaskDispatchService dispatchService;
    private final AgentWorkerTaskStore taskStore;
    private final MultiAgentWritingWorkflowStore workflowStore;
    private final PythonMigratedWorkloadClient workloadClient;
    private final Environment environment;
    private final ObjectMapper objectMapper;

    public AnimatedLessonService(
            AgentWorkerTaskDispatchService dispatchService,
            AgentWorkerTaskStore taskStore,
            MultiAgentWritingWorkflowStore workflowStore,
            PythonMigratedWorkloadClient workloadClient,
            Environment environment,
            ObjectMapper objectMapper) {
        this.dispatchService = dispatchService;
        this.taskStore = taskStore;
        this.workflowStore = workflowStore;
        this.workloadClient = workloadClient;
        this.environment = environment;
        this.objectMapper = objectMapper;
    }

    /**
     * 校验并排队一次动画讲题任务。runId 采用任务 id（UUID 稳定、重试不变），worker 侧据此推导 lesson 目录。
     *
     * @return 已入队的 durable task；taskId 即对外的轮询句柄
     */
    public AgentWorkerTask submit(RequestSubject subject, AnimatedLessonSubmission submission) {
        RequestSubject normalizedSubject = subject == null ? RequestSubject.anonymous(null, null) : subject.normalize();
        if (normalizedSubject.subjectId() == null || normalizedSubject.subjectId().isBlank()) {
            throw new IllegalArgumentException("Animated lesson requires a signed-in subject");
        }
        AnimatedLessonSubmission normalized = submission == null ? null : submission.normalize();
        if (normalized == null || normalized.problemText().length() < PROBLEM_MIN_CHARS
                || normalized.problemText().length() > PROBLEM_MAX_CHARS) {
            throw new IllegalArgumentException("problemText must contain 10 to 20000 characters");
        }
        if (normalized.storyboard() != null && !normalized.storyboard().isObject()) {
            throw new IllegalArgumentException("storyboard must be a JSON object when provided");
        }
        if (!normalized.hasValidLessonId()) {
            throw new IllegalArgumentException("lessonId must be a lowercase slug matching [a-z0-9-]");
        }
        String workflowId = UUID.randomUUID().toString();
        Instant createdAt = Instant.now();
        MultiAgentWritingWorkflowRecord workflow = new MultiAgentWritingWorkflowRecord(
                workflowId, normalizedSubject.tenantId(), normalizedSubject.subjectType(),
                normalizedSubject.subjectId(), "RUNNING", createdAt, createdAt, List.of(),
                new AgentRunExecuteResponse.TokenUsage(0, 0, 0),
                "Animated lesson task queued; dispatch pending.");
        // 身份不写入 payload（重投递时从 workflow 行复原）；六键之外无业务字段，保持与讲义同款的 {"request":...} 包装。
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("problemText", normalized.problemText());
        request.put("lessonId", normalized.lessonId().isBlank() ? null : normalized.lessonId());
        request.put("render", normalized.render());
        request.put("storyboard", normalized.storyboard());
        String requestJson;
        try {
            requestJson = objectMapper.writeValueAsString(Map.of("request", request));
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to serialize animated lesson payload", exception);
        }
        return dispatchService.create(
                workflow,
                AgentWorkerRabbitConfiguration.ANIMATED_LESSON_AGENT_CODE,
                AgentWorkerRabbitConfiguration.ANIMATED_LESSON_STAGE_CODE,
                requestJson);
    }

    /**
     * Worker 领取后执行一次长调用并把响应原文持久化到 workflow 行。
     *
     * <p>失败时直接抛出：租约、重试计数与终态 FAILED 全部由 Agent Worker 既有机制负责，
     * 这里保持 workflow 行 RUNNING，避免半程暴露假终态（与讲义 executePythonHandout 的注释同因）。</p>
     */
    public void executeDispatched(AgentWorkerTask task) {
        MultiAgentWritingWorkflowRecord workflow = workflowStore.findByIdInternal(task.workflowId())
                .orElseThrow(() -> new IllegalArgumentException("Animated lesson workflow not found"));
        try {
            JsonNode request = objectMapper.readTree(task.requestJson()).required("request");
            long startedNanos = System.nanoTime();
            AnimatedLessonResult result = workloadClient.runAnimatedLesson(
                    task.taskId(),
                    request.required("problemText").asText(),
                    request.path("lessonId").asText(""),
                    request.path("render").asBoolean(true),
                    request.path("storyboard"));
            long elapsedMs = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos);
            JsonNode root = objectMapper.readTree(result.rawJson());
            JsonNode usage = root.path("usage");
            MultiAgentWritingResponse.StageResult stage = new MultiAgentWritingResponse.StageResult(
                    AgentWorkerRabbitConfiguration.ANIMATED_LESSON_STAGE_CODE,
                    AgentWorkerRabbitConfiguration.ANIMATED_LESSON_AGENT_CODE,
                    task.workflowId() + ":" + AgentWorkerRabbitConfiguration.ANIMATED_LESSON_STAGE_CODE,
                    result.providerName(),
                    result.modelCode(),
                    "COMPLETED",
                    new AgentRunExecuteResponse.TokenUsage(
                            Math.max(0, usage.path("promptTokens").asInt(0)),
                            Math.max(0, usage.path("completionTokens").asInt(0)),
                            Math.max(0, usage.path("totalTokens").asInt(0))),
                    "Animated lesson completed.",
                    result.rawJson(),
                    elapsedMs,
                    List.of(),
                    List.of());
            workflowStore.save(new MultiAgentWritingWorkflowRecord(
                    workflow.workflowId(), workflow.tenantId(), workflow.subjectType(), workflow.subjectId(),
                    "COMPLETED", workflow.createdAt(), Instant.now(), List.of(stage), workflow.totalUsage(),
                    "Animated lesson completed."));
        } catch (RuntimeException exception) {
            // 原样上抛给 Agent Worker 的重试/终态机制处理，这里不吞异常也不改写失败语义。
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException("Animated lesson execution failed", exception);
        }
    }

    /**
     * 读取当前主体可见的任务视图；不可见（他人任务/非本 stage/未知 id）一律 empty → 控制器返回 404。
     */
    public Optional<AnimatedLessonView> findOwned(String taskId, RequestSubject subject) {
        Optional<AgentWorkerTask> task = taskStore.find(taskId)
                .filter(candidate -> AgentWorkerRabbitConfiguration.ANIMATED_LESSON_STAGE_CODE
                        .equals(candidate.stageCode()));
        if (task.isEmpty()) {
            return Optional.empty();
        }
        return workflowStore.findVisible(task.get().workflowId(), subject.normalize())
                .map(workflow -> new AnimatedLessonView(task.get(), workflow));
    }

    /** meta 响应的 result 节点：COMPLETED 时返回 worker 响应原文，其余为 null。 */
    public JsonNode resultJson(MultiAgentWritingWorkflowRecord workflow) {
        return workflow.stages().stream()
                .filter(stage -> AgentWorkerRabbitConfiguration.ANIMATED_LESSON_STAGE_CODE
                        .equals(stage.stageCode()))
                .filter(stage -> "COMPLETED".equals(stage.status()) && !stage.generatedContent().isBlank())
                .findFirst()
                .flatMap(stage -> {
                    try {
                        return Optional.of(objectMapper.readTree(stage.generatedContent()));
                    } catch (Exception exception) {
                        return Optional.empty();
                    }
                })
                .orElse(null);
    }

    /**
     * 把响应里的 videoPath 解析为 Java 进程可读的文件。
     *
     * <p>worker 返回的是其容器内绝对路径；compose 将宿主 ./output/animated-lessons 以同一容器路径
     * 挂进 ai-worker 与 backend，因此绝对路径在两侧同时成立。跨环境（本机 dev 直跑 Java）时按
     * "animated-lessons/" 标记把尾部相对段重挂到 {@code math-agent.animated-lesson.worker-output-root}。
     * 任何解析结果都必须仍位于该根目录内，杜绝响应字段被伪造成任意文件路径。</p>
     */
    public Optional<Path> videoFile(MultiAgentWritingWorkflowRecord workflow) {
        JsonNode result = resultJson(workflow);
        String videoPath = result == null ? "" : result.path("videoPath").asText("");
        if (videoPath.isBlank()) {
            // render=false 的任务没有视频，属正常缺省而非错误。
            return Optional.empty();
        }
        Path root = Path.of(environment.getProperty(
                "math-agent.animated-lesson.worker-output-root", "/app/data/animated-lessons"))
                .toAbsolutePath().normalize();
        String candidate = videoPath.replace('\\', '/');
        Path direct = Path.of(candidate).normalize();
        if (direct.isAbsolute() && direct.startsWith(root) && Files.isRegularFile(direct)) {
            return Optional.of(direct);
        }
        int marker = candidate.lastIndexOf(ARTIFACT_DIRECTORY_MARKER);
        String tail = marker >= 0 ? candidate.substring(marker + ARTIFACT_DIRECTORY_MARKER.length()) : candidate;
        Path relocated = root.resolve(tail.startsWith("/") ? tail.substring(1) : tail).normalize();
        if (relocated.startsWith(root) && Files.isRegularFile(relocated)) {
            return Optional.of(relocated);
        }
        return Optional.empty();
    }

    /** 创建请求体：problemText 是唯一教学输入；lessonId/storyboard 为高级选项，可为 null。 */
    public record AnimatedLessonSubmission(String problemText, String lessonId, Boolean render, JsonNode storyboard) {

        /** 归一化输入，未提供渲染开关时保持 worker 合同默认 render=true。 */
        public AnimatedLessonSubmission normalize() {
            return new AnimatedLessonSubmission(
                    problemText == null ? "" : problemText.strip(),
                    lessonId == null ? "" : lessonId.strip(),
                    render == null || render,
                    storyboard);
        }

        /** lessonId 提前按 worker 的 slug 合同校验，避免付费任务在 Python 边界才被 422 拒绝。 */
        public boolean hasValidLessonId() {
            return lessonId().isBlank() || LESSON_ID_PATTERN.matcher(lessonId()).matches();
        }
    }

    /** 鉴权通过后的「任务 + 所有权行」组合视图。 */
    public record AnimatedLessonView(AgentWorkerTask task, MultiAgentWritingWorkflowRecord workflow) {
    }
}
