package com.doob.mathagent.securityrisk.service;

import com.doob.mathagent.securityrisk.entity.CapabilityAuditLogEntity;
import com.doob.mathagent.securityrisk.mapper.CapabilityAuditLogMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

/**
 * MyBatis-backed capability audit sink.
 */
@Repository
@ConditionalOnProperty(prefix = "math-agent.database", name = "enabled", havingValue = "true")
public class MyBatisCapabilityAuditStore implements CapabilityAuditSink {

    private final CapabilityAuditLogMapper mapper;

    /**
     * Creates a MyBatis capability audit sink.
     *
     * @param mapper capability audit mapper
     */
    public MyBatisCapabilityAuditStore(CapabilityAuditLogMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * Records one capability event into capability_audit_log.
     *
     * @param event capability audit event
     */
    @Override
    public void record(CapabilityAuditEvent event) {
        if (event == null) {
            return;
        }
        mapper.insert(toEntity(event));
    }

    /**
     * Converts an audit event to a database entity.
     *
     * @param event audit event
     * @return database entity
     */
    private static CapabilityAuditLogEntity toEntity(CapabilityAuditEvent event) {
        CapabilityAuditLogEntity entity = new CapabilityAuditLogEntity();
        entity.setEventId(event.eventId());
        entity.setOccurredAt(event.occurredAt());
        entity.setTenantId(event.tenantId());
        entity.setSubjectType(event.subjectType());
        entity.setSubjectId(event.subjectId());
        entity.setAction(event.action());
        entity.setPath(event.path());
        entity.setRequestHash(event.requestHash());
        entity.setIdempotencyKey(event.idempotencyKey());
        entity.setTokenHash(tokenHash(event.token()));
        entity.setDecision(event.decision());
        entity.setReason(event.reason());
        return entity;
    }

    /**
     * Hashes raw capability tokens before persistence.
     *
     * @param token raw token
     * @return SHA-256 token hash or empty string when token is absent
     */
    private static String tokenHash(String token) {
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
