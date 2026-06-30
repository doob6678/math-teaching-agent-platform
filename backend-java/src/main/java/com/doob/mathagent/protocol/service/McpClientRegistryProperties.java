package com.doob.mathagent.protocol.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Optional;
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
     * Finds one enabled registered MCP client by the raw Bearer secret received over HTTPS.
     *
     * @param secret raw MCP Bearer secret from the Authorization header
     * @return matching enabled client, or empty when the secret is not registered
     */
    public Optional<Client> findEnabledClientBySecret(String secret) {
        String hashedSecret = secretHash(secret);
        return clients.stream()
                .filter(Client::enabled)
                .filter(client -> hashedSecret.equalsIgnoreCase(blankToEmpty(client.secretHash())))
                .findFirst();
    }

    /**
     * Hashes an MCP secret without storing or logging the raw value.
     *
     * @param secret raw MCP secret
     * @return SHA-256 hash in {@code sha256:<hex>} form
     */
    public static String secretHash(String secret) {
        String normalized = secret == null ? "" : secret.strip();
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(normalized.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder("sha256:");
            for (byte value : digest) {
                builder.append("%02x".formatted(value));
            }
            return builder.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 digest is unavailable", exception);
        }
    }

    /**
     * One registered MCP client key profile.
     *
     * @param clientId stable client id used for admin audit and rotation
     * @param profile profile exposed by this key, such as student or teacher
     * @param tenantId backend tenant bound to this MCP key
     * @param subjectId backend subject id bound to this MCP key
     * @param secretHash SHA-256 hash in the form {@code sha256:<hex>}
     * @param enabled whether this client key may be used
     * @param allowedTools exact MCP tool names this key may execute
     * @param allowedScopes logical data scopes this key may read
     */
    public record Client(
            String clientId,
            String profile,
            String tenantId,
            String subjectId,
            String secretHash,
            boolean enabled,
            List<String> allowedTools,
            List<String> allowedScopes) {

        /**
         * Backward-compatible constructor for discovery-only tests and old local configuration.
         */
        public Client(String clientId, String profile, String secretHash, boolean enabled) {
            this(clientId, profile, "default", clientId, secretHash, enabled, List.of(), List.of());
        }

        /**
         * Normalizes null configuration values while preserving the raw secret hash string.
         */
        public Client {
            profile = blankToDefault(profile, "teacher");
            tenantId = blankToDefault(tenantId, "default");
            subjectId = blankToDefault(subjectId, clientId);
            allowedTools = allowedTools == null ? List.of() : List.copyOf(allowedTools);
            allowedScopes = allowedScopes == null ? List.of() : List.copyOf(allowedScopes);
        }
    }

    /**
     * Converts blank text to an empty string for safe comparisons.
     */
    private static String blankToEmpty(String value) {
        return value == null ? "" : value.strip();
    }

    /**
     * Converts blank text to a default value.
     */
    private static String blankToDefault(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value.strip();
    }
}
