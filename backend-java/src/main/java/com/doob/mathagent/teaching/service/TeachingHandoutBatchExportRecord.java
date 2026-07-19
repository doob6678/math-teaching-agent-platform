package com.doob.mathagent.teaching.service;

import com.doob.mathagent.teaching.vo.TeachingHandoutBatchExportResponse;

/**
 * In-memory temporary ZIP record with immutable owner and expiry metadata.
 *
 * @param response public metadata returned to the frontend
 * @param ownerKey backend owner key allowed to download the package
 * @param zipBytes generated ZIP bytes
 */
public record TeachingHandoutBatchExportRecord(
        TeachingHandoutBatchExportResponse response,
        String ownerKey,
        byte[] zipBytes) {
}
