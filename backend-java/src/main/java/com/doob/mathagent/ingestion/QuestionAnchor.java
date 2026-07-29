package com.doob.mathagent.ingestion;

/** A printed top-level question-number line located in PDF user-space coordinates. */
record QuestionAnchor(String number, String line, int x, int y) {
    QuestionAnchor {
        if (number == null || number.isBlank() || line == null || x < 0 || y < 0) {
            throw new IllegalArgumentException("question anchor must contain non-negative coordinates and number text");
        }
    }
}
