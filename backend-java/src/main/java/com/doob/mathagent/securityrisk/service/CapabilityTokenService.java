package com.doob.mathagent.securityrisk.service;

import com.doob.mathagent.infrastructure.security.RequestSubject;
import com.doob.mathagent.securityrisk.dto.CapabilityTokenApplyRequest;
import com.doob.mathagent.securityrisk.vo.CapabilityTokenResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Issues and consumes one-time capability tokens for high-value operations.
 */
@Service
public class CapabilityTokenService {

    private static final Duration TOKEN_TTL = Duration.ofMinutes(2);
    private static final String TEACHING_SUBMIT_ACTION = "teaching:submit";
    private static final String TEACHING_TASKS_PATH = "/api/teaching/tasks";
    private static final Set<String> CAPABILITY_ALLOWED_ROLES = Set.of("student", "teacher", "admin");

    private final CapabilityTokenStore store;
    private final Clock clock;

    /**
     * Creates a production service.
     *
     * @param store token store
     */
    @Autowired
    public CapabilityTokenService(CapabilityTokenStore store) {
        this(store, Clock.systemUTC());
    }

    /**
     * Creates a testable service.
     *
     * @param store token store
     * @param clock clock
     */
    public CapabilityTokenService(CapabilityTokenStore store, Clock clock) {
        this.store = store;
        this.clock = clock;
    }

    /**
     * Applies for a high-value capability token.
     *
     * @param request apply request
     * @param subject authenticated subject
     * @return issued capability token
     */
    public CapabilityTokenResponse apply(CapabilityTokenApplyRequest request, RequestSubject subject) {
        RequestSubject normalized = subject.normalize();
        String action = request.action().strip();
        String path = request.path().strip();
        validateApplication(action, path, normalized);
        Instant expiresAt = Instant.now(clock).plus(TOKEN_TTL);
        CapabilityTokenRecord record = new CapabilityTokenRecord(
                UUID.randomUUID().toString(),
                normalized.tenantId(),
                normalized.subjectType(),
                normalized.subjectId(),
                action,
                path,
                request.requestHash().strip(),
                request.idempotencyKey().strip(),
                request.maxCost(),
                expiresAt,
                false);
        CapabilityTokenRecord saved = store.save(record);
        return new CapabilityTokenResponse(
                saved.token(),
                saved.action(),
                saved.path(),
                saved.requestHash(),
                saved.expiresAt(),
                saved.maxCost());
    }

    /**
     * Consumes a token when subject, action, path, hash and expiry all match.
     *
     * @param token opaque token
     * @param action expected action
     * @param path expected API path
     * @param requestHash expected request hash
     * @param subject authenticated subject
     * @return consume decision
     */
    public CapabilityConsumeDecision consume(
            String token,
            String action,
            String path,
            String requestHash,
            RequestSubject subject) {
        if (token == null || token.isBlank()) {
            return CapabilityConsumeDecision.deny("Missing capability token");
        }
        CapabilityTokenRecord record = store.find(token.strip()).orElse(null);
        if (record == null) {
            return CapabilityConsumeDecision.deny("Capability token not found");
        }
        CapabilityConsumeDecision validation = validate(record, action, path, requestHash, subject);
        if (!validation.allowed()) {
            return validation;
        }
        return store.consumeIfUnused(record.token())
                .map(ignored -> CapabilityConsumeDecision.allow())
                .orElseGet(() -> CapabilityConsumeDecision.deny("Capability token already used"));
    }

    /**
     * Validates a token record without mutating store state.
     */
    private CapabilityConsumeDecision validate(
            CapabilityTokenRecord record,
            String action,
            String path,
            String requestHash,
            RequestSubject subject) {
        RequestSubject normalized = subject.normalize();
        if (record.consumed()) {
            return CapabilityConsumeDecision.deny("Capability token already used");
        }
        if (Instant.now(clock).isAfter(record.expiresAt())) {
            return CapabilityConsumeDecision.deny("Capability token expired");
        }
        if (!record.tenantId().equals(normalized.tenantId())
                || !record.subjectType().equals(normalized.subjectType())
                || !record.subjectId().equals(normalized.subjectId())) {
            return CapabilityConsumeDecision.deny("Capability token subject mismatch");
        }
        if (!record.action().equals(action) || !record.path().equals(path)) {
            return CapabilityConsumeDecision.deny("Capability token action mismatch");
        }
        if (!record.requestHash().equals(requestHash)) {
            return CapabilityConsumeDecision.deny("Capability token request hash mismatch");
        }
        return CapabilityConsumeDecision.allow();
    }

    /**
     * Validates that the backend may mint a token for this operation and authenticated subject.
     */
    private static void validateApplication(String action, String path, RequestSubject subject) {
        if (!TEACHING_SUBMIT_ACTION.equals(action) || !TEACHING_TASKS_PATH.equals(path)) {
            throw new IllegalArgumentException("Unsupported capability action or path");
        }
        if (!CAPABILITY_ALLOWED_ROLES.contains(subject.subjectType()) || subject.subjectId() == null) {
            throw new IllegalArgumentException("Capability subject not allowed");
        }
    }
}
