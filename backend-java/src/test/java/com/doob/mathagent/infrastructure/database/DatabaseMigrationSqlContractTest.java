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

    @Test
    void agentTraceMigrationStoresExecutionMonitoringFieldsWithoutRawModelOutput() throws Exception {
        String migration = Files.readString(Path.of("src/main/resources/db/migration/V2__agent_run_trace.sql"));

        assertThat(migration)
                .contains("CREATE TABLE agent_run_trace")
                .contains("trace_id CHAR(36)")
                .contains("plan_id CHAR(36)")
                .contains("agent_code")
                .contains("allowed_tool_scopes_json JSON")
                .contains("allowed_data_scopes_json JSON")
                .contains("evidence_refs_json JSON")
                .contains("idx_agent_trace_subject")
                .contains("idx_agent_trace_agent_status")
                .doesNotContain("raw_prompt")
                .doesNotContain("model_output");
    }

    @Test
    void sourceSyncJobMigrationStoresDurableJobStatusAndDocumentReference() throws Exception {
        String migration = Files.readString(Path.of("src/main/resources/db/migration/V3__source_sync_job.sql"));

        assertThat(migration)
                .contains("CREATE TABLE source_sync_job")
                .contains("job_id CHAR(36)")
                .contains("source_document_id BIGINT NOT NULL")
                .contains("operation VARCHAR(64) NOT NULL")
                .contains("status VARCHAR(32) NOT NULL")
                .contains("phase VARCHAR(64) NOT NULL")
                .contains("attempt INT NOT NULL DEFAULT 0")
                .contains("idx_source_sync_job_tenant_document")
                .contains("idx_source_sync_job_status")
                .contains("FOREIGN KEY (source_document_id) REFERENCES source_document(id)");
    }

    @Test
    void sourceSyncCheckpointMigrationStoresResumeCursorAndItemSets() throws Exception {
        String migration = Files.readString(Path.of("src/main/resources/db/migration/V4__source_sync_checkpoint.sql"));

        assertThat(migration)
                .contains("CREATE TABLE source_sync_checkpoint")
                .contains("job_id CHAR(36) NOT NULL")
                .contains("root_token VARCHAR(128)")
                .contains("current_folder_token VARCHAR(128)")
                .contains("page_token VARCHAR(256)")
                .contains("visited_folder_tokens_json JSON")
                .contains("downloaded_items_json JSON")
                .contains("failed_items_json JSON")
                .contains("cursor_version INT NOT NULL DEFAULT 1")
                .contains("uk_source_sync_checkpoint_tenant_job")
                .contains("idx_source_sync_checkpoint_document")
                .contains("FOREIGN KEY (job_id) REFERENCES source_sync_job(job_id)")
                .contains("FOREIGN KEY (source_document_id) REFERENCES source_document(id)");
    }

    @Test
    void studentLearningSnapshotMigrationStoresProgressGraphAndAuditScope() throws Exception {
        String migration = Files.readString(Path.of("src/main/resources/db/migration/V5__student_learning_snapshot.sql"));

        assertThat(migration)
                .contains("CREATE TABLE student_learning_snapshot")
                .contains("tenant_id VARCHAR(64) NOT NULL")
                .contains("student_id VARCHAR(128) NOT NULL")
                .contains("knowledge_progress_json JSON NOT NULL")
                .contains("knowledge_graph_json JSON NOT NULL")
                .contains("weak_points_json JSON NOT NULL")
                .contains("score_trend_json JSON NOT NULL")
                .contains("resource_scopes_json JSON NOT NULL")
                .contains("idx_student_snapshot_tenant_student")
                .contains("idx_student_snapshot_updated_at");
    }

    @Test
    void knowledgeAndQuestionBankMigrationStoresGraphQuestionAndLinkTables() throws Exception {
        String migration = Files.readString(Path.of("src/main/resources/db/migration/V6__knowledge_question_bank.sql"));

        assertThat(migration)
                .contains("CREATE TABLE knowledge_point")
                .contains("CREATE TABLE knowledge_relation")
                .contains("CREATE TABLE question_bank_item")
                .contains("CREATE TABLE question_knowledge_link")
                .contains("tenant_id VARCHAR(64) NOT NULL")
                .contains("permission_scope VARCHAR(128) NOT NULL")
                .contains("owner_subject_id VARCHAR(128) NULL")
                .contains("question_text LONGTEXT NOT NULL")
                .contains("answer_json JSON NULL")
                .contains("source_document_id BIGINT NULL")
                .contains("idx_knowledge_point_tenant_status")
                .contains("idx_question_bank_tenant_scope")
                .contains("idx_question_knowledge_link_point")
                .contains("FOREIGN KEY (source_document_id) REFERENCES source_document(id)");
    }

    @Test
    void questionBankTeacherResourceSourceMigrationStoresImportDeduplicationFields() throws Exception {
        String migration = Files.readString(Path.of("src/main/resources/db/migration/V7__question_bank_teacher_resource_source.sql"));

        assertThat(migration)
                .contains("ALTER TABLE question_bank_item")
                .contains("source_resource_document_id VARCHAR(128)")
                .contains("source_block_id VARCHAR(128)")
                .contains("source_checksum CHAR(64)")
                .contains("idx_question_bank_source_block");
    }
}
