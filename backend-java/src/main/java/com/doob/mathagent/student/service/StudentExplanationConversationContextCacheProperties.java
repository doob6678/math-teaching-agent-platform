package com.doob.mathagent.student.service;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

/** Redis settings for the bounded student-explanation conversation-context projection. */
@ConfigurationProperties(prefix = "math-agent.redis.student-explanation-context-cache")
public record StudentExplanationConversationContextCacheProperties(boolean enabled, String keyPrefix, Duration ttl) {

    @ConstructorBinding
    public StudentExplanationConversationContextCacheProperties {
    }

    public String normalizedKeyPrefix() {
        return keyPrefix == null || keyPrefix.isBlank()
                ? "math-agent:student:conversation-context:v1"
                : keyPrefix.strip();
    }

    public Duration normalizedTtl() {
        return ttl == null || ttl.isNegative() || ttl.isZero() ? Duration.ofMinutes(30) : ttl;
    }
}
