package com.doob.mathagent.securityrisk.service;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Repository;

/**
 * In-memory capability token store for local development and tests.
 */
@Repository
public class InMemoryCapabilityTokenStore implements CapabilityTokenStore {

    /** Token records keyed by opaque token value. */
    private final Map<String, CapabilityTokenRecord> records = new ConcurrentHashMap<>();

    /**
     * Saves a token record.
     *
     * @param record token record
     * @return saved record
     */
    @Override
    public CapabilityTokenRecord save(CapabilityTokenRecord record) {
        records.put(record.token(), record);
        return record;
    }

    /**
     * Finds a token record.
     *
     * @param token opaque token
     * @return record when present
     */
    @Override
    public Optional<CapabilityTokenRecord> find(String token) {
        return Optional.ofNullable(records.get(token));
    }

    /**
     * Consumes a token exactly once.
     *
     * @param token opaque token
     * @return consumed record when successful
     */
    @Override
    public synchronized Optional<CapabilityTokenRecord> consumeIfUnused(String token) {
        CapabilityTokenRecord current = records.get(token);
        if (current == null || current.consumed()) {
            return Optional.empty();
        }
        CapabilityTokenRecord consumed = current.consume();
        records.put(token, consumed);
        return Optional.of(consumed);
    }
}
