package com.doob.mathagent.ingestion;

/** Auditable deduplication result including a human-readable reason. */
public record DeduplicationDecision(DeduplicationAction action, String reason) { }
