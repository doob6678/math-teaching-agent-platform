package com.doob.mathagent.protocol.service;

import java.util.Optional;

/**
 * Resolves one executable MCP client profile from a presented secret.
 */
public interface McpClientResolver {

    /**
     * Finds one enabled MCP client that matches the presented raw secret.
     *
     * @param secret raw Bearer secret
     * @return resolved client profile when the secret is active
     */
    Optional<McpClientRegistryProperties.Client> findEnabledClientBySecret(String secret);
}
