package com.doob.mathagent.ingestion;

/** Answer payload and review facts used by the publication boundary. */
public record AnswerEvidence(String answer, AnswerProvenance provenance, boolean humanApproved, String model) { }
