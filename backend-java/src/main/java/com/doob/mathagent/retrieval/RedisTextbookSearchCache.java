package com.doob.mathagent.retrieval;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Redis-backed textbook search result cache.
 */
@Component
@ConditionalOnProperty(prefix = "math-agent.redis.search-cache", name = "enabled", havingValue = "true")
public class RedisTextbookSearchCache implements TextbookSearchCache {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final StringRedisTemplate redisTemplate;
    private final RedisTextbookSearchCacheProperties properties;

    public RedisTextbookSearchCache(
            StringRedisTemplate redisTemplate,
            RedisTextbookSearchCacheProperties properties) {
        this.redisTemplate = redisTemplate;
        this.properties = properties;
    }

    @Override
    public boolean distributed() {
        return true;
    }

    @Override
    public Optional<CachedTextbookSearch> find(String cacheKey) {
        String value = redisTemplate.opsForValue().get(redisKey(cacheKey));
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(OBJECT_MAPPER.readValue(value, CachedTextbookSearch.class));
        } catch (Exception e) {
            redisTemplate.delete(redisKey(cacheKey));
            return Optional.empty();
        }
    }

    @Override
    public void put(String cacheKey, CachedTextbookSearch value, Duration ttl) {
        try {
            redisTemplate.opsForValue().set(
                    redisKey(cacheKey),
                    OBJECT_MAPPER.writeValueAsString(value),
                    ttl);
        } catch (Exception ignored) {
            // Search must continue even when Redis is transiently unavailable.
        }
    }

    private String redisKey(String cacheKey) {
        return properties.normalizedKeyPrefix() + ":" + cacheKey;
    }
}
