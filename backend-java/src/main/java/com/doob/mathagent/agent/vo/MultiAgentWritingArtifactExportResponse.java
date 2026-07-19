package com.doob.mathagent.agent.vo;

import java.time.Instant;

/**
 * Temporary export metadata and payload for one multi-agent writing artifact.
 *
 * @param exportId temporary export id
 * @param workflowId source workflow id
 * @param format export format, such as markdown or zip
 * @param fileName suggested download file name
 * @param mimeType MIME type of the exported payload
 * @param byteSize exported byte length
 * @param sha256 SHA-256 digest of the exported bytes
 * @param base64Content Base64 encoded exported bytes for MCP clients
 * @param expiresAt temporary export expiration time
 */
public record MultiAgentWritingArtifactExportResponse(
        String exportId,
        String workflowId,
        String format,
        String fileName,
        String mimeType,
        long byteSize,
        String sha256,
        String base64Content,
        Instant expiresAt) {
}
