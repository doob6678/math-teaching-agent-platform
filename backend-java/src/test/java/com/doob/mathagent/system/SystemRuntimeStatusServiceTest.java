package com.doob.mathagent.system;

import static org.assertj.core.api.Assertions.assertThat;

import com.doob.mathagent.infrastructure.ai.AiProviderProperties;
import com.doob.mathagent.infrastructure.database.DatabaseMigrationProperties;
import com.doob.mathagent.infrastructure.security.RequestSubject;
import com.doob.mathagent.infrastructure.security.RedisRateLimitProperties;
import com.doob.mathagent.retrieval.RedisTextbookSearchCacheProperties;
import com.doob.mathagent.securityrisk.config.CapabilityTokenStoreProperties;
import com.doob.mathagent.student.dto.StudentExplanationRequest;
import com.doob.mathagent.student.service.StudentExplanationHistoryStore;
import com.doob.mathagent.student.service.StudentExplanationHistorySummary;
import com.doob.mathagent.student.service.StudentExplanationImageRecord;
import com.doob.mathagent.student.vo.StudentExplanationResponse;
import com.doob.mathagent.teacher.service.InMemoryTeacherDocumentBlockStore;
import com.doob.mathagent.teacher.service.InMemoryTeacherResourceStore;
import com.doob.mathagent.vector.service.VectorIndexProperties;
import com.doob.mathagent.vector.service.VectorIndexService;
import com.doob.mathagent.vector.service.VectorHttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;

class SystemRuntimeStatusServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void runtimeStatusShowsRedisCacheAndMasksPasswordInRedisAddress() throws Exception {
        Path script = file(tempDir.resolve("download_feishu_url.py"));
        Path appkey = file(tempDir.resolve("APPKEY.md"));
        Path staging = Files.createDirectories(tempDir.resolve("staging"));
        MockEnvironment environment = new MockEnvironment()
                .withProperty("math-agent.redis.redisson.enabled", "true")
                .withProperty("math-agent.redis.redisson.address", "redis://user:secret@127.0.0.1:6379")
                .withProperty("math-agent.teacher.sync.feishu.process-downloader-enabled", "true")
                .withProperty("math-agent.teacher.sync.feishu.default-url", "https://my.feishu.cn/drive/folder/root")
                .withProperty("math-agent.teacher.sync.feishu.downloader-script", script.toString())
                .withProperty("math-agent.teacher.sync.feishu.appkey-path", appkey.toString())
                .withProperty("math-agent.teacher.sync.feishu.staging-root", staging.toString());
        SystemRuntimeStatusService service = new SystemRuntimeStatusService(
                environment,
                new RedisRateLimitProperties(true, "math-agent:test:rate-limit"),
                new CapabilityTokenStoreProperties(true, "math-agent:test:capability"),
                new RedisTextbookSearchCacheProperties(true, "math-agent:test:search", Duration.ofMinutes(3), Duration.ofMinutes(1)),
                new VectorIndexService(
                        new VectorIndexProperties(false, "", "", "math_agent_resource_blocks", 1024, "", "", "", 10000),
                        SystemRuntimeStatusServiceTest::vectorStatusResponse,
                new InMemoryTeacherResourceStore(),
                new InMemoryTeacherDocumentBlockStore()),
                new DatabaseMigrationProperties(true, "jdbc:mysql://localhost:3306/math_agent", "math_user", ""),
                durableHistoryStore(true),
                aiProperties("openai-key"));

        SystemRuntimeStatusResponse response = service.status();

