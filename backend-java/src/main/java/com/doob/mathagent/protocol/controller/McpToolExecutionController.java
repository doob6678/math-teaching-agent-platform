package com.doob.mathagent.protocol.controller;

import com.doob.mathagent.protocol.dto.McpToolCallRequest;
import com.doob.mathagent.protocol.service.McpToolExecutionService;
import com.doob.mathagent.protocol.vo.McpToolCallResponse;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * MCP tool execution API for registered local clients such as WorkBuddy.
 */
@RestController
public class McpToolExecutionController {

    private final McpToolExecutionService executionService;

    /**
     * Creates the MCP tool execution controller.
     *
     * @param executionService execution service with registry and tool allow-list checks
     */
    public McpToolExecutionController(McpToolExecutionService executionService) {
        this.executionService = executionService;
    }

    /**
     * Executes one MCP tool using the Authorization Bearer secret bound to a backend subject.
     */
    @PostMapping("/api/mcp/tools/{toolName}/call")
    public McpToolCallResponse callTool(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable String toolName,
            @RequestBody(required = false) McpToolCallRequest request) {
        return executionService.callTool(authorization, toolName, request == null ? new McpToolCallRequest(null) : request);
    }
}
