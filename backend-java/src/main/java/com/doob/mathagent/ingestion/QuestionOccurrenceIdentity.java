package com.doob.mathagent.ingestion;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Builds the immutable identity of one detected source occurrence. Text content is intentionally excluded: OCR may
 * improve on recovery, while the physical source location must remain the same audit object.
 */
public final class QuestionOccurrenceIdentity {
    private static final String FIELD_SEPARATOR = "\u001f";

    private QuestionOccurrenceIdentity() { }

    /**
     * Fingerprints the source version, page range, exact region and raw printed question number.
     *
     * @return lower-case SHA-256 fingerprint suitable for a fixed-width database key
     */
    public static String fingerprint(
            String sourceFileHash,
            int pageStart,
            int pageEnd,
            QuestionRegion region,
            String originalQuestionNumber) {
        if (sourceFileHash == null || sourceFileHash.isBlank()) {
            throw new IllegalArgumentException("source file hash is required");
        }
        if (pageStart < 1 || pageEnd < pageStart) {
            throw new IllegalArgumentException("page range must be positive and ordered");
        }
        if (region == null) {
            throw new IllegalArgumentException("question region is required");
        }
        String material = sourceFileHash.strip() + FIELD_SEPARATOR + pageStart + FIELD_SEPARATOR + pageEnd
                + FIELD_SEPARATOR + region.canonicalForm() + FIELD_SEPARATOR
                + (originalQuestionNumber == null ? "" : originalQuestionNumber.strip());
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(material.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("JVM does not provide SHA-256", exception);
        }
    }
}
