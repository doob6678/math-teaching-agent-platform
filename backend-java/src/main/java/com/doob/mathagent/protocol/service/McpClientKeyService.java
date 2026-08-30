package com.doob.mathagent.protocol.service;

import com.doob.mathagent.infrastructure.security.RequestSubject;
import com.doob.mathagent.protocol.vo.McpClientKeyCreatedResponse;
import com.doob.mathagent.protocol.vo.McpClientKeyResponse;
import com.doob.mathagent.protocol.vo.McpClientKeyRevocationResponse;
import com.doob.mathagent.protocol.vo.McpConfigurationResponse;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

/**
 * Owns MCP key lifecycle for authenticated backend users and resolves external Bearer secrets.
 */
@Service
@Primary
public class McpClientKeyService implements McpClientResolver {

    private static final String ACTIVE = "active";
    private static final String REVOKED = "revoked";
    private static final String DEFAULT_SECRET_ENV_NAME = "MATH_AGENT_MCP_SECRET";
    private static final DateTimeFormatter NAME_TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final McpClientKeyStore keyStore;
    private final ProtocolDiscoveryService discoveryService;
    private final McpClientRegistryProperties configuredClientRegistry;
    private final SecureRandom secureRandom = new SecureRandom();

    /** Compatibility constructor for direct tests that do not need deployment-owned client fallback. */
    public McpClientKeyService(McpClientKeyStore keyStore, ProtocolDiscoveryService discoveryService) {
        this(keyStore, discoveryService, new McpClientRegistryProperties());
    }

    @Autowired
    public McpClientKeyService(
            McpClientKeyStore keyStore,
            ProtocolDiscoveryService discoveryService,
            McpClientRegistryProperties configuredClientRegistry) {
        this.keyStore = keyStore;
        this.discoveryService = discoveryService;
        this.configuredClientRegistry = configuredClientRegistry == null ? new McpClientRegistryProperties() : configuredClientRegistry;
    }

    /**
     * Creates one new backend-owned MCP key and returns the raw secret once.
     */
    public McpClientKeyCreatedResponse createKey(RequestSubject subject, String mcpUrl) {
        RequestSubject normalized = requireAuthenticatedSubject(subject);
        LocalDateTime now = LocalDateTime.now();
        String keyId = UUID.randomUUID().toString();
        String secretKey = generateSecretKey();
        McpClientKeyRecord record = new McpClientKeyRecord(
                keyId,
                normalized.tenantId(),
                normalized.subjectId(),
                normalized.subjectType(),
                generatedName(normalized.subjectType(), now),
                McpClientRegistryProperties.secretHash(secretKey),
                previewSecret(secretKey),
                ACTIVE,
                now,
                now,
                null,
                null);
        keyStore.create(record);
        return new McpClientKeyCreatedResponse(
                record.keyId(),
                record.name(),
                record.tenantId(),
                record.ownerUserId(),
                McpAccessPolicy.normalizeProfile(record.ownerRole()),
                secretKey,
                record.secretPreview(),
                configurationForRecord(record, mcpUrl));
    }

    /**
     * Lists all MCP keys owned by the current backend user.
     */
    public List<McpClientKeyResponse> listKeys(RequestSubject subject) {
        RequestSubject normalized = requireAuthenticatedSubject(subject);
        return keyStore.listByOwner(normalized.tenantId(), normalized.subjectId()).stream()
                .map(McpClientKeyService::toResponse)
                .toList();
    }

