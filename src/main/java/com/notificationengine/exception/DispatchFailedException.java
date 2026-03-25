package com.notificationengine.exception;

public class DispatchFailedException extends RuntimeException {
    private final boolean retryable;

    public DispatchFailedException(String message, boolean retryable) {
        super(message);
        this.retryable = retryable;
    }

    public DispatchFailedException(String message, Throwable cause, boolean retryable) {
        super(message, cause);
        this.retryable = retryable;
    }

    public boolean isRetryable() {
        return retryable;
    }
}
