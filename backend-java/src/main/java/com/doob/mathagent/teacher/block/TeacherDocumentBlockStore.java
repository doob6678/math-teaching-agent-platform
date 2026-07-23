package com.doob.mathagent.teacher.block;

import com.doob.mathagent.teacher.block.TeacherDocumentBlockResponse;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Store abstraction for parsed document blocks used by private/public RAG.
 */
public interface TeacherDocumentBlockStore {

    /**
     * Replaces active blocks for a document with newly parsed blocks.
     *
     * @param tenantId tenant id used by callers for isolation checks
     * @param documentId source document id
     * @param blocks parsed active blocks
     * @return saved active blocks
     */
    List<TeacherDocumentBlockResponse> replaceActiveBlocks(
            String tenantId,
            String documentId,
            List<TeacherDocumentBlockResponse> blocks);

    /**
     * Lists active blocks for a document.
     *
     * @param tenantId tenant id used by callers for isolation checks
     * @param documentId source document id
     * @return active blocks ordered by blockOrder
     */
    List<TeacherDocumentBlockResponse> listByDocument(String tenantId, String documentId);

    /**
     * Removes parsed source content after an archived source has had its vectors removed.
     *
     * <p>The source-document row remains as a compact audit record, while its raw text, normalized text and
     * block-level references must not remain available through the local corpus.</p>
     *
     * @param tenantId tenant boundary
     * @param documentId archived source document id
     */
    default void purgeDocumentContent(String tenantId, String documentId) {
        // Lightweight stores that do not retain block data need no extra work.
    }

    /**
     * Lists active blocks for multiple documents.
     *
     * <p>Teacher search uses this to avoid an N+1 query pattern during stage-one candidate admission. The default
     * implementation preserves compatibility for lightweight/in-memory stores by delegating to
     * {@link #listByDocument(String, String)} one document at a time; database-backed stores should override it with a
     * single batched query.</p>
     *
     * @param tenantId tenant id used by callers for isolation checks
     * @param documentIds source document ids in preferred caller order
     * @return active blocks keyed by document id
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


