package com.doob.mathagent.retrieval;

import com.doob.mathagent.infrastructure.database.DatabaseMigrationProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;

/**
 * MySQL 检索审计写入实现。
 *
 * <p>字段含义与 V1__metadata_and_retrieval_audit.sql 对齐：先写 retrieval_query_log，
 * 再按 rank 写 retrieval_hit_log，确保一次检索可完整追踪查询和证据来源。
 */
public class JdbcRetrievalAuditSink implements RetrievalAuditSink {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final String INSERT_QUERY = """
            INSERT INTO retrieval_query_log (
                query_id, tenant_id, subject_type, subject_id, query_text,
                retrieval_strategy, requested_limit, hit_count, elapsed_ms, request_context_json
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;
    private static final String INSERT_HIT = """
            INSERT INTO retrieval_hit_log (
                query_id, rank_no, chunk_id, doc_id, book_name, page_no, printed_page_no,
                score, retrieval_strategy, page_quality_label, source_page_image, evidence_json
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

    private final DatabaseMigrationProperties properties;

    public JdbcRetrievalAuditSink(DatabaseMigrationProperties properties) {
        this.properties = properties;
    }

    @Override
    public void record(RetrievalAuditEvent event) {
        if (!properties.enabled()) {
            return;
        }
        try (Connection connection = DriverManager.getConnection(
                properties.url(),
                properties.username(),
                properties.safePassword())) {
            boolean previousAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                insertQuery(connection, event);
                insertHits(connection, event);
                connection.commit();
            } catch (SQLException | RuntimeException e) {
                connection.rollback();
                throw e;
            } finally {
                connection.setAutoCommit(previousAutoCommit);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to write retrieval audit log", e);
        }
    }

    private static void insertQuery(Connection connection, RetrievalAuditEvent event) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(INSERT_QUERY)) {
            statement.setString(1, event.queryId());
            statement.setString(2, event.tenantId());
            setNullableString(statement, 3, event.subjectType());
            setNullableString(statement, 4, event.subjectId());
            statement.setString(5, event.queryText());
            statement.setString(6, event.retrievalStrategy());
            statement.setInt(7, event.requestedLimit());
            statement.setInt(8, event.hitCount());
            statement.setInt(9, event.elapsedMs());
            statement.setString(10, toJson(event.requestContext().toAuditMap()));
            statement.executeUpdate();
        }
    }

    private static void insertHits(Connection connection, RetrievalAuditEvent event) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(INSERT_HIT)) {
            for (RetrievalAuditHit hit : event.hits()) {
                statement.setString(1, event.queryId());
                statement.setInt(2, hit.rankNo());
                statement.setString(3, hit.chunkId());
                statement.setString(4, hit.docId());
                setNullableString(statement, 5, hit.bookName());
                if (hit.pageNo() == null) {
                    statement.setNull(6, Types.INTEGER);
                } else {
                    statement.setInt(6, hit.pageNo());
                }
                setNullableString(statement, 7, hit.printedPageNo());
                statement.setDouble(8, hit.score());
                statement.setString(9, hit.retrievalStrategy());
                setNullableString(statement, 10, hit.pageQualityLabel());
                setNullableString(statement, 11, hit.sourcePageImage());
                statement.setString(12, toJson(hit.evidenceJson()));
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private static void setNullableString(PreparedStatement statement, int index, String value) throws SQLException {
        if (value == null || value.isBlank()) {
            statement.setNull(index, Types.VARCHAR);
        } else {
            statement.setString(index, value);
        }
    }

    private static String toJson(Object value) {
        try {
            return OBJECT_MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize retrieval audit JSON", e);
        }
    }
}
