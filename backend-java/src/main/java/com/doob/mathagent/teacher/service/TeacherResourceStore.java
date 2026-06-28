package com.doob.mathagent.teacher.service;

import com.doob.mathagent.teacher.vo.TeacherResourceDocumentResponse;
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
     * Finds a resource document by tenant and id.
     *
     * @param tenantId tenant id
     * @param documentId document id
     * @return resource document or null
     */
    TeacherResourceDocumentResponse find(String tenantId, String documentId);
}
