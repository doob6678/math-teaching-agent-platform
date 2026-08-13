package com.doob.mathagent.agent.controller;

import com.doob.mathagent.agent.dto.AgentToolBrokerReadRequest;
import com.doob.mathagent.agent.dto.AgentToolBrokerAssetRequest;
import com.doob.mathagent.agent.dto.AgentToolBrokerSearchRequest;
import com.doob.mathagent.agent.dto.HandoutContextRequest;
import com.doob.mathagent.agent.service.MultiAgentWritingWorkflowRecord;
import com.doob.mathagent.agent.service.MultiAgentWritingWorkflowStore;
import com.doob.mathagent.agent.service.AgentTraceRecord;
import com.doob.mathagent.agent.service.AgentTraceStore;
import com.doob.mathagent.infrastructure.security.RequestSubject;
import com.doob.mathagent.teaching.service.TeachingTaskStore;
import com.doob.mathagent.teaching.vo.TeachingTaskResponse;
import com.doob.mathagent.teacher.block.TeacherDocumentBlockResponse;
import com.doob.mathagent.teacher.search.TeacherResourceBlockSearchResponse;
import com.doob.mathagent.teacher.service.TeacherResourceBlockSearchService;
import com.doob.mathagent.teacher.service.TeacherResourceAssetService;
import jakarta.validation.Valid;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Base64;
import java.io.IOException;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import org.springframework.core.env.Environment;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Internal-only bridge between the Python Agent runtime and protected Java domain services.
 *
 * <p>The shared worker key authenticates the local worker process. The backend keeps the request contract limited to
 * the already-resolved tenant/user scope and opaque resource identifiers; browser-provided authorization values are not used.</p>
 */
@RestController
@RequestMapping("/internal/agent-tools/v1")
public class AgentToolBrokerController {
    /** Read in bounded chunks so a malformed or oversized authorized asset cannot exhaust worker-process memory. */
    private static final int ASSET_READ_BUFFER_BYTES = 8 * 1024;
    private final TeacherResourceBlockSearchService resourceSearchService;
    private final TeacherResourceAssetService assetService;
    private final Environment environment;
    private final MultiAgentWritingWorkflowStore workflowStore;
    private final TeachingTaskStore teachingTaskStore;
    private final AgentTraceStore agentTraceStore;

    @Autowired
    public AgentToolBrokerController(
            TeacherResourceBlockSearchService resourceSearchService,
            TeacherResourceAssetService assetService,
            Environment environment,
            MultiAgentWritingWorkflowStore workflowStore,
            TeachingTaskStore teachingTaskStore,
            AgentTraceStore agentTraceStore) {
        this.resourceSearchService = resourceSearchService;
        this.assetService = assetService;
        this.environment = environment;
        this.workflowStore = workflowStore;
        this.teachingTaskStore = teachingTaskStore;
        this.agentTraceStore = agentTraceStore;
    }

    /** Keeps older direct tests source-compatible while production injects both durable run stores. */
    public AgentToolBrokerController(
            TeacherResourceBlockSearchService resourceSearchService,
            TeacherResourceAssetService assetService,
            Environment environment,
            MultiAgentWritingWorkflowStore workflowStore) {
        this(resourceSearchService, assetService, environment, workflowStore, null, null);
    }

    /** Compatibility constructor for focused broker tests that do not load workflow persistence. */
    public AgentToolBrokerController(
            TeacherResourceBlockSearchService resourceSearchService,
            TeacherResourceAssetService assetService,
            Environment environment) {
        this(resourceSearchService, assetService, environment, null, null, null);
    }

    /**
     * Fetches one compact evidence snapshot for the complete Python graph.  Identity is derived from the durable
     * Java workflow row keyed by runId; Python cannot choose tenantId, role, or subjectId in this batch contract.
     */
    @PostMapping("/handout-context")
    public Map<String, Object> handoutContext(
            @RequestHeader("X-Agent-Worker-Key") String workerKey,
            @Valid @RequestBody HandoutContextRequest request) {
        authorize(workerKey);
        RequestSubject subject = subjectForHandoutRun(request.runId());
        String query = request.query().strip();
        TeacherResourceBlockSearchResponse response = resourceSearchService.search(
                subject.tenantId(), subject.subjectType(), subject.subjectId(), query, request.limit(),
                "/internal/agent-tools/v1/handout-context");
        List<Map<String, Object>> items = response.hits().stream().map(hit -> Map.<String, Object>of(
                "ref", hit.documentId() + ":" + hit.blockId(),
                "title", hit.documentTitle(),
                "excerpt", compactEvidence(hit.evidenceText(), hit.snippet()),
                "assetId", hit.imageAssetIds() == null || hit.imageAssetIds().isEmpty() ? "" : hit.imageAssetIds().getFirst()))
                .toList();
        return Map.of("runId", request.runId(), "query", response.query(), "items", items);
    }

