package com.doob.mathagent.securityrisk.service;

import com.doob.mathagent.infrastructure.security.RequestSubject;
import com.doob.mathagent.securityrisk.dto.CapabilityTokenApplyRequest;
import com.doob.mathagent.securityrisk.vo.CapabilityTokenResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
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
    private static final String TEACHING_HANDOUT_LATEX_PREVIEW_ACTION = "teaching-handout:preview-latex";
    private static final String TEACHING_HANDOUT_PDF_EXPORT_ACTION = "teaching-handout:export-pdf";
    private static final String TEACHING_HANDOUT_BATCH_ZIP_EXPORT_ACTION = "teaching-handout:batch-export-zip";
    private static final String TEACHING_HANDOUT_BATCH_ZIP_DOWNLOAD_ACTION = "teaching-handout:batch-download-zip";
    private static final String TEACHING_FEEDBACK_SUBMIT_ACTION = "teaching-feedback:submit";
    private static final String TEACHING_BATCH_ZIP_PATH = "/api/teaching/handouts/batch/zip";
    private static final String TEACHER_RESOURCE_REGISTER_ACTION = "teacher-resource:register";
    private static final String TEACHER_RESOURCE_ARCHIVE_ACTION = "teacher-resource:archive";
    private static final String TEACHER_RESOURCE_SYNC_ACTION = "teacher-resource:sync";
    private static final String TEACHER_RESOURCE_SYNC_EXECUTE_ACTION = "teacher-resource:sync-execute";
    private static final String TEACHER_RESOURCE_SYNC_RESUME_ACTION = "teacher-resource:sync-resume";
    private static final String TEACHER_RESOURCES_PATH = "/api/teacher/resources";
    private static final String STUDENT_MEMORY_REMEMBER_ACTION = "student-memory:remember";
    private static final String STUDENT_MEMORY_REMEMBER_PATH = "/api/students/memory/remember";
    private static final String STUDENT_DASHBOARD_REFRESH_ACTION = "student-dashboard:refresh";
    private static final String STUDENT_DASHBOARD_REFRESH_PATH = "/api/students/dashboard/refresh";
    private static final String KNOWLEDGE_POINT_CREATE_ACTION = "knowledge-point:create";
    private static final String KNOWLEDGE_POINTS_PATH = "/api/knowledge/points";
    private static final String QUESTION_BANK_CREATE_ACTION = "question-bank:create";
    private static final String QUESTION_BANK_ITEMS_PATH = "/api/question-bank/items";
    private static final String QUESTION_BANK_IMPORT_TEACHER_RESOURCE_ACTION = "question-bank:import-teacher-resource";
    private static final String QUESTION_BANK_IMPORT_TEACHER_RESOURCE_PATH_PREFIX =
            "/api/question-bank/import/teacher-resources";
    private static final String VECTOR_INDEX_REBUILD_ACTION = "vector-index:rebuild";
    private static final String VECTOR_INDEX_TEACHER_RESOURCE_PATH_PREFIX =
            "/api/vector-index/teacher-resources";
    private static final String AGENT_RUN_ACTION_PREFIX = "agent-run:";
    private static final String AGENT_EXECUTE_PATH = "/api/agents/execute";
    private static final String AGENT_WRITING_COURSEWARE_PATH = "/api/agents/writing/courseware";
    private static final String AGENT_WRITING_COURSEWARE_ASYNC_PATH = "/api/agents/writing/courseware/async";
    private static final Set<String> CAPABILITY_ALLOWED_ROLES = Set.of("student", "teacher", "admin");
    private static final Set<String> TEACHER_RESOURCE_ALLOWED_ROLES = Set.of("teacher", "admin");
    private static final Set<String> STUDENT_MEMORY_ALLOWED_ROLES = Set.of("student");
    private static final Set<String> STUDENT_DASHBOARD_REFRESH_ALLOWED_ROLES = Set.of("student", "teacher", "admin");

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
     * Creates a testable service with explicit audit sink.
     *
     * @param store token store
     * @param clock clock
     * @param auditSink audit sink
     */
    public CapabilityTokenService(CapabilityTokenStore store, Clock clock, CapabilityAuditSink auditSink) {
        this.store = Objects.requireNonNull(store, "store is required");
        this.clock = Objects.requireNonNull(clock, "clock is required");
        this.auditSink = Objects.requireNonNull(auditSink, "auditSink is required");
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
                && isTeachingHandoutPath(path, "/latex")) {
            validateTeachingSubject(subject);
            return;
        }
        if (TEACHING_HANDOUT_LATEX_PREVIEW_ACTION.equals(action)
                && isTeachingHandoutPath(path, "/latex/preview")) {
            validateTeachingSubject(subject);
            return;
        }
        if (TEACHING_HANDOUT_PDF_EXPORT_ACTION.equals(action)
                && isTeachingHandoutPath(path, "/pdf")) {
            validateTeachingSubject(subject);
            return;
        }
        if (TEACHING_HANDOUT_BATCH_ZIP_EXPORT_ACTION.equals(action)
                && TEACHING_BATCH_ZIP_PATH.equals(path)) {
            validateTeachingSubject(subject);
            return;
        }
        if (TEACHING_HANDOUT_BATCH_ZIP_DOWNLOAD_ACTION.equals(action)
                && isBatchZipDownloadPath(path)) {
            validateTeachingSubject(subject);
            return;
        }
        if (TEACHING_FEEDBACK_SUBMIT_ACTION.equals(action)
                && isTeachingFeedbackPath(path)) {
            validateTeachingSubject(subject);
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
        if (TEACHER_RESOURCE_SYNC_ACTION.equals(action) && isTeacherResourceSyncPath(path)) {
            validateTeacherResourceSubject(subject);
            return;
        }
        if (TEACHER_RESOURCE_SYNC_EXECUTE_ACTION.equals(action) && isTeacherResourceSyncExecutePath(path)) {
            validateTeacherResourceSubject(subject);
            return;
        }
        if (TEACHER_RESOURCE_SYNC_RESUME_ACTION.equals(action) && isTeacherResourceSyncResumePath(path)) {
            validateTeacherResourceSubject(subject);
            return;
        }
        if (STUDENT_MEMORY_REMEMBER_ACTION.equals(action) && STUDENT_MEMORY_REMEMBER_PATH.equals(path)) {
            validateStudentMemorySubject(subject);
            return;
        }
        if (STUDENT_DASHBOARD_REFRESH_ACTION.equals(action) && STUDENT_DASHBOARD_REFRESH_PATH.equals(path)) {
            validateStudentDashboardRefreshSubject(subject);
            return;
        }
        if (KNOWLEDGE_POINT_CREATE_ACTION.equals(action) && KNOWLEDGE_POINTS_PATH.equals(path)) {
            validateTeacherResourceSubject(subject);
            return;
        }
        if (QUESTION_BANK_CREATE_ACTION.equals(action) && QUESTION_BANK_ITEMS_PATH.equals(path)) {
            validateTeacherResourceSubject(subject);
            return;
        }
        if (QUESTION_BANK_IMPORT_TEACHER_RESOURCE_ACTION.equals(action)
                && isQuestionBankTeacherResourceImportPath(path)) {
            validateTeacherResourceSubject(subject);
            return;
        }
        if (VECTOR_INDEX_REBUILD_ACTION.equals(action) && isVectorIndexTeacherResourceRebuildPath(path)) {
            validateTeacherResourceSubject(subject);
            return;
        }
        if (action.startsWith(AGENT_RUN_ACTION_PREFIX) && AGENT_EXECUTE_PATH.equals(path)) {
            if (action.length() == AGENT_RUN_ACTION_PREFIX.length()) {
                throw new IllegalArgumentException("Unsupported capability action or path");
            }
            validateTeachingSubject(subject);
            return;
        }
        if (action.startsWith(AGENT_RUN_ACTION_PREFIX) && isAgentWritingCoursewarePath(path)) {
            if (action.length() == AGENT_RUN_ACTION_PREFIX.length()) {
                throw new IllegalArgumentException("Unsupported capability action or path");
            }
            validateTeacherResourceSubject(subject);
            return;
        }
        throw new IllegalArgumentException("Unsupported capability action or path");
    }

    /**
     * Allows only the protected multi-agent courseware writing entrypoints.
     */
    private static boolean isAgentWritingCoursewarePath(String path) {
        return AGENT_WRITING_COURSEWARE_PATH.equals(path) || AGENT_WRITING_COURSEWARE_ASYNC_PATH.equals(path);
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
     * Allows legacy handout paths and explicit teacher/student version paths for protected exports.
     */
    private static boolean isTeachingHandoutPath(String path, String suffix) {
        String[] parts = pathPartsAfterPrefix(path, TEACHING_TASKS_PATH);
        if (parts.length < 3 || !hasText(parts[0]) || !"handout".equals(parts[1])) {
            return false;
        }
        if ("/latex".equals(suffix)) {
            return (parts.length == 3 && "latex".equals(parts[2]))
                    || (parts.length == 4 && isHandoutVersion(parts[2]) && "latex".equals(parts[3]));
        }
        if ("/latex/preview".equals(suffix)) {
            return (parts.length == 4 && "latex".equals(parts[2]) && "preview".equals(parts[3]))
                    || (parts.length == 5
                    && isHandoutVersion(parts[2])
                    && "latex".equals(parts[3])
                    && "preview".equals(parts[4]));
        }
        if ("/pdf".equals(suffix)) {
            return (parts.length == 3 && "pdf".equals(parts[2]))
                    || (parts.length == 4 && isHandoutVersion(parts[2]) && "pdf".equals(parts[3]));
        }
        return false;
    }

    /**
     * Validates the exact feedback capability path shape.
     */
    private static boolean isTeachingFeedbackPath(String path) {
        String[] parts = pathPartsAfterPrefix(path, TEACHING_TASKS_PATH);
        return parts.length == 2 && hasText(parts[0]) && "feedback".equals(parts[1]);
    }

    /**
     * Validates the exact temporary ZIP download capability path shape.
     */
    private static boolean isBatchZipDownloadPath(String path) {
        String[] parts = pathPartsAfterPrefix(path, TEACHING_BATCH_ZIP_PATH);
        return parts.length == 2 && hasText(parts[0]) && "download".equals(parts[1]);
    }

    /**
     * Validates the exact source sync job path shape.
     */
    private static boolean isTeacherResourceSyncPath(String path) {
        String[] parts = pathPartsAfterPrefix(path, TEACHER_RESOURCES_PATH);
        return parts.length == 2 && hasText(parts[0]) && "sync-jobs".equals(parts[1]);
    }

    /**
     * Validates the exact source sync job execution path shape.
     */
    private static boolean isTeacherResourceSyncExecutePath(String path) {
        return isTeacherResourceSyncJobActionPath(path, "execute");
    }

    /**
     * Validates the exact source sync job resume path shape.
     */
    private static boolean isTeacherResourceSyncResumePath(String path) {
        return isTeacherResourceSyncJobActionPath(path, "resume");
    }

    /**
     * Validates a source sync job action path under a specific document/job pair.
     */
    private static boolean isTeacherResourceSyncJobActionPath(String path, String actionSegment) {
        String[] parts = pathPartsAfterPrefix(path, TEACHER_RESOURCES_PATH);
        return parts.length == 4
                && hasText(parts[0])
                && "sync-jobs".equals(parts[1])
                && hasText(parts[2])
                && actionSegment.equals(parts[3]);
    }

    /**
     * Validates importing parsed teacher resource blocks into the question bank.
     */
    private static boolean isQuestionBankTeacherResourceImportPath(String path) {
        String[] parts = pathPartsAfterPrefix(path, QUESTION_BANK_IMPORT_TEACHER_RESOURCE_PATH_PREFIX);
        return parts.length == 1 && hasText(parts[0]);
    }

    /**
     * Validates rebuilding one teacher resource vector index.
     */
    private static boolean isVectorIndexTeacherResourceRebuildPath(String path) {
        String[] parts = pathPartsAfterPrefix(path, VECTOR_INDEX_TEACHER_RESOURCE_PATH_PREFIX);
        return parts.length == 2 && hasText(parts[0]) && "rebuild".equals(parts[1]);
    }

    /**
     * Splits path segments after a fixed API prefix and rejects empty segments.
     */
    private static String[] pathPartsAfterPrefix(String path, String prefix) {
        String normalizedPrefix = prefix + "/";
        if (path == null || !path.startsWith(normalizedPrefix)) {
            return new String[0];
        }
        String tail = path.substring(normalizedPrefix.length());
        if (tail.isBlank()) {
            return new String[0];
        }
        String[] parts = tail.split("/", -1);
        for (String part : parts) {
            if (!hasText(part)) {
                return new String[0];
            }
        }
        return parts;
    }

    /**
     * Checks whether a path segment is a supported handout version.
     */
    private static boolean isHandoutVersion(String value) {
        return "teacher".equals(value) || "student".equals(value);
    }

    /**
     * Returns true when a path segment contains non-blank text.
     */
    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    /**
     * Validates subject for owned teaching task reads and exports.
     */
    private static void validateTeachingSubject(RequestSubject subject) {
        if (!CAPABILITY_ALLOWED_ROLES.contains(subject.subjectType()) || subject.subjectId() == null) {
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
     * Validates subject for persisted student dashboard snapshot refreshes.
     */
    private static void validateStudentDashboardRefreshSubject(RequestSubject subject) {
        if (!STUDENT_DASHBOARD_REFRESH_ALLOWED_ROLES.contains(subject.subjectType()) || subject.subjectId() == null) {
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
