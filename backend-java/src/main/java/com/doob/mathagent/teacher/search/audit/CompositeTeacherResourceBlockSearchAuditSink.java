package com.doob.mathagent.teacher.search.audit;

import java.util.List;

/**
 * Composite writer for teacher resource search audits.
 */
public class CompositeTeacherResourceBlockSearchAuditSink implements TeacherResourceBlockSearchAuditSink {

    private final List<TeacherResourceBlockSearchAuditSink> delegates;

    /**
     * Creates a composite sink with ordered delegates.
     *
     * @param delegates sink delegates; each delegate is called once
     */
    public CompositeTeacherResourceBlockSearchAuditSink(List<TeacherResourceBlockSearchAuditSink> delegates) {
        this.delegates = List.copyOf(delegates);
    }

    /**
     * Writes one audit event to every configured delegate.
     *
     * @param event audit event to retain
     */
    @Override
    public void record(TeacherResourceBlockSearchAuditEvent event) {
        for (TeacherResourceBlockSearchAuditSink delegate : delegates) {
            delegate.record(event);
        }
    }
}

