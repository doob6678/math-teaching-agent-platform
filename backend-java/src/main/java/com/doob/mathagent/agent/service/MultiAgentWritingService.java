package com.doob.mathagent.agent.service;

import com.doob.mathagent.agent.dto.MultiAgentWritingRequest;
import com.doob.mathagent.agent.vo.AgentRunExecuteResponse;
import com.doob.mathagent.agent.vo.MultiAgentWritingResponse;
import com.doob.mathagent.agent.worker.AgentWorkerTaskDispatchService;
import com.doob.mathagent.agent.worker.AgentWorkerRabbitConfiguration;
import com.doob.mathagent.infrastructure.security.RequestSubject;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

/**
 * Java control-plane facade for the Python-owned handout graph.
 *
 * <p>Java persists workflow ownership, publishes one lease-protected Worker command, validates visibility, and
 * projects the bounded Python result into the existing public response and artifact contracts. It never plans or
 * executes a provider stage.</p>
 */
@Service
public class MultiAgentWritingService {

    private static final ObjectMapper ARTIFACT_OBJECT_MAPPER = new ObjectMapper();
    private final MultiAgentWritingWorkflowStore workflowStore;
    private final AgentWorkerTaskDispatchService dispatchService;
    private final Environment environment;
    private final PythonHandoutClient pythonHandoutClient;

    /** Creates the Java control-plane facade for the Python handout runtime. */
    @Autowired
    public MultiAgentWritingService(
            MultiAgentWritingWorkflowStore workflowStore,
            AgentWorkerTaskDispatchService dispatchService,
            Environment environment,
            PythonHandoutClient pythonHandoutClient) {
        this.workflowStore = workflowStore;
        this.dispatchService = dispatchService;
        this.environment = environment;
        this.pythonHandoutClient = pythonHandoutClient;
    }

    /** Runs the complete Python graph synchronously after Java persists the owned workflow row. */
    public MultiAgentWritingResponse run(MultiAgentWritingRequest request, RequestSubject subject) {
        MultiAgentWritingRequest normalized = request.normalize();
        requireLiveModelExecution(normalized);
        RequestSubject normalizedSubject = subject.normalize();
        requireTeacherOrAdmin(normalizedSubject);
        requireConfiguredHandoutRuntime();
        String workflowId = UUID.randomUUID().toString();
        Instant createdAt = Instant.now();
        saveWorkflow(workflowId, normalizedSubject, "RUNNING", createdAt, List.of(),
                "Python LangGraph handout workflow started.");
        return executePythonHandout(workflowId, createdAt, normalized, normalizedSubject, false);
    }

    /** Persists and publishes one opaque Python handout command. */
    public MultiAgentWritingResponse startAsync(MultiAgentWritingRequest request, RequestSubject subject) {
        MultiAgentWritingRequest normalized = request.normalize();
        requireLiveModelExecution(normalized);
        RequestSubject normalizedSubject = subject.normalize();
        requireTeacherOrAdmin(normalizedSubject);
        requireConfiguredHandoutRuntime();
        String workflowId = UUID.randomUUID().toString();
        Instant createdAt = Instant.now();
        MultiAgentWritingWorkflowRecord started = dispatchService.submit(
                workflowRecord(workflowId, normalizedSubject, "RUNNING", createdAt, List.of(),
                        "Python LangGraph handout workflow queued; dispatch pending."),
                AgentWorkerRabbitConfiguration.PYTHON_HANDOUT_AGENT_CODE,
                AgentWorkerRabbitConfiguration.PYTHON_HANDOUT_STAGE_CODE,
                workerTaskPayload(normalized));
        return toResponse(started);
    }

