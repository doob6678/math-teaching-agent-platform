package com.doob.mathagent.teacher.service;

import com.doob.mathagent.teacher.vo.TeacherDocumentBlockResponse;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory document block store for tests and local no-database runs.
 */
public class InMemoryTeacherDocumentBlockStore implements TeacherDocumentBlockStore {

    private final Map<String, List<TeacherDocumentBlockResponse>> blocksByDocument = new ConcurrentHashMap<>();

    /**
     * Replaces active blocks under a tenant/document compound key.
     */
    @Override
    public List<TeacherDocumentBlockResponse> replaceActiveBlocks(
            String tenantId,
            String documentId,
            List<TeacherDocumentBlockResponse> blocks) {
        String key = key(tenantId, documentId);
        List<TeacherDocumentBlockResponse> snapshot = List.copyOf(blocks);
        blocksByDocument.put(key, snapshot);
        return snapshot;
    }

    /**
     * Lists active blocks ordered by block order.
     */
    @Override
    public List<TeacherDocumentBlockResponse> listByDocument(String tenantId, String documentId) {
        return new ArrayList<>(blocksByDocument.getOrDefault(key(tenantId, documentId), List.of())).stream()
                .filter(block -> "active".equals(block.status()))
                .sorted(Comparator.comparingInt(TeacherDocumentBlockResponse::blockOrder))
                .toList();
    }

    /**
     * Builds a stable map key from tenant and document id.
     */
    private static String key(String tenantId, String documentId) {
        return tenantId + ":" + documentId;
    }
}
