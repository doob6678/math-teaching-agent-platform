package com.doob.mathagent.teacher.search.audit;

import java.util.Optional;

/**
 * Query port for recent teacher resource block search audit events.
 */
public interface TeacherResourceBlockSearchAuditLookup {

    /**
     * Looks up one recent teacher resource search audit event by query id.
     *
     * @param queryId server-generated query id
     * @return matching audit event when retained
     */
    Optional<TeacherResourceBlockSearchAuditEvent> findByQueryId(String queryId);
}

