package com.doob.mathagent.system;

/**
 * Runtime integration status safe for admin UI display.
 */
public record SystemRuntimeStatusResponse(
        DeploymentStatus deployment,
        AiStatus ai,
        AuthStatus auth,
        DatabaseStatus database,
        RedisStatus redis,
        VectorStatus vectorIndex,
        FeishuStatus feishu) {

    /**
     * Deploy readiness summary computed by the backend from durable integration switches.
     */
    public record DeploymentStatus(
            boolean ready,
            String mode,
            java.util.List<String> blockingIssues,
            java.util.List<String> warnings) {
    }

    /**
     * AI provider configuration status without exposing API keys or prompts.
     */
    public record AiStatus(
            String defaultProviderName,
            String defaultModelCode,
            boolean defaultProviderConfigured,
            int enabledProviderCount,
            java.util.List<AiProviderStatus> providers) {
    }

    /**
     * One provider's safe configuration status.
     */
    public record AiProviderStatus(
            String providerName,
            String modelCode,
            boolean configured,
            boolean baseUrlConfigured,
            boolean apiKeyConfigured,
            boolean modelConfigured) {
    }

    /**
     * Authentication persistence mode.
     */
    public record AuthStatus(
            boolean persistentStoreRequired,
            String mode) {
    }

    /**
     * Database persistence status without exposing JDBC URLs or credentials.
     */
    public record DatabaseStatus(
            boolean enabled,
            boolean configured,
            boolean urlConfigured,
            boolean usernameConfigured,
            boolean studentExplanationHistoryDurable,
            boolean migrationRunnerEnabled,
            String migrationLocation,
            String mode) {
    }

    /**
     * Redis feature switches and cache settings without credentials.
     */
    public record RedisStatus(
            boolean redissonEnabled,
            String redissonAddress,
            boolean rateLimitEnabled,
            String rateLimitKeyPrefix,
            boolean capabilityStoreEnabled,
            String capabilityStoreKeyPrefix,
            boolean searchCacheEnabled,
            String searchCacheKeyPrefix,
            String searchCacheTtl) {
    }

    /**
     * Vector index feature switches without credentials.
     */
    public record VectorStatus(
            boolean enabled,
            boolean configured,
            String collectionName,
            int dimension,
            String embeddingModel,
            String milvusUri,
            String collectionState,
            String indexState,
            String loadState,
            long rowCount,
            String status) {
    }

    /**
     * Feishu process sync readiness without exposing APPKEY contents or secret paths.
     */
    public record FeishuStatus(
            boolean processDownloaderEnabled,
            boolean downloaderScriptConfigured,
            boolean downloaderScriptExists,
            boolean appkeyPathConfigured,
            boolean appkeyFileExists,
            boolean stagingRootConfigured,
            boolean stagingRootExistsOrCreatable,
            String defaultUrlHost,
            int smokeMaxFiles,
            int processTimeoutSeconds,
            String mode) {
    }
}
