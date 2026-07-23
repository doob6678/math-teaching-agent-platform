package com.doob.mathagent.teaching.service;

import java.nio.file.Path;
import java.util.Optional;

/**
 * Reads an already-authorized teacher resource image and returns only verifiable visible information.
 *
 * <p>The interface deliberately does not accept a remote URL, opaque asset id, or arbitrary caller-provided
 * storage path. Its caller must first materialize the asset through the teacher-resource permission boundary.
 * Implementations return empty when visual reading is disabled, unavailable, or not sufficiently structured;
 * callers must never synthesize a substitute graph relation or mathematical answer.</p>
 */
public interface AuthorizedTeacherImageVisionClient {

    /**
     * Reads visible text, formulas, and labels from a local image that has already passed authorization.
     *
     * @param authorizedImage local materialization created by the permission-checked teacher asset service
     * @param mimeType validated MIME type retained by the teacher asset record
     * @return concise visible facts, or empty when no real analysis is available
     */
    Optional<String> describe(Path authorizedImage, String mimeType);
}
