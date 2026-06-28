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
    private static final String TEACHING_HANDOUT_LATEX_EXPORT_ACTION = "teaching-handout:export-latex";
    private static final String TEACHING_HANDOUT_PDF_EXPORT_ACTION = "teaching-handout:export-pdf";
    private static final String TEACHER_RESOURCE_REGISTER_ACTION = "teacher-resource:register";
    private static final String TEACHER_RESOURCE_ARCHIVE_ACTION = "teacher-resource:archive";
    private static final String TEACHER_RESOURCES_PATH = "/api/teacher/resources";
    private static final String STUDENT_MEMORY_REMEMBER_ACTION = "student-memory:remember";
    private static final String STUDENT_MEMORY_REMEMBER_PATH = "/api/students/memory/remember";
    private static final Set<String> CAPABILITY_ALLOWED_ROLES = Set.of("student", "teacher", "admin");
    private static final Set<String> TEACHER_RESOURCE_ALLOWED_ROLES = Set.of("teacher", "admin");
    private static final Set<String> STUDENT_MEMORY_ALLOWED_ROLES = Set.of("student");

    private final CapabilityTokenStore store;
    private final Clock clock;
    private final CapabilityAuditSink auditSink;

    /**
     * Creates a production service.
     *
     * @param store token store
     */
    @Autowired
    public CapabilityTokenService(CapabilityTokenStore store, CapabilityAuditSink auditSink) {
        this(store, Clock.systemUTC(), auditSink);
    }

    /**
     * Creates a testable service.
     *
     * @param store token store
     * @param clock clock
     */
    public CapabilityTokenService(CapabilityTokenStore store, Clock clock) {
        this(store, clock, new NoopCapabilityAuditSink());
    }

    /**
     * Creates a testable service with explicit audit sink.
     *
     * @param store token store
     * @param clock clock
     * @param auditSink audit sink
     */
    public CapabilityTokenService(CapabilityTokenStore store, Clock clock, CapabilityAuditSink auditSink) {
        this.store = store;
        this.clock = clock;
        this.auditSink = auditSink == null ? new NoopCapabilityAuditSink() : auditSink;
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
        try {
            validateApplication(action, path, normalized);
        } catch (IllegalArgumentException exception) {
            auditApplication(
                    normalized,
                    action,
                    path,
                    request.requestHash(),
                    request.idempotencyKey(),
                    null,
                    "rejected",
                    exception.getMessage());
            throw exception;
        }
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
        auditRecord(saved, "issued", "Capability token issued");
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
            CapabilityConsumeDecision decision = CapabilityConsumeDecision.deny("Missing capability token");
            auditConsumption(null, action, path, requestHash, subject, null, "denied", decision.reason());
            return decision;
        }
        CapabilityTokenRecord record = store.find(token.strip()).orElse(null);
        if (record == null) {
            CapabilityConsumeDecision decision = CapabilityConsumeDecision.deny("Capability token not found");
            auditConsumption(token.strip(), action, path, requestHash, subject, null, "denied", decision.reason());
            return decision;
        }
        CapabilityConsumeDecision validation = validate(record, action, path, requestHash, subject);
        if (!validation.allowed()) {
            auditRecord(record, "denied", validation.reason());
            return validation;
        }
        CapabilityConsumeDecision decision = store.consumeIfUnused(record.token())
                .map(ignored -> CapabilityConsumeDecision.allow())
                .orElseGet(() -> CapabilityConsumeDecision.deny("Capability token already used"));
        auditRecord(record, decision.allowed() ? "consumed" : "denied", decision.reason());
        return decision;
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
        if (TEACHING_SUBMIT_ACTION.equals(action) && TEACHING_TASKS_PATH.equals(path)) {
            if (!CAPABILITY_ALLOWED_ROLES.contains(subject.subjectType()) || subject.subjectId() == null) {
                throw new IllegalArgumentException("Capability subject not allowed");
            }
            return;
        }
        if (TEACHING_HANDOUT_LATEX_EXPORT_ACTION.equals(action)
                && path.startsWith(TEACHING_TASKS_PATH + "/")
                && path.endsWith("/handout/latex")) {
            if (!CAPABILITY_ALLOWED_ROLES.contains(subject.subjectType()) || subject.subjectId() == null) {
                throw new IllegalArgumentException("Capability subject not allowed");
            }
            return;
        }
        if (TEACHING_HANDOUT_PDF_EXPORT_ACTION.equals(action)
                && path.startsWith(TEACHING_TASKS_PATH + "/")
                && path.endsWith("/handout/pdf")) {
            if (!CAPABILITY_ALLOWED_ROLES.contains(subject.subjectType()) || subject.subjectId() == null) {
                throw new IllegalArgumentException("Capability subject not allowed");
            }
            return;
        }
        if (TEACHER_RESOURCE_REGISTER_ACTION.equals(action) && TEACHER_RESOURCES_PATH.equals(path)) {
            validateTeacherResourceSubject(subject);
            return;
        }
        if (TEACHER_RESOURCE_ARCHIVE_ACTION.equals(action) && path.startsWith(TEACHER_RESOURCES_PATH + "/")) {
            validateTeacherResourceSubject(subject);
            return;
        }
        if (STUDENT_MEMORY_REMEMBER_ACTION.equals(action) && STUDENT_MEMORY_REMEMBER_PATH.equals(path)) {
            validateStudentMemorySubject(subject);
            return;
        }
        throw new IllegalArgumentException("Unsupported capability action or path");
    }

    /**
     * Validates subject for high-value teacher resource mutations.
     */
    private static void validateTeacherResourceSubject(RequestSubject subject) {
        if (!TEACHER_RESOURCE_ALLOWED_ROLES.contains(subject.subjectType()) || subject.subjectId() == null) {
            throw new IllegalArgumentException("Capability subject not allowed");
        }
    }

    /**
     * Validates subject for student-owned long-term memory writes.
     */
    private static void validateStudentMemorySubject(RequestSubject subject) {
        if (!STUDENT_MEMORY_ALLOWED_ROLES.contains(subject.subjectType()) || subject.subjectId() == null) {
            throw new IllegalArgumentException("Capability subject not allowed");
        }
    }

    /**
     * Records an audit event from a persisted token record.
     */
    private void auditRecord(CapabilityTokenRecord record, String decision, String reason) {
        auditApplication(
                new RequestSubject(record.tenantId(), record.subjectType(), record.subjectId(), null),
                record.action(),
                record.path(),
                record.requestHash(),
                record.idempotencyKey(),
                record.token(),
                decision,
                reason);
    }

    /**
     * Records an audit event when no stored record may exist.
     */
    private void auditConsumption(
            String token,
            String action,
            String path,
            String requestHash,
            RequestSubject subject,
            String idempotencyKey,
            String decision,
            String reason) {
        auditApplication(subject, action, path, requestHash, idempotencyKey, token, decision, reason);
    }

    /**
     * Writes a normalized capability audit event.
     */
    private void auditApplication(
            RequestSubject subject,
            String action,
            String path,
            String requestHash,
            String idempotencyKey,
            String token,
            String decision,
            String reason) {
        RequestSubject normalized = subject == null
                ? RequestSubject.anonymous("default", "unknown-device")
                : subject.normalize();
        auditSink.record(new CapabilityAuditEvent(
                UUID.randomUUID().toString(),
                Instant.now(clock),
                normalized.tenantId(),
                normalized.subjectType(),
                normalized.subjectId(),
                safeText(action),
                safeText(path),
                safeText(requestHash),
                safeText(idempotencyKey),
                safeText(token),
                safeText(decision),
                safeText(reason)));
    }

    /**
     * Returns stripped text or empty string for audit fields.
     */
    private static String safeText(String value) {
        return value == null || value.isBlank() ? "" : value.strip();
    }
}
