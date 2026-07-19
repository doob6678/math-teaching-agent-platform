package com.doob.mathagent.retrieval;

/** One visible execution stage, so a UI never treats an internal English pipeline id as a success claim. */
public record TextbookRetrievalStage(String code, String label, String status, String description, long elapsedMs) {

    /** Retains existing call sites that predate per-stage timing. */
    public TextbookRetrievalStage(String code, String label, String status, String description) {
        this(code, label, status, description, -1L);
    }
}
