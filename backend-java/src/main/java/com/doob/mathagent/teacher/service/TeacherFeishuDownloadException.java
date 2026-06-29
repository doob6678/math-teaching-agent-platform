package com.doob.mathagent.teacher.service;

/**
 * Runtime exception raised by Feishu download clients with retryability metadata.
 */
public class TeacherFeishuDownloadException extends RuntimeException {

    private final boolean retryable;

    /**
     * Creates a Feishu download exception.
     *
     * @param message concise failure message safe for job status
     * @param retryable whether the caller can pause and resume this job later
     */
    public TeacherFeishuDownloadException(String message, boolean retryable) {
        super(message);
        this.retryable = retryable;
    }

    /**
     * Creates a Feishu download exception with a root cause.
     *
     * @param message concise failure message safe for job status
     * @param retryable whether the caller can pause and resume this job later
     * @param cause original exception
     */
    public TeacherFeishuDownloadException(String message, boolean retryable, Throwable cause) {
        super(message, cause);
        this.retryable = retryable;
    }

    /**
     * Returns whether this failure is safe to resume after network recovery.
     */
    public boolean retryable() {
        return retryable;
    }
}
