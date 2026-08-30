package com.doob.mathagent.teacher.document;

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

    /** Returns whether this store can persist true ROOT/FILE document identities. */
    default boolean supportsFileDocuments() {
        return false;
    }

    /**
     * Resolves the durable FILE document for one physical source file. The identity is created at ingestion time and
     * is never reconstructed by the request-time search path.
     */
    default TeacherFileDocument findOrCreateFileDocument(
            TeacherResourceDocumentResponse rootDocument,
            String providerItemId,
            String sourcePath,
            String checksum,
            String splitFingerprint) {
        return null;
    }

    /** Lists visible FILE documents below one or more authorized ROOT documents with a SQL-side limit. */
    default List<TeacherFileDocument> listSearchableFileDocuments(
            String tenantId,
            String viewerRole,
            String viewerSubjectId,
            List<String> rootDocumentIds,
            int limit) {
        return List.of();
    }

    /** Lists visible FILE documents by their durable ids with a SQL-side result bound. */
    default List<TeacherFileDocument> listSearchableFileDocumentsByIds(
            String tenantId,
            String viewerRole,
            String viewerSubjectId,
            List<String> fileDocumentIds,
            int limit) {
        return List.of();
    }

    /** Lists non-archived FILE documents for one ROOT during bounded indexing or cleanup. */
    default List<TeacherFileDocument> listFileDocumentsForIndexing(
            String tenantId,
            String rootDocumentId,
            int limit) {
        return List.of();
    }

    /** Returns whether one ROOT still has archived FILE rows that require durable reactivation. */
    default boolean hasArchivedFileDocuments(String tenantId, String rootDocumentId) {
        return false;
    }

    /** Archives FILE rows that disappeared from the provider manifest. */
    default int archiveMissingFileDocuments(String tenantId, String rootDocumentId, List<String> activeFileIdentityHashes) {
        return 0;
    }

    /** Lists a bounded page of active FILE rows absent from the latest provider manifest. */
    default List<TeacherFileDocument> listMissingFileDocuments(
            String tenantId,
            String rootDocumentId,
            List<String> activeFileIdentityHashes,
            String afterFileDocumentId,
            int limit) {
        return List.of();
    }

    /** Archives one FILE row after its vectors, blocks, and assets have been cleaned. */
    default boolean archiveFileDocument(String tenantId, String fileDocumentId) {
        return false;
    }

    /** Lists a bounded page of active FILE rows for one ROOT using a durable id cursor. */
    default List<TeacherFileDocument> listFileDocumentsForIndexing(
            String tenantId,
            String rootDocumentId,
            int limit,
            String afterFileDocumentId) {
        return listFileDocumentsForIndexing(tenantId, rootDocumentId, limit);
    }

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

    /** Durable file-level identity used by ingestion, indexing, authorization and citation lookup. */
    record TeacherFileDocument(
            String documentId,
            String rootDocumentId,
            String providerItemId,
            String sourcePath,
            String fileIdentityHash,
            String splitFingerprint,
            TeacherResourceDocumentResponse document) {
    }
}
