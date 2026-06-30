package com.doob.mathagent.teacher.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.doob.mathagent.teacher.entity.TeacherResourceSearchAuditHitEntity;
import com.doob.mathagent.teacher.entity.TeacherResourceSearchAuditLogEntity;
import com.doob.mathagent.teacher.mapper.TeacherResourceSearchAuditHitMapper;
import com.doob.mathagent.teacher.mapper.TeacherResourceSearchAuditLogMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

/**
 * MyBatis-backed teacher resource block search audit store.
 */
@Repository
@ConditionalOnProperty(prefix = "math-agent.database", name = "enabled", havingValue = "true")
public class MyBatisTeacherResourceBlockSearchAuditStore
        implements TeacherResourceBlockSearchAuditSink, TeacherResourceBlockSearchAuditLookup {

    private final TeacherResourceSearchAuditLogMapper queryMapper;
    private final TeacherResourceSearchAuditHitMapper hitMapper;

    /**
     * Creates a MyBatis audit store.
     *
     * @param queryMapper query audit mapper
     * @param hitMapper hit audit mapper
     */
    public MyBatisTeacherResourceBlockSearchAuditStore(
            TeacherResourceSearchAuditLogMapper queryMapper,
            TeacherResourceSearchAuditHitMapper hitMapper) {
        this.queryMapper = queryMapper;
        this.hitMapper = hitMapper;
    }

    /**
     * Persists one teacher resource search audit event and its ranked hits.
     *
     * @param event audit event to retain
     */
    @Override
    public void record(TeacherResourceBlockSearchAuditEvent event) {
        if (event == null || event.queryId() == null || event.queryId().isBlank()) {
            return;
        }
        queryMapper.insert(toQueryEntity(event));
        int rank = 1;
        for (TeacherResourceBlockSearchAuditEvent.Hit hit : event.hits()) {
            hitMapper.insert(toHitEntity(event.queryId(), rank, hit));
            rank++;
        }
    }

    /**
     * Looks up a persisted teacher resource search audit event by query id.
     *
     * @param queryId server-generated search query id
     * @return matching event when present
     */
    @Override
    public Optional<TeacherResourceBlockSearchAuditEvent> findByQueryId(String queryId) {
        if (queryId == null || queryId.isBlank()) {
            return Optional.empty();
        }
        String normalizedQueryId = queryId.strip();
        List<TeacherResourceSearchAuditLogEntity> queryRows = queryMapper.selectList(
                new QueryWrapper<TeacherResourceSearchAuditLogEntity>()
                        .eq("query_id", normalizedQueryId));
        if (queryRows.isEmpty()) {
            return Optional.empty();
        }
        List<TeacherResourceSearchAuditHitEntity> hitRows = hitMapper.selectList(
                new QueryWrapper<TeacherResourceSearchAuditHitEntity>()
                        .eq("query_id", normalizedQueryId)
                        .orderByAsc("rank_no"));
        return Optional.of(fromEntities(queryRows.get(0), hitRows));
    }

    /**
     * Converts an audit event to the query log entity.
     *
     * @param event audit event
     * @return query log entity
     */
    private static TeacherResourceSearchAuditLogEntity toQueryEntity(TeacherResourceBlockSearchAuditEvent event) {
        TeacherResourceSearchAuditLogEntity entity = new TeacherResourceSearchAuditLogEntity();
        entity.setQueryId(event.queryId());
        entity.setOccurredAt(Instant.now());
        entity.setTenantId(event.tenantId());
        entity.setSubjectType(event.subjectType());
        entity.setSubjectId(event.subjectId());
        entity.setQueryText(event.query());
        entity.setRequestedLimit(event.limit());
        entity.setRetrievalMode(event.retrievalMode());
        entity.setHitCount(event.hitCount());
        entity.setElapsedMs(event.elapsedMs());
        entity.setEndpoint(event.endpoint());
        return entity;
    }

    /**
     * Converts one audit hit to the hit log entity.
     *
     * @param queryId parent query id
     * @param rank one-based rank
     * @param hit audit hit
     * @return hit log entity
     */
    private static TeacherResourceSearchAuditHitEntity toHitEntity(
            String queryId,
            int rank,
            TeacherResourceBlockSearchAuditEvent.Hit hit) {
        TeacherResourceSearchAuditHitEntity entity = new TeacherResourceSearchAuditHitEntity();
        entity.setQueryId(queryId);
        entity.setRankNo(rank);
        entity.setDocumentId(hit.documentId());
        entity.setDocumentTitle(hit.documentTitle());
        entity.setPermissionScope(hit.permissionScope());
        entity.setBlockId(hit.blockId());
        entity.setBlockType(hit.blockType());
        entity.setBlockOrder(hit.blockOrder());
        entity.setPageNo(hit.pageNo());
        entity.setScore(BigDecimal.valueOf(hit.score()));
        return entity;
    }

    /**
     * Rebuilds an audit event from query and hit rows.
     *
     * @param queryRow query row
     * @param hitRows hit rows
     * @return audit event
     */
    private static TeacherResourceBlockSearchAuditEvent fromEntities(
            TeacherResourceSearchAuditLogEntity queryRow,
            List<TeacherResourceSearchAuditHitEntity> hitRows) {
        return new TeacherResourceBlockSearchAuditEvent(
                queryRow.getQueryId(),
                queryRow.getTenantId(),
                queryRow.getSubjectType(),
                queryRow.getSubjectId(),
                queryRow.getQueryText(),
                intValue(queryRow.getRequestedLimit()),
                queryRow.getRetrievalMode(),
                intValue(queryRow.getHitCount()),
                longValue(queryRow.getElapsedMs()),
                queryRow.getEndpoint(),
                hitRows.stream()
                        .map(MyBatisTeacherResourceBlockSearchAuditStore::fromHitEntity)
                        .toList());
    }

    /**
     * Rebuilds one audit hit from a hit row.
     *
     * @param row hit row
     * @return audit hit
     */
    private static TeacherResourceBlockSearchAuditEvent.Hit fromHitEntity(TeacherResourceSearchAuditHitEntity row) {
        return new TeacherResourceBlockSearchAuditEvent.Hit(
                row.getDocumentId(),
                row.getDocumentTitle(),
                row.getPermissionScope(),
                row.getBlockId(),
                row.getBlockType(),
                intValue(row.getBlockOrder()),
                row.getPageNo(),
                row.getScore() == null ? 0.0 : row.getScore().doubleValue());
    }

    /**
     * Converts nullable Integer to primitive int.
     */
    private static int intValue(Integer value) {
        return value == null ? 0 : value;
    }

    /**
     * Converts nullable Long to primitive long.
     */
    private static long longValue(Long value) {
        return value == null ? 0L : value;
    }
}