    /** Executes the durable Python graph task; duplicate delivery reuses Python checkpoints. */
    public MultiAgentWritingResponse executeDispatchedPython(
            String workflowId, MultiAgentWritingRequest request, RequestSubject subject) {
        requireConfiguredHandoutRuntime();
        RequestSubject normalizedSubject = subject.normalize();
        MultiAgentWritingWorkflowRecord existing = workflowStore.findVisible(workflowId, normalizedSubject)
                .orElseThrow(() -> new IllegalArgumentException("Multi-agent writing workflow not found"));
        if ("COMPLETED".equals(existing.status())) {
            return toResponse(existing);
        }
        return executePythonHandout(
                existing.workflowId(), existing.createdAt(), request.normalize(), normalizedSubject, true);
    }

    /** Marks an exhausted Python Worker command recoverable without interpreting it as a Java stage. */
    public MultiAgentWritingResponse failDispatchedStage(
            String workflowId, RequestSubject subject, String errorSummary) {
        RequestSubject normalizedSubject = subject.normalize();
        MultiAgentWritingWorkflowRecord existing = workflowStore.findVisible(workflowId, normalizedSubject)
                .orElseThrow(() -> new IllegalArgumentException("Multi-agent writing workflow not found"));
        String message = errorSummary == null || errorSummary.isBlank()
                ? "Python handout task failed after retries."
                : "Python handout task failed after retries: "
                        + errorSummary.substring(0, Math.min(300, errorSummary.length()));
        return toResponse(saveWorkflow(
                existing.workflowId(), normalizedSubject, "FAILED", existing.createdAt(), existing.stages(), message));
    }

    /** Reconstructs the authenticated subject from the Java-owned workflow row after a Worker claims a task. */
    public RequestSubject resolveWorkerSubject(String workflowId) {
        MultiAgentWritingWorkflowRecord workflow = workflowStore.findByIdInternal(normalizedWorkflowId(workflowId))
                .orElseThrow(() -> new IllegalArgumentException("Multi-agent writing workflow not found"));
        return new RequestSubject(
                workflow.tenantId(), workflow.subjectType(), workflow.subjectId(), "agent-worker").normalize();
    }

    /** Requeues a failed workflow as one Python graph without preserving Java stage assumptions. */
    public MultiAgentWritingResponse resume(String workflowId, MultiAgentWritingRequest request, RequestSubject subject) {
        MultiAgentWritingRequest normalized = request.normalize();
        requireLiveModelExecution(normalized);
        RequestSubject normalizedSubject = subject.normalize();
        requireTeacherOrAdmin(normalizedSubject);
        requireConfiguredHandoutRuntime();
        MultiAgentWritingWorkflowRecord existing = workflowStore
                .findVisible(normalizedWorkflowId(workflowId), normalizedSubject)
                .orElseThrow(() -> new IllegalArgumentException("Multi-agent writing workflow not found"));
        if ("COMPLETED".equals(existing.status())) {
            return toResponse(existing);
        }
        if ("RUNNING".equals(existing.status())) {
            throw new IllegalStateException("Multi-agent writing workflow is still running");
        }
        MultiAgentWritingWorkflowRecord resumed = dispatchService.submit(
                workflowRecord(existing.workflowId(), normalizedSubject, "RUNNING", existing.createdAt(), existing.stages(),
                        "Python LangGraph handout workflow resumed; dispatch pending."),
                AgentWorkerRabbitConfiguration.PYTHON_HANDOUT_AGENT_CODE,
                AgentWorkerRabbitConfiguration.PYTHON_HANDOUT_STAGE_CODE,
                workerTaskPayload(normalized));
        return toResponse(resumed);
    }

    /** Reads a workflow only when the Java-owned tenant and subject visibility check succeeds. */
    public Optional<MultiAgentWritingResponse> find(String workflowId, RequestSubject subject) {
        return workflowStore.findVisible(workflowId, subject).map(MultiAgentWritingService::toResponse);
    }

