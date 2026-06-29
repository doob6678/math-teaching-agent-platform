package com.doob.mathagent.protocol.controller;

import com.doob.mathagent.protocol.dto.McpConfigurationRequest;
import com.doob.mathagent.protocol.service.ProtocolDiscoveryService;
import com.doob.mathagent.protocol.vo.McpConfigurationResponse;
import com.doob.mathagent.protocol.vo.McpToolDescriptor;
import jakarta.validation.Valid;
import java.util.List;
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
    public McpConfigurationResponse configuration(@Valid @RequestBody McpConfigurationRequest request) {
        return discoveryService.mcpConfiguration(request);
    }
}
