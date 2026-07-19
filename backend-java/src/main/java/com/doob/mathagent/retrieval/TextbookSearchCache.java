package com.doob.mathagent.retrieval;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * Cache boundary for textbook retrieval results.
 */
public interface TextbookSearchCache {

    /**
     * Returns whether this cache is backed by shared Redis storage.
     */
    default boolean distributed() {
        return false;
    }

    /**
     * Reads cached hits for a stable corpus/query key.
     */
    Optional<CachedTextbookSearch> find(String cacheKey);

    /**
     * Stores hits with a TTL.
     */
    void put(String cacheKey, CachedTextbookSearch value, Duration ttl);

    /**
     * Cached payload excluding per-request audit fields such as queryId.
     */
    record CachedTextbookSearch(
            String query,
            int limit,
            String retrievalStrategy,
            int total,
            List<TextbookSearchHit> hits) {
    }
}
