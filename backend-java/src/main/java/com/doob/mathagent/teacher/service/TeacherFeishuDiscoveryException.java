package com.doob.mathagent.teacher.service;

/**
 * Runtime exception raised by Feishu discovery clients with retryability metadata.
 */
public class TeacherFeishuDiscoveryException extends RuntimeException {

    private final boolean retryable;

    /**
     * Creates a discovery exception.
     *
     * @param message error message safe for logs and job state
     * @param retryable whether retrying later may succeed
     */
    public TeacherFeishuDiscoveryException(String message, boolean retryable) {
        super(message);
        this.retryable = retryable;
    }

    /**
     * Creates a discovery exception with a root cause.
     *
     * @param message error message safe for logs and job state
     * @param retryable whether retrying later may succeed
     * @param cause root cause
     */
    public TeacherFeishuDiscoveryException(String message, boolean retryable, Throwable cause) {
        super(message, cause);
        this.retryable = retryable;
    }

    /**
     * Returns whether retrying later may succeed.
     */
    public boolean retryable() {
        return retryable;
    }
}
