package com.doob.mathagent.securityrisk.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.doob.mathagent.securityrisk.dto.CapabilityAuditQuery;
import com.doob.mathagent.securityrisk.entity.CapabilityAuditLogEntity;
import com.doob.mathagent.securityrisk.mapper.CapabilityAuditLogMapper;
import com.doob.mathagent.securityrisk.vo.CapabilityAuditLogResponse;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

/**
 * MyBatis-backed capability audit sink.
 */
@Repository
@ConditionalOnProperty(prefix = "math-agent.database", name = "enabled", havingValue = "true")
public class MyBatisCapabilityAuditStore implements CapabilityAuditSink, CapabilityAuditLookup {

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
     * Searches persisted capability audit rows by backend tenant and optional filters.
     *
     * @param query query conditions
     * @return matching audit rows without raw capability tokens
     */
    @Override
    public List<CapabilityAuditLogResponse> search(CapabilityAuditQuery query) {
        CapabilityAuditQuery normalized = query.normalize();
        QueryWrapper<CapabilityAuditLogEntity> wrapper = new QueryWrapper<CapabilityAuditLogEntity>()
                .eq("tenant_id", normalized.tenantId())
                .orderByDesc("occurred_at");
        eqIfPresent(wrapper, "subject_type", normalized.subjectType());
        eqIfPresent(wrapper, "subject_id", normalized.subjectId());
        eqIfPresent(wrapper, "action", normalized.action());
        eqIfPresent(wrapper, "decision", normalized.decision());
        return mapper.selectPage(Page.of(1, normalized.limit()), wrapper)
                .getRecords()
                .stream()
                .map(CapabilityAuditResponses::fromEntity)
                .toList();
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
        entity.setTokenHash(CapabilityAuditResponses.tokenHash(event.token()));
        entity.setDecision(event.decision());
        entity.setReason(event.reason());
        return entity;
    }

    /**
     * Adds an equality filter only when a request parameter is present.
     */
    private static void eqIfPresent(QueryWrapper<CapabilityAuditLogEntity> wrapper, String column, String value) {
        if (value != null) {
            wrapper.eq(column, value);
        }
    }
}
