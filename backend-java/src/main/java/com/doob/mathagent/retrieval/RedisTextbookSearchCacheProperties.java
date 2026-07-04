package com.doob.mathagent.retrieval;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Redis cache settings for textbook search results.
 */
@ConfigurationProperties(prefix = "math-agent.redis.search-cache")
public record RedisTextbookSearchCacheProperties(
        boolean enabled,
        String keyPrefix,
        Duration ttl) {

    public String normalizedKeyPrefix() {
        return keyPrefix == null || keyPrefix.isBlank()
                ? "math-agent:retrieval:textbook-search"
                : keyPrefix.strip();
    }

    public Duration normalizedTtl() {
        return ttl == null || ttl.isNegative() || ttl.isZero() ? Duration.ofMinutes(10) : ttl;
    }
}
