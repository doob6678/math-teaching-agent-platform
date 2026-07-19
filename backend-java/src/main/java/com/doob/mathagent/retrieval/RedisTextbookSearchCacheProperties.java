package com.doob.mathagent.retrieval;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

/**
 * Redis cache settings for textbook search results.
 */
@ConfigurationProperties(prefix = "math-agent.redis.search-cache")
public record RedisTextbookSearchCacheProperties(
        boolean enabled,
        String keyPrefix,
        Duration ttl,
        Duration nullValueTtl) {

    /**
     * Selects the complete configuration shape for Spring binding while preserving the three-argument constructor
     * used by focused tests and legacy callers.
     */
    @ConstructorBinding
    public RedisTextbookSearchCacheProperties {
    }

    public RedisTextbookSearchCacheProperties(boolean enabled, String keyPrefix, Duration ttl) {
        this(enabled, keyPrefix, ttl, Duration.ofMinutes(1));
    }

    public String normalizedKeyPrefix() {
        return keyPrefix == null || keyPrefix.isBlank()
                ? "math-agent:retrieval:textbook-search"
                : keyPrefix.strip();
    }

    public Duration normalizedTtl() {
        return ttl == null || ttl.isNegative() || ttl.isZero() ? Duration.ofMinutes(10) : ttl;
    }

    /**
     * \u7a7a\u68c0\u7d22\u7ed3\u679c\u4f7f\u7528\u66f4\u77ed TTL\uff0c\u907f\u514d\u65e0\u6548\u67e5\u8be2\u6301\u7eed\u7a7f\u900f\u5230 BM25 \u4e0e\u5411\u91cf\u68c0\u7d22\uff0c\u540c\u65f6\u8ba9\u65b0\u589e\u8bed\u6599\u5c3d\u5feb\u751f\u6548\u3002
     */
    public Duration normalizedNullValueTtl() {
        return nullValueTtl == null || nullValueTtl.isNegative() || nullValueTtl.isZero()
                ? Duration.ofMinutes(1)
                : nullValueTtl;
    }
}
