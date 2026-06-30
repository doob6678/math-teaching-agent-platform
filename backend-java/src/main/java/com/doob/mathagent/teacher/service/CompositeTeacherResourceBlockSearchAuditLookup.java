package com.doob.mathagent.teacher.service;

import java.util.List;
import java.util.Optional;

/**
 * Ordered lookup for teacher resource search audits.
 */
public class CompositeTeacherResourceBlockSearchAuditLookup implements TeacherResourceBlockSearchAuditLookup {

    private final List<TeacherResourceBlockSearchAuditLookup> delegates;

    /**
     * Creates an ordered lookup chain.
     *
     * @param delegates lookup delegates; the first match is returned
     */
    public CompositeTeacherResourceBlockSearchAuditLookup(List<TeacherResourceBlockSearchAuditLookup> delegates) {
        this.delegates = List.copyOf(delegates);
    }

    /**
     * Looks up one audit event by query id.
     *
     * @param queryId server-generated search query id
     * @return first matching audit event
     */
    @Override
    public Optional<TeacherResourceBlockSearchAuditEvent> findByQueryId(String queryId) {
        for (TeacherResourceBlockSearchAuditLookup delegate : delegates) {
            Optional<TeacherResourceBlockSearchAuditEvent> event = delegate.findByQueryId(queryId);
            if (event.isPresent()) {
                return event;
            }
        }
        return Optional.empty();
    }
}