    /**
     * Resolves an opaque graph run from the one business store that owns it.
     *
     * <p>During the compatibility canary, older multi-agent rows remain readable. Newly created handouts are
     * teaching tasks, so the Python graph must derive the exact same tenant/subject from that task instead of
     * requiring a parallel workflow row or accepting identity fields from Python.</p>
     */
    private RequestSubject subjectForHandoutRun(String runId) {
        if (teachingTaskStore != null) {
            TeachingTaskResponse task = teachingTaskStore.findByTaskId(runId).orElse(null);
            if (task != null) {
                return new RequestSubject(
                        task.tenantId(), task.subjectType(), task.subjectId(), "agent-worker").normalize();
            }
        }
        if (workflowStore != null) {
            MultiAgentWritingWorkflowRecord workflow = workflowStore.findByIdInternal(runId).orElse(null);
            if (workflow != null) {
                return new RequestSubject(
                        workflow.tenantId(), workflow.subjectType(), workflow.subjectId(), "agent-worker").normalize();
            }
        }
        if (teachingTaskStore == null && workflowStore == null) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Handout context stores are unavailable");
        }
        throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Handout run not found");
    }

    /** Bounds evidence sent over the single Java-Python context request. */
    private static String compactEvidence(String evidenceText, String snippet) {
        String value = evidenceText == null || evidenceText.isBlank() ? snippet : evidenceText;
        if (value == null || value.isBlank()) return "";
        String normalized = value.replaceAll("\\s+", " ").strip();
        return normalized.length() <= 3000 ? normalized : normalized.substring(0, 2997) + "...";
    }

    /** Searches only resources visible to the user encoded in the broker request. */
    @PostMapping("/search-visible-resources")
    public Map<String, Object> search(
            @RequestHeader("X-Agent-Worker-Key") String workerKey,
            @Valid @RequestBody AgentToolBrokerSearchRequest request) {
        authorize(workerKey);
        RequestSubject subject = subjectForRun(request.runId(), request.tenantId(), request.subjectType(), request.subjectId());
        TeacherResourceBlockSearchResponse response = resourceSearchService.search(
                subject.tenantId(), subject.subjectType(), subject.subjectId(), request.query(), request.limit(),
                "/internal/agent-tools/v1/search-visible-resources");
        return Map.of("runId", request.runId(), "query", response.query(), "hits", response.hits());
    }

    /** Reads blocks only after the same tenant/role visibility check used for retrieval. */
    @PostMapping("/read-resource-blocks")
    public Map<String, Object> readBlocks(
            @RequestHeader("X-Agent-Worker-Key") String workerKey,
            @Valid @RequestBody AgentToolBrokerReadRequest request) {
        authorize(workerKey);
        RequestSubject subject = subjectForRun(request.runId(), request.tenantId(), request.subjectType(), request.subjectId());
        List<TeacherDocumentBlockResponse> blocks = resourceSearchService.listVisibleBlocks(
                subject.tenantId(), subject.subjectType(), subject.subjectId(), request.documentId());
        return Map.of("runId", request.runId(), "documentId", request.documentId(), "blocks", blocks);
    }

    /**
     * Reads one already-authorized image through Java and returns a self-contained data URL for the visual model.
     * The resource stream is opened only after scope verification; Python receives neither storage key nor local path.
     */
    @PostMapping("/read-resource-asset")
    public Map<String, Object> readAsset(
            @RequestHeader("X-Agent-Worker-Key") String workerKey,
            @Valid @RequestBody AgentToolBrokerAssetRequest request) {
        authorize(workerKey);
        RequestSubject subject = subjectForRun(request.runId(), request.tenantId(), request.subjectType(), request.subjectId());
        try {
            TeacherResourceAssetService.VisibleAsset asset = assetService.openVisibleAsset(
                    request.assetId(), subject);
            long maximumBytes = environment.getProperty("math-agent.agent-worker.asset-max-bytes", Long.class, 0L);
            byte[] content = readBoundedAsset(asset.resource().getInputStream(), maximumBytes);
            String dataUrl = "data:" + asset.mimeType() + ";base64," + Base64.getEncoder().encodeToString(content);
            return Map.of("runId", request.runId(),
                    "asset", Map.of("assetId", asset.assetId(), "mimeType", asset.mimeType(),
                            "fileName", asset.fileName(), "dataUrl", dataUrl));
        } catch (IOException exception) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Authorized asset is unavailable", exception);
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Authorized asset is unavailable", exception);
        }
    }

    /**
     * Enforces the configured transfer cap while reading rather than after allocation.  The extra byte distinguishes
     * an exact-limit asset from an oversized stream without retaining unbounded source data in Java heap.
     */
    private static byte[] readBoundedAsset(InputStream stream, long maximumBytes) throws IOException {
        if (maximumBytes <= 0L || maximumBytes > Integer.MAX_VALUE - 1L) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "Agent asset limit is invalid");
        }
        try (InputStream input = stream; ByteArrayOutputStream output = new ByteArrayOutputStream((int) maximumBytes)) {
            byte[] buffer = new byte[ASSET_READ_BUFFER_BYTES];
            long total = 0L;
            int read;
            while ((read = input.read(buffer)) != -1) {
                total += read;
                if (total > maximumBytes) {
                    throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,
                            "Authorized asset exceeds configured Agent tool limit");
                }
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        }
    }

    private void authorize(String actual) {
        String expected = environment.getProperty("math-agent.agent-worker.shared-key", "");
        if (expected.isBlank() || actual == null || !MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8), actual.getBytes(StandardCharsets.UTF_8))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Agent Worker key is invalid");
        }
    }

    /**
     * Resolves identity from the durable run row instead of trusting model-generated tenant fields. The request
     * fields remain in the DTO for wire compatibility, but production routes fail closed when the run is unknown or
     * the caller tries to use a different identity. The null-store branch exists only for focused direct-controller
     * tests that intentionally bypass Spring persistence wiring.
     */
    private RequestSubject subjectForRun(String runId, String suppliedTenantId, String suppliedSubjectType, String suppliedSubjectId) {
        if (agentTraceStore != null) {
            AgentTraceRecord trace = agentTraceStore.find(runId == null ? "" : runId.strip())
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Agent run authorization not found"));
            RequestSubject persisted = new RequestSubject(
                    trace.tenantId(), trace.subjectType(), trace.subjectId(), "agent-worker").normalize();
            requireMatchingSuppliedIdentity(persisted, suppliedTenantId, suppliedSubjectType, suppliedSubjectId);
            return persisted;
        }
        if (workflowStore == null) {
            return new RequestSubject(suppliedTenantId, suppliedSubjectType, suppliedSubjectId, "agent-worker").normalize();
        }
        MultiAgentWritingWorkflowRecord workflow = workflowStore.findByIdInternal(runId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Agent run authorization not found"));
        RequestSubject persisted = new RequestSubject(
                workflow.tenantId(), workflow.subjectType(), workflow.subjectId(), "agent-worker").normalize();
        requireMatchingSuppliedIdentity(persisted, suppliedTenantId, suppliedSubjectType, suppliedSubjectId);
        return persisted;
    }

    /** Allows absent legacy wire fields while rejecting any supplied identity that differs from the durable run. */
    private static void requireMatchingSuppliedIdentity(
            RequestSubject persisted, String tenantId, String subjectType, String subjectId) {
        boolean supplied = tenantId != null || subjectType != null || subjectId != null;
        if (supplied && (!persisted.tenantId().equals(tenantId == null ? "" : tenantId.strip())
                || !persisted.subjectType().equals(subjectType == null ? "" : subjectType.strip())
                || !persisted.subjectId().equals(subjectId == null ? "" : subjectId.strip()))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Agent run identity does not match its durable authorization");
        }
    }

}
