package com.doob.mathagent.protocol.config;

import com.doob.mathagent.protocol.service.McpClientRegistryProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Enables MCP protocol configuration objects.
 */
@Configuration
@EnableConfigurationProperties(McpClientRegistryProperties.class)
public class McpProtocolConfiguration {
}
