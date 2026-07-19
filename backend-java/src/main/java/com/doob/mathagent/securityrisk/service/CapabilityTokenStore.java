package com.doob.mathagent.securityrisk.service;

import java.util.Optional;

/**
 * Store for one-time capability tokens.
 */
public interface CapabilityTokenStore {

    /**
     * Saves a capability token record.
     *
     * @param record token record
     * @return saved record
     */
    CapabilityTokenRecord save(CapabilityTokenRecord record);

    /**
     * Finds a token record.
     *
     * @param token opaque token
     * @return record when present
     */
    Optional<CapabilityTokenRecord> find(String token);

    /**
     * Atomically marks a token as consumed when it is currently unused.
     *
     * @param token opaque token
     * @return consumed record when the transition succeeded
     */
    Optional<CapabilityTokenRecord> consumeIfUnused(String token);
}
