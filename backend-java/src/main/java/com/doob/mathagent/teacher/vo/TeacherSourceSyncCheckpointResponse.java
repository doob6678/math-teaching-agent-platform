package com.doob.mathagent.teacher.vo;

/**
 * Durable resume checkpoint for a teacher source synchronization job.
 *
 * @param jobId source synchronization job id
 * @param tenantId tenant that owns the job and checkpoint
 * @param documentId source document id bound to this checkpoint
 * @param rootToken Feishu or provider root token being traversed
 * @param currentFolderToken current folder token where traversal stopped
 * @param currentPath human-readable folder path for progress display
 * @param pageToken provider pagination token to resume the current folder page
 * @param visitedFolderTokensJson JSON array of visited folder tokens for cycle protection
 * @param downloadedItemsJson JSON array of successfully downloaded item descriptors
 * @param failedItemsJson JSON array of failed item descriptors and retry counts
 * @param cursorVersion checkpoint schema/protocol version
 * @param updatedAt backend update timestamp
 */
public record TeacherSourceSyncCheckpointResponse(
        String jobId,
        String tenantId,
        String documentId,
        String rootToken,
        String currentFolderToken,
        String currentPath,
        String pageToken,
        String visitedFolderTokensJson,
        String downloadedItemsJson,
        String failedItemsJson,
        int cursorVersion,
        String updatedAt) {
}
