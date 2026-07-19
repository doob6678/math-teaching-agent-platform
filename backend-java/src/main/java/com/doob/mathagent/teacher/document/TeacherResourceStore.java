package com.doob.mathagent.teacher.document;

import com.doob.mathagent.teacher.document.TeacherResourceDocumentResponse;
import java.util.List;

/**
 * Store abstraction for teacher-managed resource documents.
 */
public interface TeacherResourceStore {

    /**
     * Saves a resource document response.
     *
     * @param document resource document
     * @return saved resource document
     */
    TeacherResourceDocumentResponse save(TeacherResourceDocumentResponse document);

    /**
     * Lists active resource documents visible to the current viewer.
     *
     * @param tenantId tenant id
     * @param viewerRole current viewer role
     * @param viewerSubjectId current viewer subject id
     * @return visible active resources
     */
    List<TeacherResourceDocumentResponse> listVisible(String tenantId, String viewerRole, String viewerSubjectId);

    /**
     * Lists active resource documents whose parsed blocks may be searched by the current viewer.
     *
     * @param tenantId tenant id
     * @param viewerRole current viewer role
     * @param viewerSubjectId current viewer subject id
     * @return searchable active resources
     */
    List<TeacherResourceDocumentResponse> listSearchable(String tenantId, String viewerRole, String viewerSubjectId);

    /**
     * Finds a resource document by tenant and id.
     *
     * @param tenantId tenant id
     * @param documentId document id
     * @return resource document or null
     */
    TeacherResourceDocumentResponse find(String tenantId, String documentId);

    /**
     * Finds an already-registered source with the same immutable source identity and export representation.
     */
    default TeacherResourceDocumentResponse findBySourceIdentity(
            String tenantId, String ownerSubjectId, String sourceType, String sourceIdentity, String feishuExportFormat) {
        return null;
    }

    /**
     * Lists Feishu resources eligible for an explicitly configured background sync identity.
     */
    default List<TeacherResourceDocumentResponse> listSchedulableFeishu(String tenantId) {
        return List.of();
    }
}


