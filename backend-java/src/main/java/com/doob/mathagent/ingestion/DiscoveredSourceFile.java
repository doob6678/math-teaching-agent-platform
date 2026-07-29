package com.doob.mathagent.ingestion;

import java.nio.file.Path;

/**
 * Immutable description of an input file. The absolute path remains internal: callers use {@link #fileName()} only
 * for progress displays, while the checksum is the durable idempotency identity.
 */
public record DiscoveredSourceFile(Path path, String fileName, String mediaType, String sha256) { }
