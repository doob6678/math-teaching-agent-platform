package com.doob.mathagent.agent.controller;

import com.doob.mathagent.agent.dto.AgentToolBrokerReadRequest;
import com.doob.mathagent.agent.dto.AgentToolBrokerAssetRequest;
import com.doob.mathagent.agent.dto.AgentToolBrokerSearchRequest;
import com.doob.mathagent.agent.service.AgentRunCapabilityTokenService;
import com.doob.mathagent.infrastructure.security.RequestSubject;
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
 * <p>The shared worker key authenticates the worker process.  A second signed capability token binds each request
 * to its run, tenant, subject and tool scope; the JSON body alone can never impersonate another student.</p>
 */
@RestController
@RequestMapping("/internal/agent-tools/v1")
public class AgentToolBrokerController {
    /** Read in bounded chunks so a malformed or oversized authorized asset cannot exhaust worker-process memory. */
    private static final int ASSET_READ_BUFFER_BYTES = 8 * 1024;
    private final TeacherResourceBlockSearchService resourceSearchService;
    private final TeacherResourceAssetService assetService;
    private final AgentRunCapabilityTokenService capabilityTokenService;
    private final Environment environment;

    public AgentToolBrokerController(
            TeacherResourceBlockSearchService resourceSearchService,
            TeacherResourceAssetService assetService,
            AgentRunCapabilityTokenService capabilityTokenService,
            Environment environment) {
        this.resourceSearchService = resourceSearchService;
        this.assetService = assetService;
        this.capabilityTokenService = capabilityTokenService;
        this.environment = environment;
    }

    /** Searches only resources visible to the user encoded in the broker request. */
    @PostMapping("/search-visible-resources")
    public Map<String, Object> search(
            @RequestHeader("X-Agent-Worker-Key") String workerKey,
            @Valid @RequestBody AgentToolBrokerSearchRequest request) {
        authorize(workerKey);
        authorizeCapability(request.capabilityToken(), request.runId(), request.tenantId(), request.subjectType(),
                request.subjectId(), "search_visible_resources");
        TeacherResourceBlockSearchResponse response = resourceSearchService.search(
                request.tenantId(), request.subjectType(), request.subjectId(), request.query(), request.limit(),
                "/internal/agent-tools/v1/search-visible-resources");
        return Map.of("runId", request.runId(), "capabilityTokenDigest", tokenDigest(request.capabilityToken()),
                "query", response.query(), "hits", response.hits());
    }

    /** Reads blocks only after the same tenant/role visibility check used for retrieval. */
    @PostMapping("/read-resource-blocks")
    public Map<String, Object> readBlocks(
            @RequestHeader("X-Agent-Worker-Key") String workerKey,
            @Valid @RequestBody AgentToolBrokerReadRequest request) {
        authorize(workerKey);
        authorizeCapability(request.capabilityToken(), request.runId(), request.tenantId(), request.subjectType(),
                request.subjectId(), "read_resource_blocks");
        List<TeacherDocumentBlockResponse> blocks = resourceSearchService.listVisibleBlocks(
                request.tenantId(), request.subjectType(), request.subjectId(), request.documentId());
        return Map.of("runId", request.runId(), "capabilityTokenDigest", tokenDigest(request.capabilityToken()),
                "documentId", request.documentId(), "blocks", blocks);
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
        authorizeCapability(request.capabilityToken(), request.runId(), request.tenantId(), request.subjectType(),
                request.subjectId(), "read_resource_asset");
        try {
            TeacherResourceAssetService.VisibleAsset asset = assetService.openVisibleAsset(
                    request.assetId(), new RequestSubject(request.tenantId(), request.subjectType(), request.subjectId(), "agent-worker"));
            long maximumBytes = environment.getProperty("math-agent.agent-worker.asset-max-bytes", Long.class, 0L);
            byte[] content = readBoundedAsset(asset.resource().getInputStream(), maximumBytes);
            String dataUrl = "data:" + asset.mimeType() + ";base64," + Base64.getEncoder().encodeToString(content);
            return Map.of("runId", request.runId(), "capabilityTokenDigest", tokenDigest(request.capabilityToken()),
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

    /** Rejects forged body identity before any tenant-scoped resource search is attempted. */
    private void authorizeCapability(
            String token, String runId, String tenantId, String subjectType, String subjectId, String tool) {
        AgentRunCapabilityTokenService.Verification verification = capabilityTokenService.verify(
                token, runId, new RequestSubject(tenantId, subjectType, subjectId, "agent-worker"), tool);
        if (!verification.allowed()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, verification.reason());
        }
    }

    private static String tokenDigest(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder encoded = new StringBuilder();
            for (byte item : digest) {
                encoded.append(String.format("%02x", item));
            }
            return encoded.toString();
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
