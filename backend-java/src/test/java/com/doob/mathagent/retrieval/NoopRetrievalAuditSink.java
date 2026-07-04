package com.doob.mathagent.retrieval;

/**
 * Test-only retrieval audit sink for unit tests that assert search behavior without MySQL.
 */
public final class NoopRetrievalAuditSink implements RetrievalAuditSink {

    @Override
    public void record(RetrievalAuditEvent event) {
        // Test fixture intentionally records nothing.
    }
}
