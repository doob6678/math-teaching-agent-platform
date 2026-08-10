package com.doob.mathagent.teacher.sync;

import java.time.Instant;
import java.util.List;

/**
 * Persists one durable state machine row for every remote Feishu file discovered below a sync root.
 *
 * <p>The folder job remains the orchestration record, while this store is the recovery authority for individual
 * downloads and indexing stages. That separation prevents a failed embedding request for one file from hiding the
 * successfully indexed files in the same Feishu folder.</p>
 */
public interface TeacherSourceSyncManifestStore {

    /** Resolves the stable provider file identity for one persisted source path. */
    default String providerItemId(String tenantId, String documentId, String sourcePath) {
        return "";
    }

    /** Records the latest provider metadata snapshot without downloading file bodies. */
    void recordDiscovery(String tenantId, String rootUrl, String createdBy, String documentId, String discoveredItemsJson);

    /** Marks only the changed files returned by the downloader as locally materialized. */
    void markDownloaded(String tenantId, String rootUrl, String changedItemsJson, Instant now);

    /** Marks the changed files as parsing before the parser starts. */
    void markParsing(String tenantId, String rootUrl, String changedItemsJson, Instant now);

    /** Marks changed files after parsing has completed and before embedding/index work starts. */
    void markParsed(String tenantId, String rootUrl, String changedItemsJson, Instant now);

    /** Marks changed files while the embedding/vector operation is in progress. */
    void markEmbedding(String tenantId, String rootUrl, String changedItemsJson, Instant now);

    /** Marks the changed files as indexed after Milvus and embedding have completed. */
    void markIndexed(String tenantId, String rootUrl, String changedItemsJson, Instant now);

    /** Records a retryable or terminal file-level failure with a durable next retry time. */
    void markFailed(String tenantId, String rootUrl, String changedItemsJson, String error, Instant nextRetryAt, Instant now);

    /** Marks every non-terminal file under a root failed when the downloader failed before returning its item list. */
    void markRootFailed(String tenantId, String rootUrl, String error, Instant nextRetryAt, Instant now);

    /** Requeues files whose lease expired during a process or host failure. */
    int recoverExpiredLeases(Instant now);
}
