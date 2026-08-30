package com.doob.mathagent.protocol.controller;

import com.doob.mathagent.infrastructure.security.RequestSubject;
import com.doob.mathagent.infrastructure.security.RequestSubjectResolver;
import com.doob.mathagent.protocol.service.McpClientKeyService;
import com.doob.mathagent.protocol.vo.McpClientKeyCreatedResponse;
import com.doob.mathagent.protocol.vo.McpClientKeyResponse;
import com.doob.mathagent.protocol.vo.McpClientKeyRevocationResponse;
import com.doob.mathagent.protocol.vo.McpConfigurationResponse;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/**
 * Session-owned MCP key lifecycle and configuration endpoints.
 */
@RestController
public class McpKeyController {

    private final McpClientKeyService keyService;
    private final RequestSubjectResolver subjectResolver;
    /**
     * Deployment-owned public base URL for external MCP clients. This cannot be inferred reliably when the browser
     * reaches the API through a development proxy or a reverse proxy that intentionally rewrites the Host header.
     */
    private final String configuredMcpPublicUrl;

    /** Compatibility constructor for focused controller tests without a deployment public MCP endpoint. */
    public McpKeyController(McpClientKeyService keyService, RequestSubjectResolver subjectResolver) {
        this(keyService, subjectResolver, "");
    }

    @Autowired
    public McpKeyController(
            McpClientKeyService keyService,
            RequestSubjectResolver subjectResolver,
            @Value("${math-agent.mcp.public-url:}") String configuredMcpPublicUrl) {
        this.keyService = keyService;
        this.subjectResolver = subjectResolver;
        this.configuredMcpPublicUrl = normalizePublicMcpUrl(configuredMcpPublicUrl);
    }

    /**
     * Lists MCP keys owned by the current authenticated backend account.
     */
    @GetMapping("/api/mcp/keys")
    public List<McpClientKeyResponse> keys(HttpServletRequest request) {
        try {
            return keyService.listKeys(authenticatedSubject(request));
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage(), exception);
        }
    }

    /**
     * Creates one new backend-owned MCP key and returns the raw secret once.
     */
    @PostMapping("/api/mcp/keys")
    public McpClientKeyCreatedResponse createKey(HttpServletRequest request) {
        try {
            return keyService.createKey(authenticatedSubject(request), currentMcpUrl(request));
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage(), exception);
        }
    }

    /**
     * Revokes one owned MCP key.
     */
    @PostMapping("/api/mcp/keys/{keyId}/revoke")
    public McpClientKeyRevocationResponse revokeKey(@PathVariable String keyId, HttpServletRequest request) {
        try {
            return keyService.revokeKey(authenticatedSubject(request), keyId);
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage(), exception);
        }
    }

    /**
     * Physically deletes one owned MCP key. Only keys already in revoked status can be deleted.
     */
    @DeleteMapping("/api/mcp/keys/{keyId}")
    public McpClientKeyRevocationResponse deleteKey(@PathVariable String keyId, HttpServletRequest request) {
        try {
            return keyService.deleteKey(authenticatedSubject(request), keyId);
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage(), exception);
        }
    }

    /**
     * Builds backend-generated MCP configuration for the newest active key of the current user.
     */
    @GetMapping("/api/mcp/configuration/me")
    public McpConfigurationResponse currentConfiguration(HttpServletRequest request) {
        try {
            return keyService.currentConfiguration(authenticatedSubject(request), currentMcpUrl(request));
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage(), exception);
        }
    }

    private RequestSubject authenticatedSubject(HttpServletRequest request) {
        RequestSubject subject = subjectResolver.resolve(request);
        if (subject == null || subject.normalize().subjectId() == null || subject.normalize().subjectId().isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Session is not authenticated");
        }
        return subject.normalize();
    }

    /**
     * Resolves the URL placed in an external MCP configuration. A configured public URL wins because the incoming
     * request may arrive through a proxy whose rewritten host is not reachable by the MCP client.
     */
    private String currentMcpUrl(HttpServletRequest request) {
        return currentMcpUrl(request, configuredMcpPublicUrl);
    }

    private static String currentMcpUrl(HttpServletRequest request, String configuredPublicMcpUrl) {
        if (!configuredPublicMcpUrl.isBlank()) {
            return configuredPublicMcpUrl;
        }
        return ServletUriComponentsBuilder.fromRequestUri(request)
                .replacePath(request.getContextPath() + "/api/mcp")
                .replaceQuery(null)
                .build()
                .toUriString();
    }

    /** Validates the deployment value once so malformed URLs never become a copyable external-client configuration. */
    private static String normalizePublicMcpUrl(String value) {
        String normalized = value == null ? "" : value.strip();
        if (normalized.isBlank()) {
            return "";
        }
        try {
            java.net.URI uri = java.net.URI.create(normalized);
            if (!uri.isAbsolute() || !("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))) {
                throw new IllegalArgumentException("math-agent.mcp.public-url must be an absolute HTTP(S) URL");
            }
            return ServletUriComponentsBuilder.fromUri(uri)
                    .replacePath("/api/mcp")
                    .replaceQuery(null)
                    .build()
                    .toUriString();
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("Invalid MATH_AGENT_MCP_PUBLIC_URL", exception);
        }
    }
}
