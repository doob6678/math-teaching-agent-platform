package com.doob.mathagent.agent.service;

import com.doob.mathagent.agent.entity.MultiAgentWritingWorkflowEntity;
import com.doob.mathagent.agent.mapper.MultiAgentWritingWorkflowMapper;
import com.doob.mathagent.agent.vo.AgentRunExecuteResponse;
import com.doob.mathagent.agent.vo.MultiAgentWritingResponse;
import com.doob.mathagent.infrastructure.security.RequestSubject;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

/**
 * MyBatis-backed store for durable multi-agent writing workflow status.
 */
@Repository
@ConditionalOnProperty(prefix = "math-agent.database", name = "enabled", havingValue = "true")
public class MyBatisMultiAgentWritingWorkflowStore implements MultiAgentWritingWorkflowStore {

    private static final TypeReference<WorkflowMetadata> WORKFLOW_METADATA = new TypeReference<>() {
    };

    private final MultiAgentWritingWorkflowMapper mapper;
    private final ObjectMapper objectMapper;

    /**
     * Creates a MyBatis workflow store.
     *
     * @param mapper workflow mapper
     * @param objectMapper JSON mapper for safe metadata fields
     */
    public MyBatisMultiAgentWritingWorkflowStore(
            MultiAgentWritingWorkflowMapper mapper,
            ObjectMapper objectMapper) {
        this.mapper = mapper;
        this.objectMapper = objectMapper;
    }

    /**
     * Saves a workflow snapshot using workflow id as the idempotent key.
     */
    @Override
    public MultiAgentWritingWorkflowRecord save(MultiAgentWritingWorkflowRecord record) {
        MultiAgentWritingWorkflowRecord normalized = record.normalize();
        MultiAgentWritingWorkflowEntity entity = toEntity(normalized);
        MultiAgentWritingWorkflowEntity existing = mapper.selectById(normalized.workflowId());
        if (existing == null) {
            mapper.insert(entity);
        } else {
            mapper.updateById(entity);
        }
        return normalized;
    }

    /**
     * Finds a visible workflow snapshot by workflow id.
     */
    @Override
    public Optional<MultiAgentWritingWorkflowRecord> findVisible(String workflowId, RequestSubject subject) {
        RequestSubject normalizedSubject = subject.normalize();
        if (workflowId == null || workflowId.isBlank() || normalizedSubject.subjectId().isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(mapper.selectById(workflowId.strip()))
                .map(this::toRecord)
                .filter(record -> canView(record, normalizedSubject));
    }

    /**
     * Converts a workflow record to a database entity.
     */
    private MultiAgentWritingWorkflowEntity toEntity(MultiAgentWritingWorkflowRecord record) {
        MultiAgentWritingWorkflowEntity entity = new MultiAgentWritingWorkflowEntity();
        entity.setWorkflowId(record.workflowId());
        entity.setTenantId(record.tenantId());
        entity.setSubjectType(record.subjectType());
        entity.setSubjectId(record.subjectId());
        entity.setStatus(record.status());
        entity.setMessage(record.message());
        entity.setMetadataJson(writeMetadata(new WorkflowMetadata(record.stages(), record.totalUsage())));
        entity.setCreatedAt(record.createdAt());
        entity.setUpdatedAt(record.updatedAt());
        return entity;
    }

    /**
     * Converts a database entity to a workflow record.
     */
    private MultiAgentWritingWorkflowRecord toRecord(MultiAgentWritingWorkflowEntity entity) {
        WorkflowMetadata metadata = readMetadata(entity.getMetadataJson());
        return new MultiAgentWritingWorkflowRecord(
                entity.getWorkflowId(),
                entity.getTenantId(),
                entity.getSubjectType(),
                entity.getSubjectId(),
                entity.getStatus(),
                entity.getCreatedAt(),
                entity.getUpdatedAt(),
                metadata.stages(),
                metadata.totalUsage(),
                entity.getMessage()).normalize();
    }

    /**
     * Serializes safe workflow metadata without raw prompt or model output.
     */
    private String writeMetadata(WorkflowMetadata metadata) {
        try {
            return objectMapper.writeValueAsString(metadata.normalize());
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Multi-agent workflow metadata is not serializable", exception);
        }
    }

    /**
     * Reads safe workflow metadata defensively.
     */
    private WorkflowMetadata readMetadata(String value) {
        if (value == null || value.isBlank()) {
            return WorkflowMetadata.empty();
        }
        try {
            return objectMapper.readValue(value, WORKFLOW_METADATA).normalize();
        } catch (JsonProcessingException exception) {
            return WorkflowMetadata.empty();
        }
    }

    /**
     * Checks tenant and owner visibility for a workflow.
     */
    private static boolean canView(MultiAgentWritingWorkflowRecord record, RequestSubject subject) {
        if (!record.tenantId().equals(subject.tenantId())) {
            return false;
        }
        return "admin".equals(subject.subjectType())
                || (record.subjectType().equals(subject.subjectType()) && record.subjectId().equals(subject.subjectId()));
    }

    /**
     * Safe JSON metadata stored in multi_agent_writing_workflow.metadata_json.
     */
    private record WorkflowMetadata(
            List<MultiAgentWritingResponse.StageResult> stages,
            AgentRunExecuteResponse.TokenUsage totalUsage) {

        /**
         * Returns empty metadata defaults.
         */
        private static WorkflowMetadata empty() {
            return new WorkflowMetadata(List.of(), new AgentRunExecuteResponse.TokenUsage(0, 0, 0));
        }

        /**
         * Normalizes null metadata fields.
         */
        private WorkflowMetadata normalize() {
            return new WorkflowMetadata(
                    stages == null ? List.of() : List.copyOf(stages),
                    totalUsage == null ? new AgentRunExecuteResponse.TokenUsage(0, 0, 0) : totalUsage);
        }
    }
}