    /** Projects owned Python stage snapshots into the existing artifact response. */
    public MultiAgentWritingArtifact artifact(String workflowId, RequestSubject subject) {
        MultiAgentWritingWorkflowRecord record = workflowStore
                .findVisible(normalizedWorkflowId(workflowId), subject.normalize())
                .orElseThrow(() -> new IllegalArgumentException("Multi-agent writing workflow not found"))
                .normalize();
        List<MultiAgentWritingArtifact.StageArtifact> stages = record.stages().stream()
                .map(stage -> new MultiAgentWritingArtifact.StageArtifact(
                        stage.stageCode(),
                        stage.agentCode(),
                        stage.traceId(),
                        stage.providerName(),
                        stage.modelCode(),
                        stage.status(),
                        safeArtifactText(stage.generatedContent())))
                .toList();
        List<MultiAgentWritingArtifact.StructuredSection> sections = new ArrayList<>();
        for (MultiAgentWritingArtifact.StageArtifact stage : stages) {
            if (stage.generatedContent().isBlank()) {
                continue;
            }
            String title = stageTitle(stage.stageCode());
            sections.add(new MultiAgentWritingArtifact.StructuredSection(
                    stage.stageCode(), title, stage.stageCode(), stage.generatedContent(), List.of(), List.of(), List.of()));
        }
        return new MultiAgentWritingArtifact(
                record.workflowId(),
                record.tenantId(),
                record.subjectType(),
                record.subjectId(),
                record.status(),
                record.totalUsage(),
                stages,
                List.copyOf(sections),
                mergedArtifactMarkdown(sections));
    }

    private MultiAgentWritingResponse executePythonHandout(
            String workflowId,
            Instant createdAt,
            MultiAgentWritingRequest request,
            RequestSubject subject,
            boolean resume) {
        try {
            if (pythonHandoutClient == null) {
                throw new IllegalStateException(
                        "Python handout client is unavailable; Java AI execution is disabled for handout tasks");
            }
            PythonHandoutClient.PythonHandoutResult result = pythonHandoutClient.execute(
                    workflowId, request, workflowId + ":python-langgraph", resume);
            String status = "COMPLETED".equals(result.status()) ? "COMPLETED" : "FAILED";
            String message = "COMPLETED".equals(status)
                    ? "Python LangGraph handout workflow completed."
                    : "Python LangGraph handout workflow failed.";
            return toResponse(saveWorkflow(workflowId, subject, status, createdAt, result.stages(), message));
        } catch (RuntimeException exception) {
            saveWorkflow(workflowId, subject, "FAILED", createdAt, List.of(),
                    "Python LangGraph handout workflow failed: " + safeFailureMessage(exception));
            throw exception;
        }
    }

    private boolean pythonHandoutEnabled() {
        return pythonHandoutEnabled(environment);
    }

    /** Returns true only when the Python handout runtime is explicitly available to this service instance. */
    static boolean pythonHandoutEnabled(Environment environment) {
        return environment != null
                && environment.getProperty("math-agent.python-handout.enabled", Boolean.class, true);
    }

    private void requireConfiguredHandoutRuntime() {
        if (!pythonHandoutEnabled()) {
            throw new IllegalStateException("Python handout runtime is disabled; Java does not provide an AI fallback");
        }
    }

    private static String workerTaskPayload(MultiAgentWritingRequest request) {
        try {
            // Identity is reconstructed from the durable Java workflow row after the Worker claims this command.
            return ARTIFACT_OBJECT_MAPPER.writeValueAsString(java.util.Map.of("request", request));
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to persist distributed Agent Worker task payload", exception);
        }
    }

    private static void requireTeacherOrAdmin(RequestSubject subject) {
        if (!"teacher".equals(subject.subjectType()) && !"admin".equals(subject.subjectType())) {
            throw new IllegalArgumentException("Multi-agent writing requires teacher or admin subject");
        }
    }

    private static void requireLiveModelExecution(MultiAgentWritingRequest request) {
        if (request.dryRun()) {
            throw new IllegalArgumentException("Multi-agent writing dryRun is disabled in production");
        }
    }

