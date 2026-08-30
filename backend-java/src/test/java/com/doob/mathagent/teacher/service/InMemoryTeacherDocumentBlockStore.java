package com.doob.mathagent.teacher.service;

import com.doob.mathagent.teacher.block.TeacherDocumentBlockResponse;
import com.doob.mathagent.teacher.block.TeacherDocumentBlockStore;
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

    @Override
    public void beginFileReplacement(String tenantId, String fileDocumentId) {
        String key = key(tenantId, fileDocumentId);
        List<TeacherDocumentBlockResponse> retired = blocksByDocument.getOrDefault(key, List.of()).stream()
                .map(block -> new TeacherDocumentBlockResponse(
                        block.blockId(), block.documentId(), block.externalBlockId(), block.blockType(), block.blockOrder(),
                        block.chapter(), block.section(), block.pageNo(), block.printedPageNo(), block.sourcePath(),
                        block.blockRole(), block.rawText(), block.normalizedText(), block.imageRefs(), block.formulaRefs(),
                        block.graphNodeIdsJson(), block.graphTagNamesJson(), block.checksum(), block.confidence(), "inactive"))
                .toList();
        blocksByDocument.put(key, retired);
    }

    @Override
    public List<TeacherDocumentBlockResponse> replaceActiveBlockBatch(
            String tenantId,
            String fileDocumentId,
            List<TeacherDocumentBlockResponse> blocks) {
        if (blocks == null || blocks.isEmpty()) {
            return List.of();
        }
        String key = key(tenantId, fileDocumentId);
        Map<String, TeacherDocumentBlockResponse> byExternalId = new java.util.LinkedHashMap<>();
        for (TeacherDocumentBlockResponse existing : blocksByDocument.getOrDefault(key, List.of())) {
            if (existing.externalBlockId() != null && !existing.externalBlockId().isBlank()) {
                byExternalId.putIfAbsent(existing.externalBlockId(), existing);
            }
        }
        List<TeacherDocumentBlockResponse> current = new ArrayList<>(blocksByDocument.getOrDefault(key, List.of()));
        for (TeacherDocumentBlockResponse incoming : blocks) {
            TeacherDocumentBlockResponse existing = byExternalId.get(incoming.externalBlockId());
            if (existing != null) {
                current.removeIf(block -> block.blockId().equals(existing.blockId()));
            }
            current.add(incoming);
        }
        blocksByDocument.put(key, List.copyOf(current));
        return List.copyOf(blocks);
    }
    /** Lists active blocks ordered by block order. */
    @Override
    public List<TeacherDocumentBlockResponse> listByDocument(String tenantId, String documentId) {
        return new ArrayList<>(blocksByDocument.getOrDefault(key(tenantId, documentId), List.of())).stream()
                .filter(block -> "active".equals(block.status()))
                .sorted(Comparator.comparingInt(TeacherDocumentBlockResponse::blockOrder))
                .toList();
    }

    @Override
    public List<TeacherDocumentBlockResponse> listBlocksByIds(
            String tenantId, String fileDocumentId, List<String> blockIds, int limit) {
        java.util.Set<String> ids = new java.util.LinkedHashSet<>(blockIds == null ? List.of() : blockIds);
        return listByDocument(tenantId, fileDocumentId).stream()
                .filter(block -> ids.contains(block.blockId()))
                .limit(Math.max(1, Math.min(limit, 64)))
                .toList();
    }

    @Override
    public List<TeacherDocumentBlockResponse> listEvidenceWindow(
            String tenantId, String fileDocumentId, int centerBlockOrder, int radius, int limit) {
        int boundedRadius = Math.max(0, Math.min(radius, 6));
        return listByDocument(tenantId, fileDocumentId).stream()
                .filter(block -> block.blockOrder() >= Math.max(0, centerBlockOrder - boundedRadius)
                        && block.blockOrder() <= centerBlockOrder + boundedRadius)
                .limit(Math.max(1, Math.min(limit, 16)))
                .toList();
    }

    @Override
    public List<TeacherDocumentBlockResponse> listBlocksForFile(
            String tenantId, String fileDocumentId, int limit, Integer afterBlockOrder) {
        return listByDocument(tenantId, fileDocumentId).stream()
                .filter(block -> afterBlockOrder == null || block.blockOrder() > afterBlockOrder)
                .limit(Math.max(1, Math.min(limit, 128)))
                .toList();
    }


    @Override
    public void purgeDocumentContent(String tenantId, String documentId) {
        blocksByDocument.remove(key(tenantId, documentId));
    }

    /**
     * Builds a stable map key from tenant and document id.
     */
    private static String key(String tenantId, String documentId) {
        return tenantId + ":" + documentId;
    }
}

