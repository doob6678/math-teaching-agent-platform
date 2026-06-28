package com.doob.mathagent.securityrisk.service;

import com.doob.mathagent.securityrisk.dto.CapabilityAuditQuery;
import com.doob.mathagent.securityrisk.vo.CapabilityAuditLogResponse;
import java.util.List;

/**
 * Read-side capability audit lookup for teacher/admin security review.
 */
public interface CapabilityAuditLookup {

    /**
     * Searches capability audit logs under the backend resolved tenant.
     *
     * @param query normalized or raw query conditions
     * @return matching audit rows without raw capability tokens
     */
    List<CapabilityAuditLogResponse> search(CapabilityAuditQuery query);
}
