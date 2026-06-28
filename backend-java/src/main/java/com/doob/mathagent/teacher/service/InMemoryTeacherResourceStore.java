package com.doob.mathagent.teacher.service;

import com.doob.mathagent.teacher.vo.TeacherResourceDocumentResponse;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

/**
 * In-memory teacher resource store for local development and tests.
 */
@Repository
@ConditionalOnProperty(prefix = "math-agent.database", name = "enabled", havingValue = "false", matchIfMissing = true)
public class InMemoryTeacherResourceStore implements TeacherResourceStore {

    /** Resource documents keyed by document id. */
    private final Map<String, TeacherResourceDocumentResponse> documents = new ConcurrentHashMap<>();

    /**
     * Saves a resource document in memory.
     *
     * @param document resource document
     * @return saved resource document
     */
    @Override
    public TeacherResourceDocumentResponse save(TeacherResourceDocumentResponse document) {
        documents.put(document.documentId(), document);
        return document;
    }

    /**
     * Lists active documents visible to this viewer. Admin sees all tenant documents; teachers see their own.
     *
     * @param tenantId tenant id
     * @param viewerRole current viewer role
     * @param viewerSubjectId current viewer subject id
     * @return visible active resource documents
     */
    @Override
    public List<TeacherResourceDocumentResponse> listVisible(String tenantId, String viewerRole, String viewerSubjectId) {
        return documents.values().stream()
                .filter(document -> document.tenantId().equals(tenantId))
                .filter(document -> !"archived".equals(document.syncStatus()))
                .filter(document -> "admin".equals(viewerRole) || document.ownerSubjectId().equals(viewerSubjectId))
                .sorted(Comparator.comparing(TeacherResourceDocumentResponse::documentId))
                .toList();
    }

    /**
     * Finds a resource document by tenant and id.
     *
     * @param tenantId tenant id
     * @param documentId document id
     * @return resource document or null
     */
    @Override
    public TeacherResourceDocumentResponse find(String tenantId, String documentId) {
        TeacherResourceDocumentResponse document = documents.get(documentId);
        if (document == null || !document.tenantId().equals(tenantId)) {
            return null;
        }
        return document;
    }

    /**
     * Returns a snapshot of all stored documents for diagnostics.
     *
     * @return all stored documents
     */
    public List<TeacherResourceDocumentResponse> snapshot() {
        return new ArrayList<>(documents.values());
    }
}
