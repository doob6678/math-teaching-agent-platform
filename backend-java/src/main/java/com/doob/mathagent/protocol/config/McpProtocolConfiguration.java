package com.doob.mathagent.protocol.config;

import com.doob.mathagent.knowledge.service.KnowledgeGraphSpineService;
import com.doob.mathagent.protocol.service.McpClientRegistryProperties;
import com.doob.mathagent.protocol.service.McpClientResolver;
import com.doob.mathagent.protocol.service.McpJsonRpcService;
import com.doob.mathagent.protocol.service.McpToolExecutionService;
import com.doob.mathagent.protocol.service.ProtocolDiscoveryService;
import com.doob.mathagent.resources.TextbookResourceProperties;
import com.doob.mathagent.resources.TextbookResourceService;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Enables MCP protocol configuration objects.
 */
@Configuration
@EnableConfigurationProperties(McpClientRegistryProperties.class)
public class McpProtocolConfiguration {

    /**
     * Creates the standard MCP JSON-RPC service with safe resource readers wired in.
     */
    @Bean
    McpJsonRpcService mcpJsonRpcService(
            ProtocolDiscoveryService discoveryService,
            McpToolExecutionService toolExecutionService,
            McpClientResolver clientResolver,
            TextbookResourceService textbookResourceService,
            TextbookResourceProperties textbookResourceProperties,
            KnowledgeGraphSpineService knowledgeGraphSpineService) {
        return new McpJsonRpcService(
                discoveryService,
                toolExecutionService,
                clientResolver,
                textbookResourceService,
                textbookResourceProperties,
                knowledgeGraphSpineService);
    }
}
