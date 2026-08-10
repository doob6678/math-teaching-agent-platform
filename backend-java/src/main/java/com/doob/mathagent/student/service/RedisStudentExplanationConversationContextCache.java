package com.doob.mathagent.student.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Redis cache-aside implementation for a normalized conversation context projection.
 *
 * <p>Keys are scoped by hashes of the already-authorized identity boundary. Values deliberately contain only compact
 * model context and never a raw request, image payload, provider response, or full UI card payload.</p>
 */
@Component
@ConditionalOnProperty(prefix = "math-agent.redis.student-explanation-context-cache", name = "enabled", havingValue = "true")
public class RedisStudentExplanationConversationContextCache implements StudentExplanationConversationContextCache {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper().findAndRegisterModules();
    private static final TypeReference<StudentExplanationConversationContext> CONTEXT_TYPE = new TypeReference<>() { };

    private final StringRedisTemplate redisTemplate;
    private final StudentExplanationConversationContextCacheProperties properties;

    public RedisStudentExplanationConversationContextCache(
            StringRedisTemplate redisTemplate,
            StudentExplanationConversationContextCacheProperties properties) {
        this.redisTemplate = redisTemplate;
        this.properties = properties;
    }

    @Override
    public Optional<StudentExplanationConversationContext> find(
            String tenantId, String subjectType, String subjectId, String conversationId) {
        String key = key(tenantId, subjectType, subjectId, conversationId);
        try {
            String value = redisTemplate.opsForValue().get(key);
            if (value == null || value.isBlank()) {
                return Optional.empty();
            }
            StudentExplanationConversationContext context = OBJECT_MAPPER.readValue(value, CONTEXT_TYPE);
            redisTemplate.expire(key, properties.normalizedTtl());
            return Optional.of(context);
        } catch (Exception ignored) {
            deleteQuietly(key);
            return Optional.empty();
        }
    }

    @Override
    public void put(
            String tenantId,
            String subjectType,
            String subjectId,
            String conversationId,
            StudentExplanationConversationContext context) {
        try {
            redisTemplate.opsForValue().set(
                    key(tenantId, subjectType, subjectId, conversationId),
                    OBJECT_MAPPER.writeValueAsString(context == null
                            ? new StudentExplanationConversationContext(List.of(), null)
                            : context),
                    properties.normalizedTtl());
        } catch (Exception ignored) {
            // Cache writes are an optimization; durable history remains authoritative.
        }
    }

    @Override
    public void invalidate(String tenantId, String subjectType, String subjectId, String conversationId) {
        deleteQuietly(key(tenantId, subjectType, subjectId, conversationId));
    }

    private void deleteQuietly(String key) {
        try {
            redisTemplate.delete(key);
        } catch (Exception ignored) {
            // A stale key remains bounded by TTL and cannot bypass the Java ownership check.
        }
    }

    private String key(String tenantId, String subjectType, String subjectId, String conversationId) {
        return properties.normalizedKeyPrefix() + ":" + hash(tenantId) + ":" + hash(subjectType + "\n" + subjectId)
                + ":" + hash(conversationId);
    }

    private static String hash(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest((value == null ? "" : value).getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(24);
            for (int index = 0; index < 12; index += 1) {
                result.append(String.format("%02x", digest[index]));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }
}
