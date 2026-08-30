package com.doob.mathagent.agent.service;

import com.doob.mathagent.agent.dto.MultiAgentWritingRequest;
import com.doob.mathagent.agent.vo.AgentRunExecuteResponse;
import com.doob.mathagent.agent.vo.MultiAgentWritingResponse;
import com.doob.mathagent.agent.worker.AgentWorkerTaskDispatchService;
import com.doob.mathagent.agent.worker.AgentWorkerRabbitConfiguration;
import com.doob.mathagent.infrastructure.security.RequestSubject;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.doob.mathagent.teaching.TeachingEvidence;
import java.util.LinkedHashMap;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
        return startAsync(request, subject, null);
    }

    /**
     * Persists one opaque Python handout command with caller-scoped idempotency for MCP transport recovery.
     *
     * <p>The opaque workflow id is deterministically derived from the authenticated owner and validated client request
     * id. Repeated submissions therefore reload the same durable workflow rather than enqueueing another writer run.</p>
     */
    public MultiAgentWritingResponse startAsync(
            MultiAgentWritingRequest request, RequestSubject subject, String clientRequestId) {
        MultiAgentWritingRequest normalized = request.normalize();
        requireLiveModelExecution(normalized);
        RequestSubject normalizedSubject = subject.normalize();
        requireTeacherOrAdmin(normalizedSubject);
        requireConfiguredHandoutRuntime();
        String workflowId = clientRequestId == null || clientRequestId.isBlank()
                ? UUID.randomUUID().toString()
                : idempotentWorkflowId(normalizedSubject, clientRequestId);
        Optional<MultiAgentWritingWorkflowRecord> existing = workflowStore.findVisible(workflowId, normalizedSubject);
        if (existing.isPresent()) {
            return toResponse(existing.get());
        }
        Instant createdAt = Instant.now();
        List<TeachingEvidence> initialEvidence = initialEvidence(normalized);
        MultiAgentWritingRequest bound = bindInitialEvidence(workflowId, normalized);
        MultiAgentWritingWorkflowRecord started = dispatchService.submit(
                workflowRecord(workflowId, normalizedSubject, "RUNNING", createdAt,
                        initialEvidenceStages(workflowId, initialEvidence),
                        "Python LangGraph handout workflow queued; dispatch pending."),
                AgentWorkerRabbitConfiguration.PYTHON_HANDOUT_AGENT_CODE,
                AgentWorkerRabbitConfiguration.PYTHON_HANDOUT_STAGE_CODE,
                workerTaskPayload(bound));
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
            List<TeachingEvidence> recoveryEvidence = initialEvidence(normalized);
            if (!recoveryEvidence.isEmpty()) {
                MultiAgentWritingRequest bound = bindInitialEvidence(existing.workflowId(), normalized);
                MultiAgentWritingWorkflowRecord resumed = dispatchService.submitRecovery(
                        workflowRecord(existing.workflowId(), normalizedSubject, "RUNNING", existing.createdAt(),
                                mergeEvidenceStages(existing.stages(), initialEvidenceStages(existing.workflowId(), recoveryEvidence)),
                                "Authorized evidence snapshot refreshed; Python LangGraph handout workflow requeued."),
                        AgentWorkerRabbitConfiguration.PYTHON_HANDOUT_AGENT_CODE,
                        AgentWorkerRabbitConfiguration.PYTHON_HANDOUT_STAGE_CODE,
                        workerTaskPayload(bound));
                return toResponse(resumed);
            }
            return toResponse(existing);
        }
        if ("RUNNING".equals(existing.status())) {
            throw new IllegalStateException("Multi-agent writing workflow is still running");
        }
        List<TeachingEvidence> initialEvidence = initialEvidence(normalized);
        MultiAgentWritingRequest bound = bindInitialEvidence(existing.workflowId(), normalized);
        MultiAgentWritingWorkflowRecord resumed = dispatchService.submitRecovery(
                workflowRecord(existing.workflowId(), normalizedSubject, "RUNNING", existing.createdAt(),
                        mergeEvidenceStages(existing.stages(), initialEvidenceStages(existing.workflowId(), initialEvidence)),
                        "Python LangGraph handout workflow resumed; dispatch pending."),
                AgentWorkerRabbitConfiguration.PYTHON_HANDOUT_AGENT_CODE,
                AgentWorkerRabbitConfiguration.PYTHON_HANDOUT_STAGE_CODE,
                workerTaskPayload(bound));
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
                        safeArtifactText(stage.generatedContent()),
                        mergedCitations(stage),
                        stage.assetPlacements()))
                .toList();
        List<MultiAgentWritingArtifact.StructuredSection> sections = new ArrayList<>();
        for (MultiAgentWritingArtifact.StageArtifact stage : stages) {
            if (stage.generatedContent().isBlank()) {
                continue;
            }
            String title = stageTitle(stage.stageCode());
            sections.add(new MultiAgentWritingArtifact.StructuredSection(
                    stage.stageCode(), title, stage.stageCode(), stage.generatedContent(), List.of(), List.of(), List.of(),
                    mergedCitations(stage), stage.assetPlacements()));
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
            List<MultiAgentWritingResponse.StageResult> stages = bindAuthorizedImageDocuments(
                    workflowId, request, result.stages());
            return toResponse(saveWorkflow(workflowId, subject, status, createdAt, stages, message));
        } catch (RuntimeException exception) {
            // Agent Worker owns retry exhaustion. Keep the workflow RUNNING while the same durable task is requeued;
            // marking it FAILED here would expose a false terminal state during the next attempt.
            List<MultiAgentWritingResponse.StageResult> preservedStages = workflowStore.findByIdInternal(workflowId)
                    .map(MultiAgentWritingWorkflowRecord::stages)
                    .orElse(List.of());
            saveWorkflow(workflowId, subject, "RUNNING", createdAt, preservedStages,
                    "Python LangGraph handout workflow retry scheduled: " + safeFailureMessage(exception));
            throw exception;
        }
    }

    /**
     * Enriches only Java-persisted curation evidence with the document identity that issued its opaque reference.
     *
     * <p>Python receives and returns only {@code documentRef}. The export boundary needs the originating document to
     * distinguish repeated relative image paths, so Java restores it exclusively from the current request's authorized
     * initial evidence. Unrecognized references remain unbound and cannot materialize an image.</p>
     */
    private List<MultiAgentWritingResponse.StageResult> bindAuthorizedImageDocuments(
            String workflowId,
            MultiAgentWritingRequest request,
            List<MultiAgentWritingResponse.StageResult> stages) {
        Map<String, TeachingEvidence> evidenceByDocumentReference = new java.util.HashMap<>();
        for (TeachingEvidence evidence : initialEvidence(request)) {
            if (!evidence.sourceDocumentId().isBlank()) {
                evidenceByDocumentReference.put(
                        "doc_" + fingerprint(workflowId + "|document|" + evidence.sourceDocumentId()), evidence);
            }
        }
        if (evidenceByDocumentReference.isEmpty()) {
            return stages;
        }
        return stages.stream().map(stage -> {
            if (!"resource_curation".equals(stage.stageCode()) || stage.generatedContent().isBlank()) {
                return stage;
            }
            try {
                JsonNode root = ARTIFACT_OBJECT_MAPPER.readTree(stage.generatedContent());
                if (!(root instanceof ObjectNode object)) {
                    return stage;
                }
                for (String field : List.of("items", "inspectedItems")) {
                    JsonNode items = object.path(field);
                    if (!items.isArray()) continue;
                    for (JsonNode item : items) {
                        if (!(item instanceof ObjectNode row) || !row.path("imageRefs").isArray()
                                || row.path("imageRefs").isEmpty()) continue;
                        TeachingEvidence evidence = evidenceByDocumentReference.get(row.path("documentRef").asText(""));
                        if (evidence != null) {
                            row.put("sourceDocumentId", evidence.sourceDocumentId());
                            row.put("sourceScope", evidence.sourceScope());
                            if (!evidence.canonicalQuestionNumber().isBlank()) {
                                row.put("canonicalQuestionNumber", evidence.canonicalQuestionNumber());
                            }
                        }
                    }
                }
                return new MultiAgentWritingResponse.StageResult(
                        stage.stageCode(), stage.agentCode(), stage.traceId(), stage.providerName(), stage.modelCode(),
                        stage.status(), stage.actualUsage(), stage.message(),
                        ARTIFACT_OBJECT_MAPPER.writeValueAsString(object), stage.elapsedMs(),
                        stage.citations(), stage.assetPlacements());
            } catch (Exception exception) {
                throw new IllegalStateException("Could not bind authorized source images to curation evidence", exception);
            }
        }).toList();
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

    private MultiAgentWritingRequest bindInitialEvidence(String workflowId, MultiAgentWritingRequest request) {
        List<TeachingEvidence> evidence = initialEvidence(request);
        if (evidence.isEmpty()) return request;
        List<String> issued = evidence.stream().map(e -> issuedEvidenceRef(workflowId, e)).toList();
        return new MultiAgentWritingRequest(request.writingGoal(), request.questionText(), issued,
                request.dryRun(), request.preferredProviderName(), request.preferredModelCode(), evidence);
    }

    private List<MultiAgentWritingResponse.StageResult> initialEvidenceStages(
            String workflowId, List<TeachingEvidence> evidence) {
        if (evidence == null || evidence.isEmpty()) return List.of();
        try {
            List<java.util.Map<String, Object>> rows = new ArrayList<>();
            for (TeachingEvidence item : evidence) {
                rows.add(java.util.Map.ofEntries(
                        java.util.Map.entry("ref", issuedEvidenceRef(workflowId, item)),
                        java.util.Map.entry("transparentRef", transparentEvidenceRef(item)),
                        java.util.Map.entry("sourceScope", item.sourceScope()),
                        java.util.Map.entry("sourceTitle", item.sourceTitle()),
                        java.util.Map.entry("sourceDocumentId", item.sourceDocumentId()),
                        java.util.Map.entry("chunkId", item.chunkId()),
                        java.util.Map.entry("pageNo", item.pageNo()),
                        java.util.Map.entry("snippet", item.snippet()),
                        java.util.Map.entry("assetIds", item.assetIds()),
                        java.util.Map.entry("imageRefs", item.imageRefs()),
                        java.util.Map.entry("canonicalQuestionNumber", item.canonicalQuestionNumber())));
            }
            String content = ARTIFACT_OBJECT_MAPPER.writeValueAsString(java.util.Map.of("items", rows));
            return List.of(new MultiAgentWritingResponse.StageResult("resource_curation", "TeacherAssistantAgent",
                    workflowId + ":resource_curation", "java-broker", "", "COMPLETED",
                    new AgentRunExecuteResponse.TokenUsage(0, 0, 0), "Authorized evidence snapshot persisted.", content, 0L));
        } catch (Exception exception) {
            throw new IllegalStateException("Could not serialize initial handout evidence", exception);
        }
    }

    private static String transparentEvidenceRef(TeachingEvidence item) {
        String scope = item.sourceScope();
        if ("PUBLIC_TEXTBOOK".equals(scope)) return "textbook://" + item.sourceDocumentId() + "/chunk/" + item.chunkId();
        if ("TEACHER_RESOURCE".equals(scope)) return "feishu://group/TEACHER_SHARED/resource/" + item.sourceDocumentId() + "/block/" + item.chunkId();
        if ("CANONICAL_MATH_PAPER".equals(scope)) return "gaokao://canonical/" + item.sourceDocumentId() + "/question/" + item.canonicalQuestionNumber();
        return "";
    }

    private static List<TeachingEvidence> initialEvidence(MultiAgentWritingRequest request) {
        return request.initialEvidence() == null || request.initialEvidence().isEmpty()
                ? request.evidenceRefs().stream().filter(value -> value != null && value.startsWith("ev_")).map(value ->
                        new TeachingEvidence("", "", value, 0, "", "", "", "", "", "", "", List.of(), "")).toList()
                : request.initialEvidence();
    }

    private List<TeachingEvidence> parseTransparentEvidence(List<String> refs) {
        List<TeachingEvidence> result = new ArrayList<>();
        for (String raw : refs == null ? List.<String>of() : refs) {
            if (raw == null || raw.isBlank()) continue;
            if (raw.startsWith("asset://")) {
                if (!result.isEmpty()) {
                    TeachingEvidence prior = result.removeLast();
                    String assetId = raw.substring(raw.lastIndexOf('/') + 1).strip();
                    if (!assetId.isBlank() && !prior.assetIds().contains(assetId)) {
                        List<String> assets = new ArrayList<>(prior.assetIds());
                        assets.add(assetId);
                        prior = new TeachingEvidence(prior.sourceScope(), prior.sourceTitle(), prior.chunkId(), prior.pageNo(),
                                prior.snippet(), prior.imagePath(), prior.imageDescription(), prior.sourceDocumentId(),
                                prior.sourceType(), prior.sourceUrl(), prior.sourcePath(), assets, prior.canonicalQuestionNumber());
                    }
                    result.add(prior);
                }
                continue;
            }
            TeachingEvidence evidence = transparentEvidence(raw);
            if (evidence != null) result.add(evidence);
        }
        return result.stream().collect(java.util.stream.Collectors.collectingAndThen(
                java.util.stream.Collectors.toMap(e -> e.sourceScope() + "|" + e.sourceDocumentId() + "|" + e.chunkId()
                        + "|" + e.canonicalQuestionNumber(), e -> e, (a, b) -> mergeEvidenceAssets(a, b), LinkedHashMap::new),
                map -> List.copyOf(map.values())));
    }

    private static TeachingEvidence mergeEvidenceAssets(TeachingEvidence first, TeachingEvidence second) {
        if (second.assetIds().isEmpty()) return first;
        List<String> assets = new ArrayList<>(first.assetIds());
        second.assetIds().stream().filter(asset -> !assets.contains(asset)).forEach(assets::add);
        return new TeachingEvidence(first.sourceScope(), first.sourceTitle(), first.chunkId(), first.pageNo(), first.snippet(),
                first.imagePath(), first.imageDescription(), first.sourceDocumentId(), first.sourceType(), first.sourceUrl(),
                first.sourcePath(), assets, first.canonicalQuestionNumber());
    }

    private static TeachingEvidence transparentEvidence(String raw) {
        try {
            if (raw.startsWith("textbook://")) {
                String body = raw.substring(11);
                int marker = body.indexOf("/chunk/");
                if (marker <= 0) return null;
                return new TeachingEvidence("PUBLIC_TEXTBOOK", "", body.substring(marker + 7), 0, "", "", "",
                        body.substring(0, marker), "public_textbook", "", "", List.of(), "");
            }
            if (raw.startsWith("feishu://group/")) {
                String body = raw.substring(15);
                int resource = body.indexOf("/resource/");
                int block = body.indexOf("/block/");
                if (resource <= 0 || block <= resource) return null;
                return new TeachingEvidence("TEACHER_RESOURCE", "", body.substring(block + 7), 0, "", "", "",
                        body.substring(resource + 10, block), "feishu", "", "", List.of(), "");
            }
            if (raw.startsWith("gaokao://canonical/")) {
                String body = raw.substring(19);
                int question = body.indexOf("/question/");
                if (question <= 0) return null;
                return new TeachingEvidence("CANONICAL_MATH_PAPER", "", "", 0, "", "", "",
                        body.substring(0, question), "gaokao", "", "", List.of(), body.substring(question + 10));
            }
        } catch (RuntimeException ignored) { }
        return null;
    }

    private String issuedEvidenceRef(String workflowId, TeachingEvidence evidence) {
        String assets = evidence.assetIds() == null
                ? ""
                : evidence.assetIds().stream().sorted().collect(java.util.stream.Collectors.joining(","));
        return "ev_" + fingerprint(workflowId + "|evidence|" + evidence.sourceDocumentId() + "|"
                + evidence.sourceScope() + "|" + evidence.sourceTitle() + "|" + evidence.chunkId()
                + "|assets=" + assets);
    }

    private String fingerprint(String value) {
        try {
            String secret = environment.getProperty("math-agent.agent-worker.shared-key", "");
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256").digest(
                    (secret + "|" + value).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest, 0, 16);
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static List<MultiAgentWritingResponse.StageResult> mergeEvidenceStages(
            List<MultiAgentWritingResponse.StageResult> existing,
            List<MultiAgentWritingResponse.StageResult> incoming) {
        List<MultiAgentWritingResponse.StageResult> merged = new ArrayList<>(existing == null ? List.of() : existing);
        merged.removeIf(stage -> "resource_curation".equals(stage.stageCode()));
        merged.addAll(incoming);
        return List.copyOf(merged);
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

    private static String idempotentWorkflowId(RequestSubject subject, String clientRequestId) {
        String normalized = clientRequestId.strip();
        if (normalized.length() > 128 || !normalized.matches("[A-Za-z0-9][A-Za-z0-9._:-]*")) {
            throw new IllegalArgumentException("clientRequestId must be 1-128 ASCII letters, digits, '.', '_', ':', or '-' characters");
        }
        try {
            byte[] source = (subject.tenantId() + "|" + subject.subjectType() + "|" + subject.subjectId() + "|" + normalized)
                    .getBytes(StandardCharsets.UTF_8);
            return UUID.nameUUIDFromBytes(source).toString();
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("clientRequestId could not be normalized", exception);
        }
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

    private static List<String> mergedCitations(MultiAgentWritingResponse.StageResult stage) {
        if (!"resource_curation".equals(stage.stageCode())) return stage.citations();
        return mergeCitationValues(stage.citations(), parseResourceCitations(stage.generatedContent()));
    }

    private static List<String> mergedCitations(MultiAgentWritingArtifact.StageArtifact stage) {
        if (!"resource_curation".equals(stage.stageCode())) return stage.citations();
        return mergeCitationValues(stage.citations(), parseResourceCitations(stage.generatedContent()));
    }

    private static List<String> mergeCitationValues(List<String> first, List<String> second) {
        List<String> merged = new ArrayList<>();
        for (String value : java.util.stream.Stream.concat(first.stream(), second.stream()).toList()) {
            if (!value.isBlank() && !merged.contains(value)) merged.add(value);
            if (merged.size() >= 48) break;
        }
        return List.copyOf(merged);
    }

    private static List<String> parseResourceCitations(String content) {
        try {
            JsonNode root = ARTIFACT_OBJECT_MAPPER.readTree(content);
            List<String> refs = new ArrayList<>();
            for (JsonNode item : root.path("items")) {
                String ref = item.path("transparentRef").asText("");
                if (ref.matches("(?:textbook|feishu|gaokao)://[^\\s]{1,500}") && !refs.contains(ref)) refs.add(ref);
                if (refs.size() >= 24) break;
            }
            return List.copyOf(refs);
        } catch (Exception ignored) {
            return List.of();
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