    /**
     * Builds backend-generated MCP configuration for the newest active key owned by the current user.
     */
    public McpConfigurationResponse currentConfiguration(RequestSubject subject, String mcpUrl) {
        RequestSubject normalized = requireAuthenticatedSubject(subject);
        McpClientKeyRecord record = keyStore.listByOwner(normalized.tenantId(), normalized.subjectId()).stream()
                .filter(candidate -> ACTIVE.equals(candidate.status()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Current user has no active MCP key"));
        return configurationForRecord(record, mcpUrl);
    }

    /**
     * Revokes one owned MCP key.
     */
    public McpClientKeyRevocationResponse revokeKey(RequestSubject subject, String keyId) {
        RequestSubject normalized = requireAuthenticatedSubject(subject);
        LocalDateTime revokedAt = LocalDateTime.now();
        boolean revoked = keyStore.revoke(normalized.tenantId(), normalized.subjectId(), keyId, revokedAt);
        if (!revoked) {
            throw new IllegalArgumentException("Owned active MCP key not found");
        }
        return new McpClientKeyRevocationResponse(keyId, REVOKED, revokedAt);
    }

    /**
     * Physically deletes one owned MCP key. Only already-revoked keys are deletable so acceptance runs and
     * manual rotation can clean up their own stopped keys without ever dropping a usable credential.
     */
    public McpClientKeyRevocationResponse deleteKey(RequestSubject subject, String keyId) {
        RequestSubject normalized = requireAuthenticatedSubject(subject);
        boolean owned = keyStore.findByOwnerAndKeyId(normalized.tenantId(), normalized.subjectId(), keyId).isPresent();
        if (!owned) {
            throw new IllegalArgumentException("Owned MCP key not found");
        }
        boolean deleted = keyStore.deleteRevoked(normalized.tenantId(), normalized.subjectId(), keyId);
        if (!deleted) {
            throw new IllegalArgumentException("Only revoked MCP keys can be deleted");
        }
        return new McpClientKeyRevocationResponse(keyId, "deleted", LocalDateTime.now());
    }

    @Override
    public Optional<McpClientRegistryProperties.Client> findEnabledClientBySecret(String secret) {
        Optional<McpClientRegistryProperties.Client> persisted = keyStore.findActiveBySecretHash(McpClientRegistryProperties.secretHash(secret))
                .map(record -> {
                    keyStore.updateLastUsedAt(record.keyId(), LocalDateTime.now());
                    return toClient(record);
                });
        // Deployment-owned clients are intentionally hash-only configuration.  They are needed before a user has
        // created a database key (for local WorkBuddy and integration probes), while persisted keys retain priority.
        return persisted.isPresent() ? persisted : configuredClientRegistry.findEnabledClientBySecret(secret);
    }

    private McpConfigurationResponse configurationForRecord(McpClientKeyRecord record, String mcpUrl) {
        return discoveryService.mcpConfiguration(toClient(record), mcpUrl, DEFAULT_SECRET_ENV_NAME, record.secretPreview());
    }

    private static McpClientKeyResponse toResponse(McpClientKeyRecord record) {
        return new McpClientKeyResponse(
                record.keyId(),
                record.name(),
                record.tenantId(),
                record.ownerUserId(),
                McpAccessPolicy.normalizeProfile(record.ownerRole()),
                record.status(),
                record.secretPreview(),
                record.createdAt(),
                record.lastUsedAt(),
                record.revokedAt());
    }

    private static McpClientRegistryProperties.Client toClient(McpClientKeyRecord record) {
        String profile = McpAccessPolicy.normalizeProfile(record.ownerRole());
        return new McpClientRegistryProperties.Client(
                record.keyId(),
                profile,
                record.tenantId(),
                record.ownerUserId(),
                record.secretHash(),
                ACTIVE.equals(record.status()),
                McpAccessPolicy.toolsForProfile(profile),
                McpAccessPolicy.scopesForProfile(profile));
    }

    private RequestSubject requireAuthenticatedSubject(RequestSubject subject) {
        RequestSubject normalized = subject == null ? RequestSubject.anonymous("default", "unknown-device") : subject.normalize();
        if (normalized.subjectId() == null || normalized.subjectId().isBlank()) {
            throw new IllegalArgumentException("Session is not authenticated");
        }
        return normalized;
    }

    private String generateSecretKey() {
        byte[] bytes = new byte[33];
        secureRandom.nextBytes(bytes);
        return "mcp_" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String previewSecret(String secretKey) {
        return secretKey.substring(0, Math.min(4, secretKey.length()))
                + "..."
                + secretKey.substring(secretKey.length() - 4);
    }

    private static String generatedName(String profile, LocalDateTime createdAt) {
        return McpAccessPolicy.normalizeProfile(profile)
                + "-mcp-"
                + NAME_TIMESTAMP.format(createdAt);
    }
}
