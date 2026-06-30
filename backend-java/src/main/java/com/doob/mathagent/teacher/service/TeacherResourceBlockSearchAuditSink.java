package com.doob.mathagent.teacher.service;

/**
 * Sink for teacher resource block search audit events.
 */
public interface TeacherResourceBlockSearchAuditSink {

    /**
     * Records one teacher resource block search audit event.
     *
     * @param event audit event to retain
     */
    void record(TeacherResourceBlockSearchAuditEvent event);
}
