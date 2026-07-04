package com.doob.mathagent.student.vo;

import java.time.Instant;

/**
 * Response returned after storing a temporary student explanation image.
 *
 * @param uploadId backend-issued temporary image id
 * @param originalFileName browser-provided original file name after stripping path fragments
 * @param contentType validated image content type
 * @param sizeBytes stored file size in bytes
 * @param expiresAt instant after which the image cannot be used by explanation requests
 * @param imageStatus explicit status; upload does not mean OCR has run
 */
public record StudentExplanationImageUploadResponse(
        String uploadId,
        String originalFileName,
        String contentType,
        long sizeBytes,
        Instant expiresAt,
        String imageStatus) {
}
