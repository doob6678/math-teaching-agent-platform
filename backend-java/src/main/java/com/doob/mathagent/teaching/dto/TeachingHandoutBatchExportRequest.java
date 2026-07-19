package com.doob.mathagent.teaching.dto;

import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * Request for creating a temporary ZIP package of teaching handouts.
 *
 * @param taskIds task ids selected for export; each id is reloaded through backend ownership checks
 * @param folderIds optional frontend folder ids used for audit and later persistent folder expansion
 * @param folderPaths optional folder paths used as ZIP entry prefixes when packaging the selected tasks
 */
public record TeachingHandoutBatchExportRequest(
        @Size(max = 100) List<String> taskIds,
        @Size(max = 20) List<String> folderIds,
        @Size(max = 20) List<String> folderPaths) {

    /**
     * Returns a null-safe request with stripped values and empty lists.
     */
    public TeachingHandoutBatchExportRequest normalize() {
        return new TeachingHandoutBatchExportRequest(
                normalizeList(taskIds, 100),
                normalizeList(folderIds, 20),
                normalizeList(folderPaths, 20));
    }

    /**
     * Removes blank values while preserving caller order for deterministic ZIP output.
     */
    private static List<String> normalizeList(List<String> values, int limit) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        return values.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(String::strip)
                .distinct()
                .limit(limit)
                .toList();
    }
}
