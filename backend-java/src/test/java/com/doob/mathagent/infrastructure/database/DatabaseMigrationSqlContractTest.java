package com.doob.mathagent.infrastructure.database;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class DatabaseMigrationSqlContractTest {

    @Test
    void metadataAndRetrievalAuditMigrationContainsRequiredTablesAndIndexes() throws Exception {
        String migration = Files.readString(Path.of("src/main/resources/db/migration/V1__metadata_and_retrieval_audit.sql"));

        assertThat(migration)
                .contains("CREATE TABLE source_document")
                .contains("CREATE TABLE document_block")
                .contains("CREATE TABLE retrieval_query_log")
                .contains("CREATE TABLE retrieval_hit_log")
                .contains("CREATE TABLE capability_audit_log")
                .contains("CREATE TABLE security_audit_log")
                .contains("idx_source_document_tenant_type")
                .contains("idx_document_block_source_page")
                .contains("idx_retrieval_query_created_at")
                .contains("idx_retrieval_hit_query_rank")
                .contains("idx_capability_audit_subject")
                .contains("idx_capability_audit_action_decision")
                .contains("idx_security_audit_created_at");
    }

    @Test
    void retrievalHitAuditStoresEvidenceAndPageQuality() throws Exception {
        String migration = Files.readString(Path.of("src/main/resources/db/migration/V1__metadata_and_retrieval_audit.sql"));

        assertThat(migration)
                .contains("chunk_id")
                .contains("score")
                .contains("page_quality_label")
                .contains("evidence_json JSON")
                .contains("FOREIGN KEY (query_id) REFERENCES retrieval_query_log(query_id)");
    }

    @Test
    void capabilityAuditMigrationStoresLifecycleAndTokenHash() throws Exception {
        String migration = Files.readString(Path.of("src/main/resources/db/migration/V1__metadata_and_retrieval_audit.sql"));

        assertThat(migration)
                .contains("capability_audit_log")
                .contains("event_id")
                .contains("token_hash")
                .contains("decision")
                .contains("reason")
                .contains("request_hash")
                .contains("idempotency_key")
                .doesNotContain("token VARCHAR");
    }
}
