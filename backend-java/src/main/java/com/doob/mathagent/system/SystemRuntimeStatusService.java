package com.doob.mathagent.system;

import com.doob.mathagent.infrastructure.database.DatabaseMigrationProperties;
import com.doob.mathagent.infrastructure.ai.AiProviderProperties;
import com.doob.mathagent.infrastructure.security.RedisRateLimitProperties;
import com.doob.mathagent.infrastructure.security.config.RedissonClientProperties;
import com.doob.mathagent.retrieval.RedisTextbookSearchCacheProperties;
import com.doob.mathagent.securityrisk.config.CapabilityTokenStoreProperties;
import com.doob.mathagent.student.service.StudentExplanationHistoryStore;
import com.doob.mathagent.teacher.service.TeacherSourceSyncProperties;
import com.doob.mathagent.vector.service.VectorIndexService;
import com.doob.mathagent.vector.service.VectorIndexStatusResponse;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

/**
 * Assembles safe runtime integration status.
 */
@Service
public class SystemRuntimeStatusService {

    private final Environment environment;
    private final RedisRateLimitProperties rateLimitProperties;
    private final CapabilityTokenStoreProperties capabilityTokenStoreProperties;
    private final RedisTextbookSearchCacheProperties searchCacheProperties;
    private final VectorIndexService vectorIndexService;
    private final DatabaseMigrationProperties databaseProperties;
    private final StudentExplanationHistoryStore studentExplanationHistoryStore;
    private final AiProviderProperties aiProviderProperties;

    public SystemRuntimeStatusService(
            Environment environment,
            RedisRateLimitProperties rateLimitProperties,
            CapabilityTokenStoreProperties capabilityTokenStoreProperties,
            RedisTextbookSearchCacheProperties searchCacheProperties,
            VectorIndexService vectorIndexService,
            DatabaseMigrationProperties databaseProperties,
            StudentExplanationHistoryStore studentExplanationHistoryStore,
            AiProviderProperties aiProviderProperties) {
        this.environment = environment;
        this.rateLimitProperties = rateLimitProperties;
        this.capabilityTokenStoreProperties = capabilityTokenStoreProperties;
        this.searchCacheProperties = searchCacheProperties;
        this.vectorIndexService = vectorIndexService;
        this.databaseProperties = databaseProperties;
        this.studentExplanationHistoryStore = studentExplanationHistoryStore;
        this.aiProviderProperties = aiProviderProperties;
    }

    public SystemRuntimeStatusResponse status() {
        RedissonClientProperties redisson = redissonProperties();
        VectorIndexStatusResponse vector = vectorIndexService.status();
        SystemRuntimeStatusResponse.AiStatus ai = aiStatus();
        SystemRuntimeStatusResponse.DatabaseStatus database = databaseStatus();
        SystemRuntimeStatusResponse.AuthStatus auth = authStatus(database);
        SystemRuntimeStatusResponse.RedisStatus redis = new SystemRuntimeStatusResponse.RedisStatus(
                booleanProperty("math-agent.redis.redisson.enabled"),
                sanitizeRedisAddress(redisson.getAddress()),
                rateLimitProperties.enabled(),
                safe(rateLimitProperties.keyPrefix()),
                capabilityTokenStoreProperties.enabled(),
                safe(capabilityTokenStoreProperties.keyPrefix()),
                searchCacheProperties.enabled(),
                searchCacheProperties.normalizedKeyPrefix(),
                searchCacheProperties.normalizedTtl().toString());
        SystemRuntimeStatusResponse.VectorStatus vectorStatus = new SystemRuntimeStatusResponse.VectorStatus(
                vector.enabled(),
                vector.configured(),
                vector.collectionName(),
                vector.dimension(),
                vector.embeddingModel(),
                vector.milvusUri(),
                vector.collectionState(),
                vector.indexState(),
                vector.loadState(),
                vector.rowCount(),
                vector.status());
        SystemRuntimeStatusResponse.FeishuStatus feishu = feishuStatus();
        return new SystemRuntimeStatusResponse(
                deploymentStatus(ai, auth, database, redis, vectorStatus, feishu),
                ai,
                auth,
                database,
                redis,
                vectorStatus,
                feishu);
    }

