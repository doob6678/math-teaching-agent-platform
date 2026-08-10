package com.doob.mathagent.teacher.sync.mq;

/**
 * Versioned, non-secret command sent after the HTTP layer has authorized the authenticated user's source-sync action.
 *
 * <p>The consumer receives only stable identifiers and the backend-resolved identity required by domain checks.</p>
 */
public record TeacherSourceSyncCommand(
        int schemaVersion,
        String action,
        String tenantId,
        String subjectRole,
        String subjectId,
        String documentId,
        String jobId) {

    /** Current message contract version; consumers reject unrecognized versions rather than guessing semantics. */
    public static final int CURRENT_SCHEMA_VERSION = 1;
    /** Execute a queued job from the beginning. */
    public static final String EXECUTE = "execute";
    /** Resume a paused Feishu job from its durable checkpoint. */
    public static final String RESUME = "resume";
}
