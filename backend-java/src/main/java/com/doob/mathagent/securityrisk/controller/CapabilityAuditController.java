package com.doob.mathagent.securityrisk.controller;

import com.doob.mathagent.infrastructure.security.RequestSubject;
import com.doob.mathagent.infrastructure.security.RequestSubjectResolver;
import com.doob.mathagent.securityrisk.dto.CapabilityAuditQuery;
import com.doob.mathagent.securityrisk.service.CapabilityAuditLookup;
import com.doob.mathagent.securityrisk.vo.CapabilityAuditLogResponse;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Capability audit query API for teacher/admin security review.
 */
@RestController
public class CapabilityAuditController {

    private static final Set<String> REVIEWER_ROLES = Set.of("teacher", "admin");

    private final CapabilityAuditLookup auditLookup;
    private final RequestSubjectResolver subjectResolver;

    /**
     * Creates a capability audit controller.
     *
     * @param auditLookup audit lookup service
     * @param subjectResolver backend subject resolver
     */
    public CapabilityAuditController(
            CapabilityAuditLookup auditLookup,
            RequestSubjectResolver subjectResolver) {
        this.auditLookup = auditLookup;
        this.subjectResolver = subjectResolver;
    }

    /**
     * Lists capability audit rows scoped to the reviewer's backend tenant.
     *
     * @param subjectType optional audited subject role filter
     * @param subjectId optional audited subject id filter
     * @param action optional capability action filter
     * @param decision optional lifecycle decision filter
     * @param limit maximum rows returned
     * @param httpRequest HTTP request used only for backend subject resolution
     * @return capability audit rows without raw capability tokens
     */
    @GetMapping("/api/security/capability-audits")
    public List<CapabilityAuditLogResponse> list(
            @RequestParam(required = false) String subjectType,
            @RequestParam(required = false) String subjectId,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String decision,
            @RequestParam(defaultValue = "50") int limit,
            HttpServletRequest httpRequest) {
        RequestSubject reviewer = subjectResolver.resolve(httpRequest).normalize();
        if (!REVIEWER_ROLES.contains(reviewer.subjectType())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Capability audit requires teacher or admin");
        }
        return auditLookup.search(new CapabilityAuditQuery(
                reviewer.tenantId(),
                subjectType,
                subjectId,
                action,
                decision,
                limit));
    }
}
