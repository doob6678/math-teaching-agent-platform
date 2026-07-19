package com.doob.mathagent.infrastructure.database;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
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

    @Test
    void knowledgeGraphDisplaySpineSeedsReviewedMainGraphWithoutOcrFragments() throws Exception {
        String migration = Files.readString(Path.of("src/main/resources/db/migration/V8__knowledge_graph_display_spine_v01.sql"));
        String source = Files.readString(
                Path.of("src/main/resources/knowledge/graph-spine-v0.1.md"),
                StandardCharsets.UTF_8);

        assertThat(source)
                .contains("\u9ad8\u4e2d\u6570\u5b66\u77e5\u8bc6\u56fe\u8c31\u4e3b\u5e72 v0.1")
                .contains("\u51fd\u6570\u57fa\u7840 -> \u5bfc\u6570\u7814\u7a76\u51fd\u6570")
                .contains("\u7b2c\u4e00\u7248\u89c4\u6a21\u63a7\u5236")
                .contains("page\u3001formula\u3001topic");
        assertThat(migration)
                .contains("KnowledgeGraphSpineSeedService")
                .contains("knowledge/graph-spine-v0.1.md")
                .contains("deterministic IDs")
                .doesNotContain("INSERT INTO knowledge_point")
                .doesNotContain("INSERT INTO knowledge_relation")
                .doesNotContain("page_")
                .doesNotContain("formula")
                .doesNotContain("OCR topic");
    }

    @Test
    void teacherResourceSearchAuditMigrationStoresQueryAndRankedHitsWithoutSecretsOrPaths() throws Exception {
        String migration = Files.readString(Path.of("src/main/resources/db/migration/V9__teacher_resource_search_audit.sql"));

        assertThat(migration)
                .contains("CREATE TABLE teacher_resource_search_audit_log")
                .contains("CREATE TABLE teacher_resource_search_audit_hit")
                .contains("query_id CHAR(36) NOT NULL")
                .contains("endpoint VARCHAR(255) NOT NULL")
                .contains("idx_teacher_resource_search_subject")
                .contains("idx_teacher_resource_search_hit_query_rank")
                .contains("FOREIGN KEY (query_id) REFERENCES teacher_resource_search_audit_log(query_id)")
                .contains("ON DELETE CASCADE")
                .doesNotContain("local_path")
                .doesNotContain("token")
                .doesNotContain("secret")
                .doesNotContain("raw_text");
    }

    @Test
    void authAccountMigrationStoresDeployableLoginAccountsWithoutPlaintextPasswords() throws Exception {
        String migration = Files.readString(Path.of("src/main/resources/db/migration/V11__auth_account.sql"));

        assertThat(migration)
                .contains("CREATE TABLE auth_account")
                .contains("account_id CHAR(36) NOT NULL")
                .contains("user_id VARCHAR(128) NOT NULL")
                .contains("tenant_id VARCHAR(64) NOT NULL")
                .contains("username_normalized VARCHAR(128) NOT NULL")
                .contains("password_hash VARCHAR(512) NOT NULL")
                .contains("role VARCHAR(32) NOT NULL")
                .contains("status VARCHAR(32) NOT NULL")
                .contains("uk_auth_account_username_normalized")
                .contains("idx_auth_account_tenant_role")
                .doesNotContain("password VARCHAR")
                .doesNotContain("plain_password")
                .doesNotContain("secret");
    }

    @Test
    void teacherDocumentBlockTwoStageMigrationAddsStableRerankAndGraphFields() throws Exception {
        String migration = Files.readString(Path.of("src/main/resources/db/migration/V16__teacher_document_block_two_stage_rag_fields.sql"));

        assertThat(migration)
                .contains("ALTER TABLE document_block")
                .contains("ADD COLUMN source_path")
                .contains("ADD COLUMN block_role")
                .contains("ADD COLUMN graph_node_ids_json JSON")
                .contains("ADD COLUMN graph_tag_names_json JSON")
                .contains("idx_document_block_doc_role_order")
                .contains("idx_document_block_doc_source_path");
    }

    @Test
    void studentExplanationHistoryMigrationStoresDurableConversationAndMessagePayloads() throws Exception {
        String migration = Files.readString(Path.of("src/main/resources/db/migration/V12__student_explanation_history.sql"));

        assertThat(migration)
                .contains("CREATE TABLE student_explanation_session")
                .contains("CREATE TABLE student_explanation_message")
                .contains("conversation_id CHAR(36) NOT NULL")
                .contains("request_json JSON NOT NULL")
                .contains("image_understanding_json JSON NOT NULL")
                .contains("ai_draft_json JSON NOT NULL")
                .contains("workflow_stages_json JSON NOT NULL")
                .contains("cards_json JSON NOT NULL")
                .contains("sources_json JSON NOT NULL")
                .contains("idx_student_explanation_message_conversation")
                .contains("idx_student_explanation_message_owner")
                .contains("FOREIGN KEY (conversation_id) REFERENCES student_explanation_session(conversation_id)");
    }

    /**
     * Counts non-overlapping text occurrences.
     */
    private static int countOccurrences(String value, String needle) {
        int count = 0;
        int index = 0;
        while ((index = value.indexOf(needle, index)) >= 0) {
            count++;
            index += needle.length();
        }
        return count;
    }
}
