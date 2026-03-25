package com.notificationengine.exception;

public class DuplicateNotificationException extends RuntimeException {
    public DuplicateNotificationException(String idempotencyKey) {
        super("Notification already exists with idempotency key: " + idempotencyKey);
    }
}
