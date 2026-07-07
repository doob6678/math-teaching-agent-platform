package com.doob.mathagent.protocol.controller;

import com.doob.mathagent.protocol.dto.McpConfigurationRequest;
import com.doob.mathagent.protocol.service.ProtocolDiscoveryService;
import com.doob.mathagent.protocol.vo.McpConfigurationResponse;
import com.doob.mathagent.protocol.vo.McpToolDescriptor;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * MCP discovery API. This controller exposes metadata only and never executes tools.
 */
@RestController
public class McpDiscoveryController {

    private final ProtocolDiscoveryService discoveryService;

    /**
     * Creates the MCP discovery controller.
     *
     * @param discoveryService metadata service
     */
    public McpDiscoveryController(ProtocolDiscoveryService discoveryService) {
        this.discoveryService = discoveryService;
    }

    /**
     * Lists MCP tool descriptors for external clients.
     */
    @GetMapping("/api/mcp/tools")
    public List<McpToolDescriptor> tools() {
        return discoveryService.mcpTools();
    }

    /**
     * Validates user-supplied MCP connection values and returns a copyable JSON template.
     */
    @PostMapping("/api/mcp/configuration")
    public ResponseEntity<?> configuration(@Valid @RequestBody McpConfigurationRequest request) {
        try {
            return ResponseEntity.ok(discoveryService.mcpConfiguration(request));
        } catch (IllegalArgumentException exception) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of(
                    "code", "MCP_CONFIGURATION_INVALID",
                    "message", exception.getMessage()));
        }
    }
}
