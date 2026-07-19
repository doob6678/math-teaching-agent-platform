package com.doob.mathagent.securityrisk.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;

/**
 * Redisson-backed store for one-time capability tokens.
 */
public class RedissonCapabilityTokenStore implements CapabilityTokenStore {

    private static final long MIN_TTL_MILLIS = 1L;

    private final RedissonClient redissonClient;
    private final ObjectMapper objectMapper;
    private final String keyPrefix;

    /**
     * Creates a store with a default Jackson mapper.
     *
     * @param redissonClient Redisson client
     * @param keyPrefix Redis key prefix
     */
    public RedissonCapabilityTokenStore(RedissonClient redissonClient, String keyPrefix) {
        this(redissonClient, new ObjectMapper().findAndRegisterModules(), keyPrefix);
    }

    /**
     * Creates a store with explicit JSON serialization.
     *
     * @param redissonClient Redisson client
     * @param objectMapper mapper used for stable JSON records
     * @param keyPrefix Redis key prefix
     */
    public RedissonCapabilityTokenStore(
            RedissonClient redissonClient,
            ObjectMapper objectMapper,
            String keyPrefix) {
        this.redissonClient = redissonClient;
        this.objectMapper = objectMapper.findAndRegisterModules();
        this.keyPrefix = keyPrefix == null || keyPrefix.isBlank()
                ? "math-agent:security:capability"
                : keyPrefix.strip();
    }

    /**
     * Stores a token record with Redis TTL derived from expiresAt.
     *
     * @param record token record
     * @return saved record
     */
    @Override
    public CapabilityTokenRecord save(CapabilityTokenRecord record) {
        RBucket<String> bucket = bucket(record.token());
        long ttlMillis = ttlMillis(record.expiresAt());
        bucket.set(toJson(record), ttlMillis, TimeUnit.MILLISECONDS);
        return record;
    }

    /**
     * Reads and deserializes a token record by token.
     *
     * @param token opaque token
     * @return record when present
     */
    @Override
    public Optional<CapabilityTokenRecord> find(String token) {
        return Optional.ofNullable(bucket(token).get()).map(this::fromJson);
    }

    /**
     * Atomically transitions an unused token to consumed with Redis compare-and-set.
     *
     * @param token opaque token
     * @return consumed record when the atomic transition succeeds
     */
    @Override
    public Optional<CapabilityTokenRecord> consumeIfUnused(String token) {
        RBucket<String> bucket = bucket(token);
        String currentJson = bucket.get();
        if (currentJson == null) {
            return Optional.empty();
        }
        CapabilityTokenRecord current = fromJson(currentJson);
        if (current.consumed()) {
            return Optional.empty();
        }
        CapabilityTokenRecord consumed = current.consume();
        boolean updated = bucket.compareAndSet(currentJson, toJson(consumed));
        if (!updated) {
            return Optional.empty();
        }
        bucket.expire(Duration.ofMillis(ttlMillis(current.expiresAt())));
        return Optional.of(consumed);
    }

    /**
     * Returns the Redis bucket for a token key using string codec to avoid JVM-specific object serialization.
     */
    private RBucket<String> bucket(String token) {
        return redissonClient.getBucket("%s:%s".formatted(keyPrefix, token), StringCodec.INSTANCE);
    }

    /**
     * Converts expiresAt into a positive TTL in milliseconds.
     */
    private static long ttlMillis(Instant expiresAt) {
        return Math.max(MIN_TTL_MILLIS, Duration.between(Instant.now(), expiresAt).toMillis());
    }

    /**
     * Serializes a token record as JSON.
     */
    private String toJson(CapabilityTokenRecord record) {
        try {
            return objectMapper.writeValueAsString(record);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Failed to serialize capability token record", exception);
        }
    }

    /**
     * Deserializes a token record from JSON.
     */
    private CapabilityTokenRecord fromJson(String value) {
        try {
            return objectMapper.readValue(value, CapabilityTokenRecord.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Failed to deserialize capability token record", exception);
        }
    }
}
