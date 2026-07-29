package com.doob.mathagent.ingestion;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Defines the deterministic auto-approval boundary for text/formula ingestion.
 *
 * <p>PDF text layers are reliable enough to preserve a non-empty question statement, but they do not prove image
 * ownership, page continuation, or an answer.  The batch therefore approves only the text/formula record for storage
 * and retrieval.  It deliberately does not mark an answer as approved or a question as publicly published.</p>
 */
public record TextFormulaCandidateApproval(String normalizedText, String fingerprint) {
    /** Status used for deterministic text/formula records that are available to the internal retrieval experiment. */
    public static final String STORAGE_APPROVED_STATUS = "AUTO_APPROVED_TEXT_FORMULA";

    /**
     * Normalizes extraction-only whitespace and creates a source-scoped fingerprint.
     * Source scope is intentional: blank and solution copies must remain independently auditable until pairing occurs.
     */
    public static TextFormulaCandidateApproval approve(String sourceHash, int pageNumber, String questionNumber, String extractedText) {
        String normalized = extractedText == null ? "" : extractedText.replaceAll("\\s+", " ").strip();
        if (normalized.isBlank()) {
            throw new IllegalArgumentException("Text/formula auto-approval requires non-empty extracted text");
        }
        return new TextFormulaCandidateApproval(normalized,
                sha256(sourceHash + "|" + pageNumber + "|" + questionNumber + "|" + normalized));
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 must be available in the Java runtime", exception);
        }
    }
}
