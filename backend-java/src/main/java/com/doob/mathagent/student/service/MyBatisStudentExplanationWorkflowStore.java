package com.doob.mathagent.student.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.doob.mathagent.infrastructure.security.RequestSubject;
import com.doob.mathagent.student.dto.StudentExplanationRequest;
import com.doob.mathagent.student.entity.StudentExplanationWorkflowEventEntity;
import com.doob.mathagent.student.entity.StudentExplanationWorkflowRunEntity;
import com.doob.mathagent.student.mapper.StudentExplanationWorkflowEventMapper;
import com.doob.mathagent.student.mapper.StudentExplanationWorkflowRunMapper;
import com.doob.mathagent.student.vo.StudentExplanationResponse;
import com.doob.mathagent.student.vo.StudentExplanationStreamEvent;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Repository;

/** MySQL implementation of the Java-owned explanation workflow store. */
@Repository
public class MyBatisStudentExplanationWorkflowStore implements StudentExplanationWorkflowStore {

    private static final int MAX_EVENT_PAGE_SIZE = 100;

    private final StudentExplanationWorkflowRunMapper runMapper;
    private final StudentExplanationWorkflowEventMapper eventMapper;
    private final ObjectMapper objectMapper;

    public MyBatisStudentExplanationWorkflowStore(
            StudentExplanationWorkflowRunMapper runMapper,
            StudentExplanationWorkflowEventMapper eventMapper,
            ObjectMapper objectMapper) {
        this.runMapper = runMapper;
        this.eventMapper = eventMapper;
        this.objectMapper = objectMapper;
    }

    @Override
    public WorkflowRun createOrLoad(RequestSubject subject, StudentExplanationRequest request) {
        RequestSubject normalizedSubject = subject.normalize();
        StudentExplanationRequest normalizedRequest = request.normalize();
        String clientRequestId = normalizedRequest.clientRequestId() == null
                ? UUID.randomUUID().toString() : normalizedRequest.clientRequestId();
        String requestJson = json(normalizedRequest);
        String fingerprint = sha256(requestJson);
        String subjectId = normalizedSubject.subjectId() == null ? "" : normalizedSubject.subjectId();
        StudentExplanationWorkflowRunEntity entity = new StudentExplanationWorkflowRunEntity();
        entity.setRunId(UUID.randomUUID().toString());
        entity.setTenantId(normalizedSubject.tenantId());
        entity.setSubjectType(normalizedSubject.subjectType());
        entity.setSubjectId(subjectId);
        entity.setClientRequestId(clientRequestId);
        entity.setRequestFingerprint(fingerprint);
        entity.setRequestJson(requestJson);
        entity.setStatus("RUNNING");
        entity.setRetryCount(0);
        try {
            runMapper.insert(entity);
            return toRun(entity, true);
        } catch (DuplicateKeyException duplicate) {
            StudentExplanationWorkflowRunEntity existing = runMapper.selectOne(new LambdaQueryWrapper<StudentExplanationWorkflowRunEntity>()
                    .eq(StudentExplanationWorkflowRunEntity::getTenantId, normalizedSubject.tenantId())
                    .eq(StudentExplanationWorkflowRunEntity::getSubjectType, normalizedSubject.subjectType())
                    .eq(StudentExplanationWorkflowRunEntity::getSubjectId, subjectId)
                    .eq(StudentExplanationWorkflowRunEntity::getClientRequestId, clientRequestId));
            if (existing == null) {
                throw duplicate;
            }
            if (!fingerprint.equals(existing.getRequestFingerprint())) {
                throw new IllegalArgumentException("STUDENT_EXPLANATION_REQUEST_FINGERPRINT_MISMATCH");
            }
            return toRun(existing, false);
        }
    }

    @Override
    public WorkflowEvent append(String runId, String eventName, StudentExplanationStreamEvent event) {
        StudentExplanationWorkflowEventEntity entity = new StudentExplanationWorkflowEventEntity();
        entity.setRunId(runId);
        entity.setEventName(eventName);
        entity.setEventJson(json(event));
        eventMapper.insert(entity);
        return new WorkflowEvent(entity.getEventId(), eventName, event);
    }

    @Override
    public List<WorkflowEvent> eventsAfter(String runId, long afterEventId, int limit) {
        return eventMapper.selectList(new LambdaQueryWrapper<StudentExplanationWorkflowEventEntity>()
                        .eq(StudentExplanationWorkflowEventEntity::getRunId, runId)
                        .gt(StudentExplanationWorkflowEventEntity::getEventId, Math.max(0L, afterEventId))
                        .orderByAsc(StudentExplanationWorkflowEventEntity::getEventId)
                        .last("LIMIT " + Math.max(1, Math.min(limit, MAX_EVENT_PAGE_SIZE))))
                .stream()
                .map(item -> new WorkflowEvent(item.getEventId(), item.getEventName(), read(item.getEventJson(), StudentExplanationStreamEvent.class)))
                .toList();
    }

    @Override
    public void complete(String runId, StudentExplanationResponse response) {
        StudentExplanationWorkflowRunEntity update = new StudentExplanationWorkflowRunEntity();
        update.setStatus("COMPLETED");
        update.setResponseJson(json(response));
        runMapper.update(update, new LambdaQueryWrapper<StudentExplanationWorkflowRunEntity>()
                .eq(StudentExplanationWorkflowRunEntity::getRunId, runId));
    }

    @Override
    public void fail(String runId, String errorCode, String errorMessage) {
        StudentExplanationWorkflowRunEntity update = new StudentExplanationWorkflowRunEntity();
        update.setStatus("FAILED");
        update.setErrorCode(errorCode == null ? "STREAM_FAILED" : errorCode.substring(0, Math.min(64, errorCode.length())));
        update.setErrorMessage(errorMessage == null ? "" : errorMessage.substring(0, Math.min(512, errorMessage.length())));
        runMapper.update(update, new LambdaQueryWrapper<StudentExplanationWorkflowRunEntity>()
                .eq(StudentExplanationWorkflowRunEntity::getRunId, runId));
    }

    private WorkflowRun toRun(StudentExplanationWorkflowRunEntity entity, boolean created) {
        StudentExplanationResponse response = entity.getResponseJson() == null ? null
                : read(entity.getResponseJson(), StudentExplanationResponse.class);
        return new WorkflowRun(entity.getRunId(), entity.getRequestFingerprint(), entity.getStatus(), response,
                entity.getErrorCode(), entity.getErrorMessage(), created);
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Student explanation workflow serialization failed", exception);
        }
    }

    private <T> T read(String value, Class<T> type) {
        try {
            return objectMapper.readValue(value, type);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Student explanation workflow data is invalid", exception);
        }
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(digest.length * 2);
            for (byte item : digest) result.append(String.format("%02x", item));
            return result.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