        assertThat(response.deployment().ready()).isFalse();
        assertThat(response.deployment().mode()).isEqualTo("needs_configuration");
        assertThat(response.deployment().blockingIssues()).contains("VECTOR_INDEX_DISABLED", "VECTOR_INDEX_NOT_CONFIGURED");
        assertThat(response.ai().defaultProviderName()).isEqualTo("openai");
        assertThat(response.ai().defaultModelCode()).isEqualTo("gpt-5.4");
        assertThat(response.ai().defaultProviderConfigured()).isTrue();
        assertThat(response.ai().enabledProviderCount()).isEqualTo(1);
        assertThat(response.auth().mode()).isEqualTo("mysql_only");
        assertThat(response.database().enabled()).isTrue();
        assertThat(response.database().configured()).isTrue();
        assertThat(response.database().studentExplanationHistoryDurable()).isTrue();
        assertThat(response.database().mode()).isEqualTo("mysql");
        assertThat(response.redis().redissonEnabled()).isTrue();
        assertThat(response.redis().redissonAddress()).isEqualTo("redis://***@127.0.0.1:6379");
        assertThat(response.redis().rateLimitEnabled()).isTrue();
        assertThat(response.redis().capabilityStoreEnabled()).isTrue();
        assertThat(response.redis().searchCacheEnabled()).isTrue();
        assertThat(response.redis().searchCacheTtl()).isEqualTo("PT3M");
        assertThat(response.vectorIndex().status()).isEqualTo("disabled");
        assertThat(response.feishu().mode()).isEqualTo("process_ready");
        assertThat(response.feishu().appkeyFileExists()).isTrue();
        assertThat(response.feishu().defaultUrlHost()).isEqualTo("my.feishu.cn");
    }

    @Test
    void runtimeStatusShowsDatabaseDisabledAsBlockingConfigurationError() {
        SystemRuntimeStatusService service = new SystemRuntimeStatusService(
                new MockEnvironment(),
                new RedisRateLimitProperties(false, "math-agent:test:rate-limit"),
                new CapabilityTokenStoreProperties(false, "math-agent:test:capability"),
                new RedisTextbookSearchCacheProperties(false, "math-agent:test:search", Duration.ofMinutes(3), Duration.ofMinutes(1)),
                new VectorIndexService(
                        new VectorIndexProperties(false, "", "", "math_agent_resource_blocks", 1024, "", "", "", 10000),
                        SystemRuntimeStatusServiceTest::vectorStatusResponse,
                new InMemoryTeacherResourceStore(),
                new InMemoryTeacherDocumentBlockStore()),
                new DatabaseMigrationProperties(false, "", "", ""),
                durableHistoryStore(false),
                aiProperties(""));

        SystemRuntimeStatusResponse response = service.status();

        assertThat(response.deployment().ready()).isFalse();
        assertThat(response.deployment().mode()).isEqualTo("needs_configuration");
        assertThat(response.deployment().blockingIssues())
                .contains("AI_NO_PROVIDER_CONFIGURED", "AI_DEFAULT_PROVIDER_NOT_CONFIGURED",
                        "DB_PERSISTENCE_DISABLED", "STUDENT_EXPLANATION_HISTORY_NOT_DURABLE");
        assertThat(response.ai().defaultProviderConfigured()).isFalse();
        assertThat(response.ai().enabledProviderCount()).isZero();
        assertThat(response.auth().mode()).isEqualTo("mysql_only");
        assertThat(response.auth().persistentStoreRequired()).isFalse();
        assertThat(response.database().enabled()).isFalse();
        assertThat(response.database().configured()).isFalse();
        assertThat(response.database().studentExplanationHistoryDurable()).isFalse();
        assertThat(response.database().mode()).isEqualTo("disabled");
        assertThat(response.feishu().processDownloaderEnabled()).isFalse();
        assertThat(response.feishu().mode()).isEqualTo("disabled");
    }

    @Test
    void runtimeStatusShowsDeployReadyWhenDurableIntegrationsAreConfigured() throws Exception {
        Path script = file(tempDir.resolve("download_feishu_url.py"));
        Path appkey = file(tempDir.resolve("APPKEY.md"));
        Path staging = Files.createDirectories(tempDir.resolve("staging"));
        MockEnvironment environment = new MockEnvironment()
                .withProperty("math-agent.redis.redisson.enabled", "true")
                .withProperty("math-agent.redis.redisson.address", "redis://redis.internal:6379")
                .withProperty("math-agent.teacher.sync.feishu.process-downloader-enabled", "true")
                .withProperty("math-agent.teacher.sync.feishu.downloader-script", script.toString())
                .withProperty("math-agent.teacher.sync.feishu.appkey-path", appkey.toString())
                .withProperty("math-agent.teacher.sync.feishu.staging-root", staging.toString())
                .withProperty("math-agent.teacher.sync.feishu.smoke-max-files", "2")
                .withProperty("math-agent.teacher.sync.feishu.process-timeout-seconds", "45");
        SystemRuntimeStatusService service = new SystemRuntimeStatusService(
                environment,
                new RedisRateLimitProperties(true, "math-agent:test:rate-limit"),
                new CapabilityTokenStoreProperties(true, "math-agent:test:capability"),
                new RedisTextbookSearchCacheProperties(true, "math-agent:test:search", Duration.ofMinutes(3), Duration.ofMinutes(1)),
                new VectorIndexService(
                        new VectorIndexProperties(
                                true,
                                "http://milvus.internal:19530",
                                "token",
                                "math_agent_resource_blocks",
                                1024,
                                "https://api.example.com/v1",
                                "embedding-key",
                                "text-embedding-3-small",
                                10000),
                        SystemRuntimeStatusServiceTest::vectorStatusResponse,
                        new InMemoryTeacherResourceStore(),
                        new InMemoryTeacherDocumentBlockStore()),
                new DatabaseMigrationProperties(true, "jdbc:mysql://mysql.internal:3306/math_agent", "math_user", ""),
                durableHistoryStore(true),
                aiProperties("openai-key"));

        SystemRuntimeStatusResponse response = service.status();

        assertThat(response.deployment().ready()).isTrue();
        assertThat(response.deployment().mode()).isEqualTo("deploy_ready");
        assertThat(response.deployment().blockingIssues()).isEmpty();
        assertThat(response.deployment().warnings()).isEmpty();
        assertThat(response.vectorIndex().status()).isEqualTo("searchable");
        assertThat(response.feishu().mode()).isEqualTo("process_ready");
        assertThat(response.feishu().smokeMaxFiles()).isEqualTo(2);
        assertThat(response.feishu().processTimeoutSeconds()).isEqualTo(45);
    }

    private static AiProviderProperties aiProperties(String openAiKey) {
        AiProviderProperties properties = new AiProviderProperties();
        properties.getOpenai().setApiKey(openAiKey);
        properties.getDashscope().setApiKey("");
        properties.getDeepseek().setApiKey("");
        properties.getArk().setApiKey("");
        return properties;
    }

    private static StudentExplanationHistoryStore durableHistoryStore(boolean durable) {
        return new StudentExplanationHistoryStore() {
            @Override
            public boolean durable() {
                return durable;
            }

            @Override
            public void save(
                    StudentExplanationRequest request,
                    RequestSubject subject,
                    StudentExplanationImageRecord imageRecord,
                    StudentExplanationResponse response) {
            }

            @Override
            public List<StudentExplanationHistorySummary> findRecent(
                    String tenantId,
                    String subjectType,
                    String subjectId,
                    String conversationId,
                    int limit) {
                return List.of();
            }

            @Override
            public List<com.doob.mathagent.student.service.StudentExplanationConversationSummary> listConversations(
                    String tenantId,
                    String subjectType,
                    String subjectId,
                    int limit) {
                return List.of();
            }

            @Override
            public com.doob.mathagent.student.service.StudentExplanationConversationDetail loadConversation(
                    String tenantId,
                    String subjectType,
                    String subjectId,
                    String conversationId,
                    int limit) {
                return null;
            }
        };
    }

    private static Path file(Path path) throws Exception {
        Files.createDirectories(path.getParent());
        return Files.writeString(path, "test");
    }

    private static VectorHttpResponse vectorStatusResponse(
            java.net.URI uri,
            java.util.Map<String, String> headers,
            String body,
            Duration timeout) {
        if (uri.toString().endsWith("/collections/describe")) {
            return new VectorHttpResponse(200, "{\"code\":0,\"data\":{\"state\":\"exists\"}}");
        }
        if (uri.toString().endsWith("/indexes/describe")) {
            return new VectorHttpResponse(200, "{\"code\":0,\"data\":[{\"indexState\":\"Finished\"}]}");
        }
        if (uri.toString().endsWith("/collections/get_load_state")) {
            return new VectorHttpResponse(200, "{\"code\":0,\"data\":{\"loadState\":\"LoadStateLoaded\"}}");
        }
        if (uri.toString().endsWith("/entities/query")) {
            return new VectorHttpResponse(200, "{\"code\":0,\"data\":[{\"count(*)\":7}]}");
        }
        return new VectorHttpResponse(404, "{}");
    }
}
