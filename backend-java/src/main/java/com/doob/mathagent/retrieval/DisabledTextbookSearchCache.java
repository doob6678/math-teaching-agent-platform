package com.doob.mathagent.retrieval;

import java.time.Duration;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Explicit no-op cache used for source re-sync and cache-bypass acceptance runs.
 *
 * <p>The retrieval service always depends on the cache boundary.  Providing this bean when Redis result reuse is
 * disabled keeps that dependency stable while guaranteeing every query reads the current MySQL/Milvus state rather
 * than a pre-sync OCR block.</p>
 */
@Component
@ConditionalOnProperty(prefix = "math-agent.redis.search-cache", name = "enabled", havingValue = "false")
public class DisabledTextbookSearchCache implements TextbookSearchCache {

    /** A disabled cache must never return a previously captured retrieval result. */
    @Override
    public Optional<CachedTextbookSearch> find(String cacheKey) {
        return Optional.empty();
    }

    /** Intentionally discards results so later requests observe a re-synchronized source immediately. */
    @Override
    public void put(String cacheKey, CachedTextbookSearch value, Duration ttl) {
        // No state is retained when the caller explicitly disabled search caching.
    }
}
