package com.doob.mathagent.teacher.service;

/**
 * Runtime exception raised by Feishu download clients with retryability metadata.
 */
public class TeacherFeishuDownloadException extends RuntimeException {

    private final boolean retryable;
    private final TeacherFeishuDownloadClient.FeishuDownloadCheckpoint checkpoint;

    /**
     * Creates a Feishu download exception.
     *
     * @param message concise failure message safe for job status
     * @param retryable whether the caller can pause and resume this job later
     */
    public TeacherFeishuDownloadException(String message, boolean retryable) {
        this(message, retryable, null, TeacherFeishuDownloadClient.FeishuDownloadCheckpoint.empty());
    }

    /**
     * Creates a Feishu download exception with a root cause.
     *
     * @param message concise failure message safe for job status
     * @param retryable whether the caller can pause and resume this job later
     * @param cause original exception
     */
    public TeacherFeishuDownloadException(String message, boolean retryable, Throwable cause) {
        this(message, retryable, cause, TeacherFeishuDownloadClient.FeishuDownloadCheckpoint.empty());
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
        super(message, cause);
        this.retryable = retryable;
        this.checkpoint = checkpoint == null ? TeacherFeishuDownloadClient.FeishuDownloadCheckpoint.empty() : checkpoint;
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
}
