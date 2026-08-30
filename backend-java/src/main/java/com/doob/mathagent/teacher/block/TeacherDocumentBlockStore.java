package com.doob.mathagent.teacher.block;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Store abstraction for parsed document blocks used by private/public RAG.
 */
public interface TeacherDocumentBlockStore {

    /** Replaces active blocks for one already-persisted FILE document. */
    List<TeacherDocumentBlockResponse> replaceActiveBlocks(
            String tenantId,
            String documentId,
            List<TeacherDocumentBlockResponse> blocks);

    /** Starts a bounded replacement generation for one FILE by retiring its prior active blocks. */
    default void beginFileReplacement(String tenantId, String fileDocumentId) {
        // Stores without generation support retain the compatibility replacement contract.
    }

    /** Writes one bounded batch into a replacement generation without loading the FILE's old blocks. */
    default List<TeacherDocumentBlockResponse> replaceActiveBlockBatch(
            String tenantId,
            String fileDocumentId,
            List<TeacherDocumentBlockResponse> blocks) {
        return replaceActiveBlocks(tenantId, fileDocumentId, blocks);
    }

    /** Completes a bounded FILE replacement after all batches have been persisted. */
    default void completeFileReplacement(String tenantId, String fileDocumentId) {
        // Retired blocks were already made inactive by beginFileReplacement.
    }

    /** Lists active blocks for a document; retained for management and compatibility paths, not bounded search. */
    List<TeacherDocumentBlockResponse> listByDocument(String tenantId, String documentId);

    /** Returns selected blocks by persisted block id, bounded by the caller's id list. */
    default List<TeacherDocumentBlockResponse> listBlocksByIds(
            String tenantId, String fileDocumentId, List<String> blockIds, int limit) {
        return List.of();
    }

    /** Returns a bounded block-order window from one FILE document. */
    default List<TeacherDocumentBlockResponse> listEvidenceWindow(
            String tenantId, String fileDocumentId, int centerBlockOrder, int radius, int limit) {
        return List.of();
    }

    /** Returns a bounded page of active blocks for one FILE document. */
    default List<TeacherDocumentBlockResponse> listBlocksForFile(
            String tenantId, String fileDocumentId, int limit, Integer afterBlockOrder) {
        return List.of();
    }

    /** Returns BM25-ranked active blocks from visible physical Feishu FILE documents. */
    default List<TeacherDocumentBlockResponse> searchFileBlocksByLexicalTerms(
            String tenantId, String viewerRole, String viewerSubjectId, List<String> terms, int limit) {
        return searchFileBlocksByLexicalTerms(
                tenantId, viewerRole, viewerSubjectId, terms, limit,
                com.doob.mathagent.teacher.search.TeacherResourceSearchFilter.EMPTY);
    }

    /** Returns BM25-ranked active FILE blocks while preserving caller-supplied document and scope boundaries. */
    default List<TeacherDocumentBlockResponse> searchFileBlocksByLexicalTerms(
            String tenantId,
            String viewerRole,
            String viewerSubjectId,
            List<String> terms,
            int limit,
            com.doob.mathagent.teacher.search.TeacherResourceSearchFilter filter) {
        return List.of();
    }

    /** Returns a SQL-bounded graph-tag representative block for each visible physical FILE. */
    default List<TeacherDocumentBlockResponse> searchFileBlocksByGraphTags(
            String tenantId, String viewerRole, String viewerSubjectId, List<String> tagNames, int limit) {
        return List.of();
    }

    /** Removes parsed source content after an archived source has had its vectors removed. */
    default void purgeDocumentContent(String tenantId, String documentId) {
        // Lightweight stores that do not retain block data need no extra work.
    }

    /**
     * Legacy multi-document API. It is retained for management/compatibility callers only; search must use the bounded
     * file APIs above so a shared ROOT never causes a full-corpus block load.
     */
    default Map<String, List<TeacherDocumentBlockResponse>> listByDocuments(String tenantId, List<String> documentIds) {
        Map<String, List<TeacherDocumentBlockResponse>> blocksByDocumentId = new LinkedHashMap<>();
        if (documentIds == null || documentIds.isEmpty()) {
            return blocksByDocumentId;
        }
        for (String documentId : documentIds) {
            if (documentId == null || documentId.isBlank()) {
                continue;
            }
            blocksByDocumentId.put(documentId, listByDocument(tenantId, documentId));
        }
        return blocksByDocumentId;
    }
}
