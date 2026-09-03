package com.doob.mathagent.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;

/**
 * 真 Redis 集成探针：验证 RedissonApiRateLimiter 在真实 redis:7.4-alpine（WSL docker，
 * 宿主端口映射见根目录 .env 的 MATH_AGENT_REDIS_HOST_PORT，当前为 6380）上的原子限流行为——
 * 窗口内放行 limit 次拒绝第 limit+1 次、窗口翻转后恢复、不同维度 key 互不影响。
 *
 * <p>默认不执行，仅在 {@code MATHAGENT_REDIS_IT=1} 时启用，避免普通 {@code mvn test} 依赖外部
 * 服务；连接地址与密码全部来自环境变量（地址可用 {@code MATHAGENT_REDIS_ADDRESS} 覆盖），
 * 凭据不写入代码库。</p>
 */
@EnabledIfEnvironmentVariable(named = "MATHAGENT_REDIS_IT", matches = "1")
class RedissonApiRateLimiterIT {

    /** 随机前缀把探针写入的 key 与生产限流数据隔离，AfterAll 只清理本次自己的 key。 */
    private static final String LIMITER_PREFIX = "math-agent:rl-it:" + UUID.randomUUID();

    private static RedissonClient redissonClient;
    private static ApiRateLimiter rateLimiter;

    @BeforeAll
    static void connect() {
        String address = System.getenv().getOrDefault("MATHAGENT_REDIS_ADDRESS", "redis://127.0.0.1:6380");
        String password = System.getenv("REDIS_PASSWORD");
        assertThat(password)
                .as("真 Redis 探针需要环境变量 REDIS_PASSWORD（与根目录 .env 同源）")
                .isNotBlank();
        Config config = new Config();
        config.useSingleServer().setAddress(address).setPassword(password);
        redissonClient = Redisson.create(config);
        rateLimiter = new RedissonApiRateLimiter(redissonClient, LIMITER_PREFIX);
    }

    @AfterAll
    static void disconnect() {
        if (redissonClient != null) {
            // 主动清理；即便遗漏，keepAlive(2*window) 也会让限流器 key 在空闲后自动过期。
            redissonClient.getKeys().deleteByPattern(LIMITER_PREFIX + "*");
            redissonClient.shutdown();
        }
    }

    @Test
    void allowsUpToLimitDeniesNextThenRecoversAfterWindowFlips() throws InterruptedException {
        Duration window = Duration.ofSeconds(4);
        int limit = 3;
        String key = "device-it-a";

        // 逐字对齐验收断言之一：连打 limit 次全部放行且 used 递增。
        for (int i = 1; i <= limit; i++) {
            RateLimitUsage usage = rateLimiter.check(key, limit, window, Instant.now());
            assertThat(usage.exceeded()).as("窗口内第 %d 次请求应放行", i).isFalse();
            assertThat(usage.used()).as("窗口内第 %d 次请求的 used", i).isEqualTo(i);
        }

        // 连打的第 limit+1 次：应拒绝，used=limit+1（exceeded() 契约与 429 响应头近似语义）。
        RateLimitUsage denied = rateLimiter.check(key, limit, window, Instant.now());
        assertThat(denied.exceeded()).as("窗口内第 %d 次请求应被拒绝", limit + 1).isTrue();
        assertThat(denied.used()).as("被拒请求上报 used=limit+1").isEqualTo(limit + 1);
        assertThat(denied.limit()).isEqualTo(limit);

        // 不同维度 key 使用独立限流器，不受已耗尽的 device-it-a 影响。
        RateLimitUsage otherKey = rateLimiter.check("device-it-b", limit, window, Instant.now());
        assertThat(otherKey.exceeded()).as("另一个 key 首次请求应放行").isFalse();
        assertThat(otherKey.used()).isEqualTo(1);

        // 等 5 秒（>4 秒窗口）后旧令牌全部过期，窗口翻转，同 key 再次放行。
        Thread.sleep(Duration.ofSeconds(5).toMillis());
        RateLimitUsage afterWindow = rateLimiter.check(key, limit, window, Instant.now());
        assertThat(afterWindow.exceeded()).as("窗口翻转后应重新放行").isFalse();
        assertThat(afterWindow.used()).as("新窗口重新计数从 1 开始").isEqualTo(1);
    }
}
