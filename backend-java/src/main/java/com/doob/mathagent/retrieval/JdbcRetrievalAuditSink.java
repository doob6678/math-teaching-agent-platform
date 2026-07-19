package com.doob.mathagent.retrieval;

import com.doob.mathagent.infrastructure.database.DatabaseMigrationProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * MySQL 检索审计写入实现。
 *
 * <p>字段含义与 V1__metadata_and_retrieval_audit.sql 对齐：先写 retrieval_query_log，
 * 再按 rank 写 retrieval_hit_log，确保一次检索可完整追踪查询和证据来源。
 */
public class JdbcRetrievalAuditSink implements RetrievalAuditSink, RetrievalAuditLookup {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final int MAX_RETRIEVAL_STRATEGY_LENGTH = 64;
    private static final TypeReference<Map<String, String>> STRING_MAP_TYPE = new TypeReference<>() {
    };
    private static final TypeReference<Map<String, Object>> OBJECT_MAP_TYPE = new TypeReference<>() {
    };
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
    private static final String SELECT_QUERY = """
            SELECT query_id, tenant_id, subject_type, subject_id, query_text,
                   retrieval_strategy, requested_limit, hit_count, elapsed_ms, request_context_json
            FROM retrieval_query_log
            WHERE query_id = ?
            """;
    private static final String SELECT_HITS = """
            SELECT rank_no, chunk_id, doc_id, book_name, page_no, printed_page_no,
                   score, retrieval_strategy, page_quality_label, source_page_image, evidence_json
            FROM retrieval_hit_log
            WHERE query_id = ?
            ORDER BY rank_no ASC
            """;

    private final DatabaseMigrationProperties properties;

    public JdbcRetrievalAuditSink(DatabaseMigrationProperties properties) {
        properties.validate();
        this.properties = properties;
    }

    @Override
    public void record(RetrievalAuditEvent event) {
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

    @Override
    public Optional<RetrievalAuditEvent> findByQueryId(String queryId) {
        if (queryId == null || queryId.isBlank()) {
            return Optional.empty();
        }
        try (Connection connection = DriverManager.getConnection(
                properties.url(),
                properties.username(),
                properties.safePassword())) {
            RetrievalAuditEvent query = selectQuery(connection, queryId.strip()).orElse(null);
            if (query == null) {
                return Optional.empty();
            }
            List<RetrievalAuditHit> hits = selectHits(connection, query.queryId());
            return Optional.of(new RetrievalAuditEvent(
                    query.queryId(),
                    query.tenantId(),
                    query.subjectType(),
                    query.subjectId(),
                    query.queryText(),
                    query.retrievalStrategy(),
                    query.requestedLimit(),
                    query.hitCount(),
                    query.elapsedMs(),
                    query.requestContext(),
                    hits));
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to read retrieval audit log", e);
        }
    }

    private static void insertQuery(Connection connection, RetrievalAuditEvent event) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(INSERT_QUERY)) {
            statement.setString(1, event.queryId());
            statement.setString(2, event.tenantId());
            setNullableString(statement, 3, event.subjectType());
            setNullableString(statement, 4, event.subjectId());
            statement.setString(5, event.queryText());
            statement.setString(6, safeRetrievalStrategy(event.retrievalStrategy()));
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
                statement.setString(9, safeRetrievalStrategy(hit.retrievalStrategy()));
                setNullableString(statement, 10, hit.pageQualityLabel());
                setNullableString(statement, 11, hit.sourcePageImage());
                statement.setString(12, toJson(hit.evidenceJson()));
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private static Optional<RetrievalAuditEvent> selectQuery(Connection connection, String queryId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(SELECT_QUERY)) {
            statement.setString(1, queryId);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return Optional.empty();
                }
                RetrievalRequestContext context = requestContext(resultSet.getString("request_context_json"));
                return Optional.of(new RetrievalAuditEvent(
                        resultSet.getString("query_id"),
                        resultSet.getString("tenant_id"),
                        resultSet.getString("subject_type"),
                        resultSet.getString("subject_id"),
                        resultSet.getString("query_text"),
                        resultSet.getString("retrieval_strategy"),
                        resultSet.getInt("requested_limit"),
                        resultSet.getInt("hit_count"),
                        resultSet.getInt("elapsed_ms"),
                        context,
                        List.of()));
            }
        }
    }

    private static List<RetrievalAuditHit> selectHits(Connection connection, String queryId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(SELECT_HITS)) {
            statement.setString(1, queryId);
            try (ResultSet resultSet = statement.executeQuery()) {
                List<RetrievalAuditHit> hits = new java.util.ArrayList<>();
                while (resultSet.next()) {
                    Map<String, Object> evidence = objectMap(resultSet.getString("evidence_json"));
                    hits.add(new RetrievalAuditHit(
                            resultSet.getInt("rank_no"),
                            resultSet.getString("chunk_id"),
                            resultSet.getString("doc_id"),
                            resultSet.getString("book_name"),
                            nullableInteger(resultSet, "page_no"),
                            resultSet.getString("printed_page_no"),
                            resultSet.getDouble("score"),
                            resultSet.getString("retrieval_strategy"),
                            resultSet.getString("page_quality_label"),
                            resultSet.getString("source_page_image"),
                            stringValue(evidence.get("volume")),
                            stringList(evidence.get("chapter_path")),
                            stringValue(evidence.get("section_title")),
                            stringValue(evidence.get("text_snippet")),
                            stringValue(evidence.get("formula_text"))));
                }
                return List.copyOf(hits);
            }
        }
    }

    private static void setNullableString(PreparedStatement statement, int index, String value) throws SQLException {
        if (value == null || value.isBlank()) {
            statement.setNull(index, Types.VARCHAR);
        } else {
            statement.setString(index, value);
        }
    }

    /**
     * Retrieval strategy is a compact diagnostic label persisted into VARCHAR(64).
     *
     * <p>Do not let evolving pipeline version names break production search. Audit should record a short readable mode
     * string, not the full internal implementation detail.</p>
     */
    private static String safeRetrievalStrategy(String value) {
        String normalized = value == null ? "" : value.strip();
        if (normalized.length() <= MAX_RETRIEVAL_STRATEGY_LENGTH) {
            return normalized;
        }
        return normalized.substring(0, MAX_RETRIEVAL_STRATEGY_LENGTH);
    }

    private static String toJson(Object value) {
        try {
            return OBJECT_MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize retrieval audit JSON", e);
        }
    }

    private static RetrievalRequestContext requestContext(String json) {
        Map<String, String> values = stringMap(json);
        return new RetrievalRequestContext(
                values.get("tenant_id"),
                values.get("subject_type"),
                values.get("subject_id"),
                values.get("ip"),
                values.get("device_id"),
                values.get("user_agent"),
                values.get("endpoint")).normalize();
    }

    private static Map<String, String> stringMap(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return OBJECT_MAPPER.readValue(json, STRING_MAP_TYPE);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to parse retrieval audit request context JSON", e);
        }
    }

    private static Map<String, Object> objectMap(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return OBJECT_MAPPER.readValue(json, OBJECT_MAP_TYPE);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to parse retrieval audit evidence JSON", e);
        }
    }

    private static Integer nullableInteger(ResultSet resultSet, String column) throws SQLException {
        int value = resultSet.getInt(column);
        return resultSet.wasNull() ? null : value;
    }

    private static String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static List<String> stringList(Object value) {
        if (!(value instanceof List<?> rawList)) {
            return List.of();
        }
        return rawList.stream().map(String::valueOf).toList();
    }
}
