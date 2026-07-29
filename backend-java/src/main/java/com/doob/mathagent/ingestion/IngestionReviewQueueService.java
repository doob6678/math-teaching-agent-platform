package com.doob.mathagent.ingestion;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Exposes the evidence-backed review queue and appends human decisions to the immutable ingestion audit table.
 * It deliberately does not update publication state: accepting a review task is not equivalent to publishing a
 * canonical question, which still requires every verification and answer gate.
 */
@Service
public final class IngestionReviewQueueService {
    private static final String REVIEW_QUEUE_FILE = "2024/2024-review-queue.json";
    private static final List<String> ALLOWED_DECISIONS = List.of("ACCEPT", "REJECT", "DEFER");
    private final DataSource dataSource;
    private final ObjectMapper objectMapper;
    private final IngestionProperties properties;

    public IngestionReviewQueueService(DataSource dataSource, ObjectMapper objectMapper, IngestionProperties properties) {
        this.dataSource = dataSource;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    /** Reads the generated queue from the configured Docker evidence mount, never from a caller-provided path. */
    public Map<String, Object> queue() throws IOException {
        Path evidenceFile = Path.of(properties.getEvidenceRoot()).resolve(REVIEW_QUEUE_FILE);
        if (!Files.isRegularFile(evidenceFile)) {
            throw new IllegalStateException("review queue evidence has not been generated: " + evidenceFile);
        }
        return objectMapper.readValue(Files.readString(evidenceFile), new TypeReference<>() { });
    }

    /** Appends a human decision and returns a redacted acknowledgement suitable for the browser. */
    public Map<String, Object> recordDecision(IngestionReviewDecisionRequest request) throws Exception {
        validate(request);
        Map<String, Object> queue = queue();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> tasks = (List<Map<String, Object>>) queue.get("tasks");
        if (request.taskIndex() < 0 || request.taskIndex() >= tasks.size()) {
            throw new IllegalArgumentException("taskIndex does not identify a queue task");
        }
        Map<String, Object> task = tasks.get(request.taskIndex());
        Map<String, Object> decision = new LinkedHashMap<>();
        decision.put("taskIndex", request.taskIndex());
        decision.put("task", task);
        decision.put("humanDecision", request.decision().strip());
        decision.put("reason", request.reason().strip());
        String runId = String.valueOf(queue.get("importRunId"));
        try (var connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("INSERT INTO question_ingestion_audit (audit_id,import_run_id,action_type,actor_type,actor_id,decision_json) VALUES (?,?,?,?,?,?)")) {
            statement.setString(1, UUID.randomUUID().toString());
            statement.setString(2, runId);
            statement.setString(3, "HUMAN_REVIEW_" + request.decision().strip());
            statement.setString(4, "HUMAN");
            statement.setString(5, request.reviewerId().strip());
            statement.setString(6, objectMapper.writeValueAsString(decision));
            statement.executeUpdate();
        }
        return Map.of("recorded", true, "taskIndex", request.taskIndex(), "decision", request.decision().strip(), "publicationChanged", false);
    }

    private static void validate(IngestionReviewDecisionRequest request) {
        if (request == null || request.reviewerId() == null || request.reviewerId().isBlank() || request.reason() == null || request.reason().isBlank()) {
            throw new IllegalArgumentException("reviewerId and reason are required for a human review decision");
        }
        if (request.decision() == null || !ALLOWED_DECISIONS.contains(request.decision().strip())) {
            throw new IllegalArgumentException("decision must be one of " + ALLOWED_DECISIONS);
        }
    }
}
