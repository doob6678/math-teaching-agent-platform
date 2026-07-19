package com.doob.mathagent.retrieval;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
public class RetrievalAuditController {

    private final RetrievalAuditLookup auditLookup;

    public RetrievalAuditController(RetrievalAuditLookup auditLookup) {
        this.auditLookup = auditLookup;
    }

    @GetMapping("/api/retrieval/audit/{queryId}")
    public RetrievalAuditEvent detail(@PathVariable String queryId) {
        return auditLookup.findByQueryId(queryId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "Retrieval audit queryId not found: " + queryId));
    }
}