    private static String normalizedWorkflowId(String workflowId) {
        if (workflowId == null || workflowId.isBlank()) {
            throw new IllegalArgumentException("workflowId is required");
        }
        String normalized = workflowId.strip();
        if (!normalized.matches("[A-Za-z0-9._:-]{8,80}")) {
            throw new IllegalArgumentException("workflowId is invalid");
        }
        return normalized;
    }

    private MultiAgentWritingWorkflowRecord saveWorkflow(
            String workflowId,
            RequestSubject subject,
            String status,
            Instant createdAt,
            List<MultiAgentWritingResponse.StageResult> stages,
            String message) {
        return workflowStore.save(workflowRecord(workflowId, subject, status, createdAt, stages, message));
    }

    private static MultiAgentWritingWorkflowRecord workflowRecord(
            String workflowId,
            RequestSubject subject,
            String status,
            Instant createdAt,
            List<MultiAgentWritingResponse.StageResult> stages,
            String message) {
        List<MultiAgentWritingResponse.StageResult> safeStages = stages == null ? List.of() : List.copyOf(stages);
        return new MultiAgentWritingWorkflowRecord(
                workflowId,
                subject.tenantId(),
                subject.subjectType(),
                subject.subjectId(),
                status,
                createdAt,
                Instant.now(),
                safeStages,
                totalUsage(safeStages),
                message);
    }

    private static AgentRunExecuteResponse.TokenUsage totalUsage(List<MultiAgentWritingResponse.StageResult> stages) {
        int promptTokens = stages.stream().mapToInt(stage -> stage.actualUsage().promptTokens()).sum();
        int completionTokens = stages.stream().mapToInt(stage -> stage.actualUsage().completionTokens()).sum();
        int totalTokens = stages.stream().mapToInt(stage -> stage.actualUsage().totalTokens()).sum();
        return new AgentRunExecuteResponse.TokenUsage(promptTokens, completionTokens, totalTokens);
    }

    private static MultiAgentWritingResponse toResponse(MultiAgentWritingWorkflowRecord record) {
        MultiAgentWritingWorkflowRecord normalized = record.normalize();
        return new MultiAgentWritingResponse(
                normalized.workflowId(),
                normalized.tenantId(),
                normalized.subjectType(),
                normalized.subjectId(),
                normalized.status(),
                normalized.createdAt(),
                normalized.updatedAt(),
                normalized.stages(),
                normalized.totalUsage(),
                normalized.message());
    }

    private static String safeArtifactText(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String normalized = value.strip();
        if (!normalized.startsWith("{")) {
            return normalized;
        }
        try {
            JsonNode root = ARTIFACT_OBJECT_MAPPER.readTree(normalized);
            if (!root.isObject()) {
                return normalized;
            }
            for (String field : List.of("markdown", "content", "body", "result", "teacherExplanation", "studentWorksheet")) {
                JsonNode candidate = root.get(field);
                if (candidate != null && candidate.isTextual() && !candidate.asText().isBlank()) {
                    return candidate.asText().strip();
                }
            }
            return normalized;
        } catch (Exception exception) {
            return normalized;
        }
    }

    private static String mergedArtifactMarkdown(List<MultiAgentWritingArtifact.StructuredSection> sections) {
        StringBuilder result = new StringBuilder();
        for (MultiAgentWritingArtifact.StructuredSection section : sections) {
            if (section.content().isBlank()) {
                continue;
            }
            if (!result.isEmpty()) {
                result.append("\n\n");
            }
            if (!section.content().stripLeading().startsWith("#")) {
                result.append("## ").append(section.title()).append("\n\n");
            }
            result.append(section.content());
        }
        return result.toString();
    }

    private static String stageTitle(String stageCode) {
        return switch (stageCode) {
            case "resource_curation" -> "资料汇总";
            case "teacher_writer" -> "教师版";
            case "student_writer" -> "学生版";
            case "lecture_writer" -> "16:10 讲解版";
            default -> stageCode == null ? "讲义内容" : stageCode;
        };
    }

    private static String safeFailureMessage(RuntimeException exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank() ? "handout runtime failure" : message.strip();
    }
}
