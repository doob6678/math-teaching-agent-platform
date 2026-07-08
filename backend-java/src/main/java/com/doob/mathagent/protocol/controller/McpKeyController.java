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

    public McpKeyController(McpClientKeyService keyService, RequestSubjectResolver subjectResolver) {
        this.keyService = keyService;
        this.subjectResolver = subjectResolver;
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

    private static String currentMcpUrl(HttpServletRequest request) {
        return ServletUriComponentsBuilder.fromRequestUri(request)
                .replacePath(request.getContextPath() + "/api/mcp")
                .replaceQuery(null)
                .build()
                .toUriString();
    }
}
