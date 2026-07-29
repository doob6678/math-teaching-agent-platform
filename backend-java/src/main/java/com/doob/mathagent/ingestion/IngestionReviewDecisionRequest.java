package com.doob.mathagent.ingestion;

/** Human-only review input: a decision without a named reviewer and reason is not durable evidence. */
public record IngestionReviewDecisionRequest(int taskIndex, String reviewerId, String decision, String reason) { }
