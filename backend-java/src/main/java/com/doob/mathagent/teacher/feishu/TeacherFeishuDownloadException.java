package com.doob.mathagent.teacher.feishu;

import com.doob.mathagent.teacher.vo.TeacherSourceSyncFailureResponse;

/**
 * Runtime exception raised by Feishu download clients with retryability metadata.
 */
public class TeacherFeishuDownloadException extends RuntimeException {

    private final boolean retryable;
    private final TeacherFeishuDownloadClient.FeishuDownloadCheckpoint checkpoint;
    private final TeacherSourceSyncFailureResponse failure;
    private final String failedItemsJson;

    /**
     * Creates a Feishu download exception.
     *
     * @param message concise failure message safe for job status
     * @param retryable whether the caller can pause and resume this job later
     */
    public TeacherFeishuDownloadException(String message, boolean retryable) {
        this(message, retryable, null, TeacherFeishuDownloadClient.FeishuDownloadCheckpoint.empty(),
                TeacherSourceSyncFailureResponse.none());
    }

    /**
     * Creates a Feishu download exception with a root cause.
     *
     * @param message concise failure message safe for job status
     * @param retryable whether the caller can pause and resume this job later
     * @param cause original exception
     */
    public TeacherFeishuDownloadException(String message, boolean retryable, Throwable cause) {
        this(message, retryable, cause, TeacherFeishuDownloadClient.FeishuDownloadCheckpoint.empty(),
                TeacherSourceSyncFailureResponse.none());
    }

    /**
     * Creates a Feishu download exception with a root cause and latest durable checkpoint.
     *
     * @param message concise failure message safe for job status
     * @param retryable whether the caller can pause and resume this job later
     * @param cause original exception
     * @param checkpoint latest downloader checkpoint known at failure time
     */
    public TeacherFeishuDownloadException(
            String message,
            boolean retryable,
            Throwable cause,
            TeacherFeishuDownloadClient.FeishuDownloadCheckpoint checkpoint) {
        this(message, retryable, cause, checkpoint, TeacherSourceSyncFailureResponse.none());
    }

    /**
     * Creates a failure with structured provider authorization/permission details captured from the real worker output.
     */
    public TeacherFeishuDownloadException(
            String message,
            boolean retryable,
            Throwable cause,
            TeacherFeishuDownloadClient.FeishuDownloadCheckpoint checkpoint,
            TeacherSourceSyncFailureResponse failure) {
        this(message, retryable, cause, checkpoint, failure, "[]");
    }

    /**
     * Creates a failure with the downloader's item-level error records for durable checkpoint inspection.
     */
    public TeacherFeishuDownloadException(
            String message,
            boolean retryable,
            Throwable cause,
            TeacherFeishuDownloadClient.FeishuDownloadCheckpoint checkpoint,
            TeacherSourceSyncFailureResponse failure,
            String failedItemsJson) {
        super(message, cause);
        this.retryable = retryable;
        this.checkpoint = checkpoint == null ? TeacherFeishuDownloadClient.FeishuDownloadCheckpoint.empty() : checkpoint;
        this.failure = failure == null ? TeacherSourceSyncFailureResponse.none() : failure;
        this.failedItemsJson = failedItemsJson == null || failedItemsJson.isBlank() ? "[]" : failedItemsJson.strip();
    }

    /**
     * Returns whether this failure is safe to resume after network recovery.
     */
    public boolean retryable() {
        return retryable;
    }

    /**
     * Returns the latest downloader checkpoint supplied by the Feishu worker.
     */
    public TeacherFeishuDownloadClient.FeishuDownloadCheckpoint checkpoint() {
        return checkpoint;
    }

    /** Provider details safe to persist in source_sync_job.metadata_json and show to the authorization UI. */
    public TeacherSourceSyncFailureResponse failure() {
        return failure;
    }

    /** Item-level downloader failures retained as plaintext checkpoint evidence after provider-side redaction. */
    public String failedItemsJson() {
        return failedItemsJson;
    }
}
