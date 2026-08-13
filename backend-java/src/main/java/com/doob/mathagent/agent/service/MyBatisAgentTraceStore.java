package com.doob.mathagent.agent.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.doob.mathagent.agent.entity.AgentRunTraceEntity;
import com.doob.mathagent.agent.mapper.AgentRunTraceMapper;
import com.doob.mathagent.agent.vo.AgentRunExecuteResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

/**
 * MyBatis-backed agent trace store.
 */
@Repository
@ConditionalOnProperty(prefix = "math-agent.database", name = "enabled", havingValue = "true")
public class MyBatisAgentTraceStore implements AgentTraceStore {

    /** Matches V10's persisted plan_id width; task-local runtime IDs can contain longer diagnostic suffixes. */
    private static final int PLAN_ID_MAX_LENGTH = 96;

    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {
    };
    private static final TypeReference<TraceMetadata> TRACE_METADATA = new TypeReference<>() {
    };

    private final AgentRunTraceMapper mapper;
    private final ObjectMapper objectMapper;

    /**
     * Creates a MyBatis-backed trace store.
     *
     * @param mapper trace mapper
     * @param objectMapper JSON mapper for list fields
     */
    public MyBatisAgentTraceStore(AgentRunTraceMapper mapper, ObjectMapper objectMapper) {
        this.mapper = mapper;
        this.objectMapper = objectMapper;
    }

    /**
     * Saves a trace record into agent_run_trace.
     */
    @Override
    public AgentTraceRecord save(AgentTraceRecord record) {
        AgentRunTraceEntity entity = toEntity(record);
        // A run is first saved as RUNNING for tool authorization and then replaced by its terminal audit record.
        // Updating by trace id keeps that two-stage protocol idempotent across a Worker retry.
        if (mapper.selectById(record.traceId()) == null) {
            mapper.insert(entity);
        } else {
            mapper.updateById(entity);
        }
        return record;
    }

    /**
     * Finds a trace by id.
     */
    @Override
    public Optional<AgentTraceRecord> find(String traceId) {
        if (traceId == null || traceId.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(mapper.selectById(traceId.strip()))
                .map(this::toRecord);
    }

    /**
     * Searches traces with already-scoped criteria.
     */
    @Override
    public List<AgentTraceRecord> search(AgentTraceSearchCriteria criteria) {
        AgentTraceSearchCriteria normalized = criteria.normalize();
        QueryWrapper<AgentRunTraceEntity> wrapper = new QueryWrapper<AgentRunTraceEntity>()
                .eq("tenant_id", normalized.tenantId())
                .orderByDesc("created_at");
        eqIfPresent(wrapper, "subject_type", normalized.subjectType());
        eqIfPresent(wrapper, "subject_id", normalized.subjectId());
        eqIfPresent(wrapper, "agent_code", normalized.agentCode());
        eqIfPresent(wrapper, "status", normalized.status());
        eqIfPresent(wrapper, "plan_id", normalized.planId());
        likeRightIfPresent(wrapper, "plan_id", normalized.planIdPrefix());
        return mapper.selectPage(Page.of(1, normalized.limit()), wrapper)
                .getRecords()
                .stream()
                .map(this::toRecord)
                .toList();
    }

    /**
     * Converts a trace record to a database entity.
     */
    private AgentRunTraceEntity toEntity(AgentTraceRecord record) {
        AgentRunTraceEntity entity = new AgentRunTraceEntity();
        entity.setTraceId(record.traceId());
        entity.setPlanId(databasePlanId(record.planId()));
        entity.setCreatedAt(record.createdAt());
        entity.setTenantId(record.tenantId());
        entity.setSubjectType(record.subjectType());
        entity.setSubjectId(record.subjectId());
        entity.setAgentCode(record.agentCode());
        entity.setProviderName(record.providerName());
        entity.setModelCode(record.modelCode());
        entity.setStatus(record.status());
        entity.setEstimatedCost(record.estimatedCost());
        entity.setAllowedToolScopesJson(writeList(record.allowedToolScopes()));
        entity.setAllowedDataScopesJson(writeList(record.allowedDataScopes()));
        entity.setEvidenceRefsJson(writeList(record.evidenceRefs()));
        entity.setMetadataJson(writeMetadata(new TraceMetadata(
                record.stageTimings(),
                record.actualUsage(),
                safeText(record.message()),
                safeDiagnosticEvents(record.diagnosticEvents()),
                record.actualCost(),
                record.costKnown())));
        return entity;
    }

    /**
     * Keeps the stable task prefix in a database trace without allowing an internal diagnostic suffix to abort a
     * completed teaching workflow. The full task identity remains in the owning teaching-task record.
     */
    private static String databasePlanId(String planId) {
        if (planId == null || planId.length() <= PLAN_ID_MAX_LENGTH) {
            return planId;
        }
        return planId.substring(0, PLAN_ID_MAX_LENGTH);
    }

    /**
     * Converts a database entity to a trace record.
     */
    private AgentTraceRecord toRecord(AgentRunTraceEntity entity) {
        TraceMetadata metadata = readMetadata(entity.getMetadataJson());
        return new AgentTraceRecord(
                entity.getTraceId(),
                entity.getPlanId(),
                entity.getCreatedAt(),
                entity.getTenantId(),
                entity.getSubjectType(),
                entity.getSubjectId(),
                entity.getAgentCode(),
                entity.getProviderName(),
                entity.getModelCode(),
                entity.getStatus(),
                entity.getEstimatedCost() == null ? 0.0d : entity.getEstimatedCost(),
                readList(entity.getAllowedToolScopesJson()),
                readList(entity.getAllowedDataScopesJson()),
                readList(entity.getEvidenceRefsJson()),
                metadata.stageTimings(),
                metadata.actualUsage(),
                metadata.message(),
                metadata.diagnosticEvents(),
                metadata.actualCost(),
                metadata.costKnown());
    }

    /**
     * Serializes string list fields as JSON arrays.
     */
    private String writeList(List<String> values) {
        try {
            return objectMapper.writeValueAsString(values == null ? List.of() : values);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Agent trace list field is not serializable", exception);
        }
    }

    /**
     * Reads JSON array fields defensively.
     */
    private List<String> readList(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(value, STRING_LIST);
        } catch (JsonProcessingException exception) {
            return List.of();
        }
    }