    private SystemRuntimeStatusResponse.AiStatus aiStatus() {
        List<SystemRuntimeStatusResponse.AiProviderStatus> providers = List.of(
                aiProviderStatus(aiProviderProperties.getOpenai()),
                aiProviderStatus(aiProviderProperties.getDashscope()),
                aiProviderStatus(aiProviderProperties.getDeepseek()),
                aiProviderStatus(aiProviderProperties.getArk()));
        String defaultProviderName = safe(aiProviderProperties.getDefaultProvider()).strip().toLowerCase();
        SystemRuntimeStatusResponse.AiProviderStatus defaultProvider = providers.stream()
                .filter(provider -> provider.providerName().equals(defaultProviderName))
                .findFirst()
                .orElse(null);
        return new SystemRuntimeStatusResponse.AiStatus(
                defaultProviderName,
                defaultProvider == null ? "" : defaultProvider.modelCode(),
                defaultProvider != null && defaultProvider.configured(),
                (int) providers.stream().filter(SystemRuntimeStatusResponse.AiProviderStatus::configured).count(),
                providers);
    }

    private static SystemRuntimeStatusResponse.AiProviderStatus aiProviderStatus(AiProviderProperties.Provider provider) {
        if (provider == null) {
            return new SystemRuntimeStatusResponse.AiProviderStatus("", "", false, false, false, false);
        }
        boolean baseUrlConfigured = !safe(provider.getBaseUrl()).isBlank();
        boolean apiKeyConfigured = !safe(provider.getApiKey()).isBlank();
        boolean modelConfigured = !safe(provider.getChatModel()).isBlank();
        return new SystemRuntimeStatusResponse.AiProviderStatus(
                safe(provider.getName()).strip().toLowerCase(),
                safe(provider.getChatModel()).strip(),
                !safe(provider.getName()).isBlank() && baseUrlConfigured && apiKeyConfigured && modelConfigured,
                baseUrlConfigured,
                apiKeyConfigured,
                modelConfigured);
    }

    private SystemRuntimeStatusResponse.DatabaseStatus databaseStatus() {
        boolean enabled = databaseProperties.enabled();
        boolean urlConfigured = !safe(databaseProperties.url()).isBlank();
        boolean usernameConfigured = !safe(databaseProperties.username()).isBlank();
        boolean historyDurable = studentExplanationHistoryStore.durable();
        return new SystemRuntimeStatusResponse.DatabaseStatus(
                enabled,
                enabled && urlConfigured && usernameConfigured,
                urlConfigured,
                usernameConfigured,
                historyDurable,
                enabled,
                enabled ? "classpath:db/migration" : "",
                historyDurable ? "mysql" : enabled ? "mysql_not_ready" : "disabled");
    }

    private SystemRuntimeStatusResponse.AuthStatus authStatus(SystemRuntimeStatusResponse.DatabaseStatus database) {
        return new SystemRuntimeStatusResponse.AuthStatus(database.enabled(), "mysql_only");
    }

    private SystemRuntimeStatusResponse.DeploymentStatus deploymentStatus(
            SystemRuntimeStatusResponse.AiStatus ai,
            SystemRuntimeStatusResponse.AuthStatus auth,
            SystemRuntimeStatusResponse.DatabaseStatus database,
            SystemRuntimeStatusResponse.RedisStatus redis,
            SystemRuntimeStatusResponse.VectorStatus vector,
            SystemRuntimeStatusResponse.FeishuStatus feishu) {
        List<String> blockingIssues = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        if (ai.enabledProviderCount() == 0) {
            blockingIssues.add("AI_NO_PROVIDER_CONFIGURED");
        }
        if (!ai.defaultProviderConfigured()) {
            blockingIssues.add("AI_DEFAULT_PROVIDER_NOT_CONFIGURED");
        }
        if (!database.enabled()) {
            blockingIssues.add("DB_PERSISTENCE_DISABLED");
        }
        if (!database.configured()) {
            blockingIssues.add("DB_CONNECTION_NOT_CONFIGURED");
        }
        if (!database.studentExplanationHistoryDurable()) {
            blockingIssues.add("STUDENT_EXPLANATION_HISTORY_NOT_DURABLE");
        }
        if (database.enabled() && !database.migrationRunnerEnabled()) {
            blockingIssues.add("DB_MIGRATION_RUNNER_DISABLED");
        }
        if (!redis.redissonEnabled()) {
            blockingIssues.add("REDIS_REDISSON_DISABLED");
        }
        if (!redis.rateLimitEnabled()) {
            blockingIssues.add("REDIS_RATE_LIMIT_DISABLED");
        }
        if (!redis.capabilityStoreEnabled()) {
            blockingIssues.add("REDIS_CAPABILITY_STORE_DISABLED");
        }
        if (!redis.searchCacheEnabled()) {
            blockingIssues.add("REDIS_SEARCH_CACHE_DISABLED");
        }
        if (!vector.enabled()) {
            blockingIssues.add("VECTOR_INDEX_DISABLED");
        }
        if (!vector.configured()) {
            blockingIssues.add("VECTOR_INDEX_NOT_CONFIGURED");
        }
        if (!feishu.processDownloaderEnabled()) {
            blockingIssues.add("FEISHU_PROCESS_DOWNLOADER_DISABLED");
        }
        if (!feishu.downloaderScriptExists()) {
            blockingIssues.add("FEISHU_DOWNLOADER_SCRIPT_NOT_FOUND");
        }
        if (!feishu.appkeyFileExists()) {
            blockingIssues.add("FEISHU_APPKEY_FILE_NOT_FOUND");
        }
        if (!feishu.stagingRootExistsOrCreatable()) {
            blockingIssues.add("FEISHU_STAGING_ROOT_NOT_READY");
        }
        if (redis.redissonEnabled() && looksLocal(redis.redissonAddress())) {
            warnings.add("REDIS_ADDRESS_LOCALHOST");
        }
        if (vector.enabled() && looksLocal(vector.milvusUri())) {
            warnings.add("MILVUS_URI_LOCALHOST");
        }
        String mode = blockingIssues.isEmpty() ? "deploy_ready" : "needs_configuration";
        return new SystemRuntimeStatusResponse.DeploymentStatus(
                blockingIssues.isEmpty(),
                mode,
                List.copyOf(blockingIssues),
                List.copyOf(warnings));
    }

