package com.doob.mathagent.protocol.service;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration-backed MCP client registry.
 *
 * <p>The registry stores only secret hashes. Raw MCP secrets are accepted in the configuration builder request for
 * validation, hashed in memory, and immediately discarded.
 */
@ConfigurationProperties(prefix = "math-agent.protocol.mcp.registry")
public class McpClientRegistryProperties {

    private List<Client> clients = List.of();

    /**
     * Returns configured MCP clients.
     *
     * @return registered clients
     */
    public List<Client> getClients() {
        return clients;
    }

    /**
     * Updates configured MCP clients from Spring configuration.
     *
     * @param clients registered clients
     */
    public void setClients(List<Client> clients) {
        this.clients = clients == null ? List.of() : List.copyOf(clients);
    }

    /**
     * One registered MCP client key profile.
     *
     * @param clientId stable client id used for admin audit and rotation
     * @param profile profile exposed by this key, such as student or teacher
     * @param secretHash SHA-256 hash in the form {@code sha256:<hex>}
     * @param enabled whether this client key may be used
     */
    public record Client(
            String clientId,
            String profile,
            String secretHash,
            boolean enabled) {
    }
}
