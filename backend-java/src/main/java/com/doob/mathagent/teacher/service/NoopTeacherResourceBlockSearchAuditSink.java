package com.doob.mathagent.teacher.service;

/**
 * No-op audit sink used by focused unit tests that do not inspect search audit events.
 */
public final class NoopTeacherResourceBlockSearchAuditSink implements TeacherResourceBlockSearchAuditSink {

    public static final NoopTeacherResourceBlockSearchAuditSink INSTANCE =
            new NoopTeacherResourceBlockSearchAuditSink();

    private NoopTeacherResourceBlockSearchAuditSink() {
    }

    /**
     * Intentionally ignores the event.
     */
    @Override
    public void record(TeacherResourceBlockSearchAuditEvent event) {
        // No-op for tests and compatibility construction paths.
    }
}
