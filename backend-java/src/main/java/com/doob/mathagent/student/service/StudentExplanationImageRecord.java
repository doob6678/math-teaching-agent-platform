package com.doob.mathagent.student.service;

import java.nio.file.Path;
import java.time.Instant;

/**
 * Temporary image metadata bound to a backend-resolved owner.
 *
 * @param uploadId backend-issued image upload id
 * @param tenantId tenant that owns this image
 * @param subjectType owner subject type
 * @param subjectId owner subject id
 * @param originalFileName browser-provided file name after sanitization
 * @param contentType validated image content type
 * @param sizeBytes stored file size
 * @param localPath absolute local path under configured storage root
 * @param createdAt creation instant
 * @param expiresAt expiration instant
 */
public record StudentExplanationImageRecord(
        String uploadId,
        String tenantId,
        String subjectType,
        String subjectId,
        String originalFileName,
        String contentType,
        long sizeBytes,
        Path localPath,
        Instant createdAt,
        Instant expiresAt) {
}
