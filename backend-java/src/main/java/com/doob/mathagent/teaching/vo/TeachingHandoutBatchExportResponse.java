package com.doob.mathagent.teaching.vo;

import java.time.Instant;
import java.util.List;

/**
 * Response returned after creating a temporary teaching handout ZIP package.
 *
 * @param batchId temporary package id used by the protected download endpoint
 * @param status export status; the current baseline completes synchronously
 * @param requestedCount number of task ids requested by the caller
 * @param exportedCount number of owned task handouts written into the ZIP
 * @param taskIds owned task ids included in the ZIP
 * @param folderIds folder ids captured for audit and future backend folder expansion
 * @param folderPaths folder paths used to organize files inside the ZIP
 * @param expiresAt backend expiration time after which the temporary package cannot be downloaded
 */
public record TeachingHandoutBatchExportResponse(
        String batchId,
        String status,
        int requestedCount,
        int exportedCount,
        List<String> taskIds,
        List<String> folderIds,
        List<String> folderPaths,
        Instant expiresAt) {
}
