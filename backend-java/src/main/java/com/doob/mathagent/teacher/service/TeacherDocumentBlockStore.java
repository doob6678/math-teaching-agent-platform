package com.doob.mathagent.teacher.service;

import com.doob.mathagent.teacher.vo.TeacherDocumentBlockResponse;
import java.util.List;

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
}
