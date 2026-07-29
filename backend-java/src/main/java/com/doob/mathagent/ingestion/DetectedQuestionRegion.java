package com.doob.mathagent.ingestion;

/** A conservative visual region inferred from a real PDF text-layer question anchor. */
public record DetectedQuestionRegion(int pageNumber, String questionNumber, QuestionRegion region, String anchorLine, String layout) { }
