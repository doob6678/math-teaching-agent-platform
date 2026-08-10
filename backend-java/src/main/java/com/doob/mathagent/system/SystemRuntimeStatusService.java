package com.doob.mathagent.system;

import com.doob.mathagent.infrastructure.database.DatabaseMigrationProperties;
import com.doob.mathagent.infrastructure.ai.AiProviderProperties;
import com.doob.mathagent.infrastructure.security.RedisRateLimitProperties;
import com.doob.mathagent.infrastructure.security.config.RedissonClientProperties;
import com.doob.mathagent.retrieval.RedisTextbookSearchCacheProperties;
import com.doob.mathagent.student.service.StudentExplanationHistoryStore;
import com.doob.mathagent.teacher.sync.TeacherSourceSyncProperties;
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
    private final RedisTextbookSearchCacheProperties searchCacheProperties;
    private final VectorIndexService vectorIndexService;
    private final DatabaseMigrationProperties databaseProperties;
    private final StudentExplanationHistoryStore studentExplanationHistoryStore;
    private final AiProviderProperties aiProviderProperties;
    private final InfrastructureDependencyProbe dependencyProbe;

    @org.springframework.beans.factory.annotation.Autowired
    public SystemRuntimeStatusService(
            Environment environment,
            RedisRateLimitProperties rateLimitProperties,
            RedisTextbookSearchCacheProperties searchCacheProperties,
            VectorIndexService vectorIndexService,
            DatabaseMigrationProperties databaseProperties,
            StudentExplanationHistoryStore studentExplanationHistoryStore,
            AiProviderProperties aiProviderProperties,
            InfrastructureDependencyProbe dependencyProbe) {
        this.environment = environment;
        this.rateLimitProperties = rateLimitProperties;
        this.searchCacheProperties = searchCacheProperties;
        this.vectorIndexService = vectorIndexService;
        this.databaseProperties = databaseProperties;
        this.studentExplanationHistoryStore = studentExplanationHistoryStore;
        this.aiProviderProperties = aiProviderProperties;
        this.dependencyProbe = dependencyProbe;
    }

    /** Compatibility constructor for focused unit tests without live infrastructure. */
    public SystemRuntimeStatusService(
            Environment environment,
            RedisRateLimitProperties rateLimitProperties,
            RedisTextbookSearchCacheProperties searchCacheProperties,
            VectorIndexService vectorIndexService,
            DatabaseMigrationProperties databaseProperties,
            StudentExplanationHistoryStore studentExplanationHistoryStore,
            AiProviderProperties aiProviderProperties) {
        this(environment, rateLimitProperties, searchCacheProperties, vectorIndexService, databaseProperties,
                studentExplanationHistoryStore, aiProviderProperties, InfrastructureDependencyProbe.disabled());
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
        InfrastructureDependencyProbe.Result dependencyResult = dependencyProbe == null
                ? InfrastructureDependencyProbe.Result.unprobed()
                : dependencyProbe.probe();
        SystemRuntimeStatusResponse.DependencyStatus dependencies = new SystemRuntimeStatusResponse.DependencyStatus(
                dependencyResult.probed(), dependencyResult.mysql(), dependencyResult.redis(),
                dependencyResult.rabbitmq(), dependencyResult.worker(), dependencyResult.flyway());
        return new SystemRuntimeStatusResponse(
                deploymentStatus(ai, auth, database, redis, vectorStatus, feishu, dependencies),
                ai,
                auth,
                database,
                redis,
                vectorStatus,
                feishu,
                dependencies);
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
        boolean routeEnabled = provider.isEnabled();
        boolean modelConfigured = !safe(provider.getChatModel()).isBlank();
        return new SystemRuntimeStatusResponse.AiProviderStatus(
                safe(provider.getName()).strip().toLowerCase(),
                safe(provider.getChatModel()).strip(),
                !safe(provider.getName()).isBlank() && routeEnabled && modelConfigured,
                routeEnabled,
                false,
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
            SystemRuntimeStatusResponse.FeishuStatus feishu,
            SystemRuntimeStatusResponse.DependencyStatus dependencies) {
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
        if (dependencies.probed() && database.enabled() && (!dependencies.mysql() || !dependencies.flyway())) {
            blockingIssues.add("DATABASE_DEPENDENCY_NOT_READY");
        }
        if (dependencies.probed() && (redis.redissonEnabled() || redis.rateLimitEnabled() || redis.searchCacheEnabled())
                && !dependencies.redis()) {
            blockingIssues.add("REDIS_DEPENDENCY_NOT_READY");
        }
        if (dependencies.probed()
                && booleanProperty("math-agent.rabbitmq.listeners-enabled")
                && !dependencies.rabbitmq()) {
            blockingIssues.add("RABBITMQ_DEPENDENCY_NOT_READY");
        }
        if (dependencies.probed() && vector.enabled() && !dependencies.worker()) {
            blockingIssues.add("AI_WORKER_DEPENDENCY_NOT_READY");
        }
        if (!redis.redissonEnabled()) {
            blockingIssues.add("REDIS_REDISSON_DISABLED");
        }
        if (!redis.rateLimitEnabled()) {
            blockingIssues.add("REDIS_RATE_LIMIT_DISABLED");
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
        if ("milvus_status_error".equalsIgnoreCase(vector.status())) {
            blockingIssues.add("VECTOR_INDEX_DEPENDENCY_UNAVAILABLE");
        }
        if (safe(environment.getProperty("REDIS_PASSWORD")).isBlank()) {
            blockingIssues.add("REDIS_AUTH_NOT_CONFIGURED");
        }
        if (safe(environment.getProperty("RABBITMQ_DEFAULT_PASS")).isBlank()) {
            blockingIssues.add("RABBITMQ_AUTH_NOT_CONFIGURED");
        }
        if (!feishu.processDownloaderEnabled()) {
            blockingIssues.add("FEISHU_PROCESS_DOWNLOADER_DISABLED");
        }
        if (!feishu.downloaderScriptExists()) {
            blockingIssues.add("FEISHU_DOWNLOADER_SCRIPT_NOT_FOUND");
        }
        if (!feishu.appkeyFileExists()) {
            blockingIssues.add("FEISHU_CREDENTIALS_NOT_CONFIGURED");
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
        // The public status field is retained for frontend compatibility, but now represents usable credentials:
        // either the deployment environment pair or the local APPKEY fallback can satisfy the worker.
        boolean appkeyFileExists = properties.credentialsConfigured();
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