    /**
     * Serializes safe trace metadata without raw prompts or raw model output.
     */
    private String writeMetadata(TraceMetadata metadata) {
        try {
            return objectMapper.writeValueAsString(metadata.normalize());
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Agent trace metadata is not serializable", exception);
        }
    }

    /**
     * Reads optional metadata from older and current trace rows.
     */
    private TraceMetadata readMetadata(String value) {
        if (value == null || value.isBlank()) {
            return TraceMetadata.empty();
        }
        try {
            return objectMapper.readValue(value, TRACE_METADATA).normalize();
        } catch (JsonProcessingException exception) {
            return TraceMetadata.empty();
        }
    }

    /**
     * Adds an equality filter only when a value is present.
     */
    private static void eqIfPresent(QueryWrapper<AgentRunTraceEntity> wrapper, String column, String value) {
        if (value != null) {
            wrapper.eq(column, value);
        }
    }

    /**
     * Adds a prefix filter only when a value is present.
     */
    private static void likeRightIfPresent(QueryWrapper<AgentRunTraceEntity> wrapper, String column, String value) {
        if (value != null) {
            wrapper.likeRight(column, value);
        }
    }

    /**
     * Returns stripped text or an empty string.
     */
    private static String safeText(String value) {
        return value == null || value.isBlank() ? "" : value.strip();
    }

    /**
     * Returns immutable diagnostic events safe for metadata JSON.
     */
    private static List<AgentTraceRecord.DiagnosticEvent> safeDiagnosticEvents(
            List<AgentTraceRecord.DiagnosticEvent> events) {
        return events == null ? List.of() : List.copyOf(events);
    }

    /**
     * Safe metadata stored in agent_run_trace.metadata_json.
     */
    private record TraceMetadata(
            List<AgentRunExecuteResponse.StageTiming> stageTimings,
            AgentRunExecuteResponse.TokenUsage actualUsage,
            String message,
            List<AgentTraceRecord.DiagnosticEvent> diagnosticEvents,
            double actualCost,
            boolean costKnown) {

        /**
         * Returns metadata defaults for old rows and failed metadata parsing.
         */
        private static TraceMetadata empty() {
            return new TraceMetadata(
                    List.of(), new AgentRunExecuteResponse.TokenUsage(0, 0, 0), "", List.of(), -1.0d, false);
        }

        /**
         * Normalizes null fields before returning metadata to callers.
         */
        private TraceMetadata normalize() {
            return new TraceMetadata(
                    stageTimings == null ? List.of() : List.copyOf(stageTimings),
                    actualUsage == null ? new AgentRunExecuteResponse.TokenUsage(0, 0, 0) : actualUsage,
                    safeText(message),
                    safeDiagnosticEvents(diagnosticEvents),
                    actualCost,
                    costKnown);
        }
    }
}
