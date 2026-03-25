package com.notificationengine.exception;

import com.notificationengine.model.enums.NotificationStatus;

public class InvalidStateTransitionException extends RuntimeException {
    public InvalidStateTransitionException(NotificationStatus from, NotificationStatus to) {
        super("Invalid state transition from " + from + " to " + to);
    }
}
