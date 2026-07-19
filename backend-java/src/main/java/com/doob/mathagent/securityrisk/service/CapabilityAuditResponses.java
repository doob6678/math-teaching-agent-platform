package com.doob.mathagent.securityrisk.service;

import com.doob.mathagent.securityrisk.entity.CapabilityAuditLogEntity;
import com.doob.mathagent.securityrisk.vo.CapabilityAuditLogResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Shared mapping and hashing helpers for capability audit responses.
 */
final class CapabilityAuditResponses {

    private CapabilityAuditResponses() {
    }

    /**
     * Maps an in-memory event to a reviewer response with a hashed token.
     *
     * @param event in-memory audit event
     * @return reviewer response
     */
    static CapabilityAuditLogResponse fromEvent(CapabilityAuditEvent event) {
        return new CapabilityAuditLogResponse(
                event.eventId(),
                event.occurredAt(),
                event.tenantId(),
                event.subjectType(),
                event.subjectId(),
                event.action(),
                event.path(),
                event.requestHash(),
                event.idempotencyKey(),
                tokenHash(event.token()),
                event.decision(),
                event.reason());
    }

    /**
     * Maps a persisted entity to a reviewer response.
     *
     * @param entity persisted audit entity
     * @return reviewer response
     */
    static CapabilityAuditLogResponse fromEntity(CapabilityAuditLogEntity entity) {
        return new CapabilityAuditLogResponse(
                entity.getEventId(),
                entity.getOccurredAt(),
                entity.getTenantId(),
                entity.getSubjectType(),
                entity.getSubjectId(),
                entity.getAction(),
                entity.getPath(),
                entity.getRequestHash(),
                entity.getIdempotencyKey(),
                entity.getTokenHash(),
                entity.getDecision(),
                entity.getReason());
    }

    /**
     * Hashes raw capability tokens before persistence or response projection.
     *
     * @param token raw capability token
     * @return SHA-256 token hash or empty string when token is absent
     */
    static String tokenHash(String token) {
        if (token == null || token.isBlank()) {
            return "";
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }
}