    private SystemRuntimeStatusResponse.FeishuStatus feishuStatus() {
        TeacherSourceSyncProperties properties = TeacherSourceSyncProperties.fromSpringEnvironment(environment);
        boolean processEnabled = booleanProperty("math-agent.teacher.sync.feishu.process-downloader-enabled");
        boolean downloaderScriptConfigured = properties.feishuDownloaderScript() != null
                && !properties.feishuDownloaderScript().toString().isBlank();
        boolean downloaderScriptExists = downloaderScriptConfigured
                && Files.isRegularFile(properties.feishuDownloaderScript());
        boolean appkeyPathConfigured = properties.feishuAppkeyPath() != null
                && !properties.feishuAppkeyPath().toString().isBlank();
        boolean appkeyFileExists = appkeyPathConfigured && Files.isRegularFile(properties.feishuAppkeyPath());
        boolean stagingRootConfigured = properties.feishuStagingRoot() != null
                && !properties.feishuStagingRoot().toString().isBlank();
        boolean stagingRootReady = stagingRootConfigured && stagingRootExistsOrCreatable(properties.feishuStagingRoot());
        String mode = processEnabled && downloaderScriptExists && appkeyFileExists && stagingRootReady
                ? "process_ready"
                : processEnabled ? "process_needs_configuration" : "disabled";
        return new SystemRuntimeStatusResponse.FeishuStatus(
                processEnabled,
                downloaderScriptConfigured,
                downloaderScriptExists,
                appkeyPathConfigured,
                appkeyFileExists,
                stagingRootConfigured,
                stagingRootReady,
                uriHost(properties.feishuDefaultUrl()),
                properties.feishuSmokeMaxFiles(),
                properties.feishuProcessTimeoutSeconds(),
                mode);
    }

    private RedissonClientProperties redissonProperties() {
        RedissonClientProperties properties = new RedissonClientProperties();
        String address = environment.getProperty("math-agent.redis.redisson.address");
        if (address != null && !address.isBlank()) {
            properties.setAddress(address);
        }
        return properties;
    }

    private boolean booleanProperty(String key) {
        return Boolean.parseBoolean(environment.getProperty(key, "false"));
    }

    private static String sanitizeRedisAddress(String address) {
        String value = safe(address);
        int scheme = value.indexOf("://");
        int at = value.indexOf('@');
        if (scheme >= 0 && at > scheme) {
            return value.substring(0, scheme + 3) + "***@" + value.substring(at + 1);
        }
        return value;
    }

    private static boolean looksLocal(String value) {
        String safeValue = safe(value).toLowerCase();
        return safeValue.contains("127.0.0.1") || safeValue.contains("localhost");
    }

    private static boolean stagingRootExistsOrCreatable(Path path) {
        if (Files.isDirectory(path)) {
            return true;
        }
        Path parent = path.toAbsolutePath().normalize().getParent();
        while (parent != null && !Files.exists(parent)) {
            parent = parent.getParent();
        }
        return parent != null && Files.isDirectory(parent) && Files.isWritable(parent);
    }

    private static String uriHost(String value) {
        try {
            URI uri = URI.create(safe(value));
            return safe(uri.getHost());
        } catch (IllegalArgumentException exception) {
            return "";
        }
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
