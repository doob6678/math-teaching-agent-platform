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
import java.util.ArrayList;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

/**
 * MyBatis-backed store for durable multi-agent writing workflow status.
 */
@Repository
@ConditionalOnProperty(prefix = "math-agent.database", name = "enabled", havingValue = "true")
public class MyBatisMultiAgentWritingWorkflowStore implements MultiAgentWritingWorkflowStore {

    private static final int MAX_CAS_RETRIES = 5;

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
     * Saves a workflow snapshot with optimistic merge.
     *
     * <p>Parallel Writer tasks may finish from the same prefix.  Each attempt reloads the newest row, merges stage
     * results monotonically, and updates with a revision predicate.  A stale Worker therefore retries its merge
     * instead of replacing a sibling's result.</p>
     */
    @Override
    public MultiAgentWritingWorkflowRecord save(MultiAgentWritingWorkflowRecord record) {
        MultiAgentWritingWorkflowRecord normalized = record.normalize();
        for (int attempt = 0; attempt < MAX_CAS_RETRIES; attempt++) {
            MultiAgentWritingWorkflowEntity existing = mapper.selectById(normalized.workflowId());
            if (existing == null) {
                MultiAgentWritingWorkflowEntity entity = toEntity(normalized);
                entity.setRevision(0L);
                try {
                    mapper.insert(entity);
                    return normalized;
                } catch (org.springframework.dao.DuplicateKeyException duplicate) {
                    // Another Worker inserted the same workflow between SELECT and INSERT; reload and merge.
                    continue;
                }
            }
            MultiAgentWritingWorkflowRecord merged = merge(existing, normalized);
            MultiAgentWritingWorkflowEntity entity = toEntity(merged);
            long revision = existing.getRevision() == null ? 0L : existing.getRevision();
            entity.setRevision(revision);
            if (mapper.updateIfRevisionMatches(entity, revision) == 1) {
                return merged;
            }
        }
        throw new IllegalStateException("Concurrent multi-agent workflow updates did not converge");
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

    /** Internal Worker lookup used only after the shared Worker key has been authenticated. */
    @Override
    public Optional<MultiAgentWritingWorkflowRecord> findByIdInternal(String workflowId) {
        if (workflowId == null || workflowId.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(mapper.selectById(workflowId.strip())).map(this::toRecord);
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

    /** Merges stage results and terminal status without allowing a stale snapshot to regress completion. */
    private MultiAgentWritingWorkflowRecord merge(
            MultiAgentWritingWorkflowEntity existingEntity,
            MultiAgentWritingWorkflowRecord incoming) {
        MultiAgentWritingWorkflowRecord existing = toRecord(existingEntity);
        List<MultiAgentWritingResponse.StageResult> stages = new ArrayList<>(existing.stages());
        for (MultiAgentWritingResponse.StageResult incomingStage : incoming.stages()) {
            stages.removeIf(stage -> stage.stageCode().equals(incomingStage.stageCode()));
            stages.add(incomingStage);
        }
        // Persisted order is part of the handout contract: evidence must precede the three version writers,
        // and the version writers must retain their declared teacher/student/lecture order.  Lexical sorting
        // would make a concurrent merge look complete while silently changing the user-visible document order.
        stages.sort(java.util.Comparator.comparingInt(stage -> stageOrder(stage.stageCode())));
        String status = "COMPLETED".equals(existing.status()) ? "COMPLETED" : incoming.status();
        return new MultiAgentWritingWorkflowRecord(
                existing.workflowId(),
                existing.tenantId(),
                existing.subjectType(),
                existing.subjectId(),
                status,
                existing.createdAt(),
                incoming.updatedAt(),
                List.copyOf(stages),
                totalUsage(stages),
                incoming.message()).normalize();
    }

    /** Recomputes usage from the merged stage set so stale totals cannot erase a completed sibling's usage. */
    private static AgentRunExecuteResponse.TokenUsage totalUsage(
            List<MultiAgentWritingResponse.StageResult> stages) {
        return new AgentRunExecuteResponse.TokenUsage(
                stages.stream().mapToInt(stage -> stage.actualUsage().promptTokens()).sum(),
                stages.stream().mapToInt(stage -> stage.actualUsage().completionTokens()).sum(),
                stages.stream().mapToInt(stage -> stage.actualUsage().totalTokens()).sum());
    }

    /** Returns the stable persisted order shared by the workflow topology and its exported handout versions. */
    private static int stageOrder(String stageCode) {
        return switch (stageCode) {
            case "resource_curation" -> 0;
            case "teacher_writer" -> 1;
            case "student_writer" -> 2;
            case "lecture_writer" -> 3;
            default -> Integer.MAX_VALUE;
        };
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
