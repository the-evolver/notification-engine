package com.notificationengine.model.enums;

import java.util.Set;
import java.util.Map;

/**
 * Delivery status state machine:
 *
 *   QUEUED ──→ DISPATCHED ──→ DELIVERED
 *     │            │
 *     └────────────└──→ FAILED
 *
 * Valid transitions are enforced at the service layer.
 */
public enum NotificationStatus {
    QUEUED,
    DISPATCHED,
    DELIVERED,
    FAILED;

    private static final Map<NotificationStatus, Set<NotificationStatus>> VALID_TRANSITIONS = Map.of(
            QUEUED,      Set.of(DISPATCHED, FAILED),
            DISPATCHED,  Set.of(DELIVERED, FAILED),
            DELIVERED,   Set.of(),           // terminal
            FAILED,      Set.of(QUEUED)      // allow re-queue from DLQ
    );

    public boolean canTransitionTo(NotificationStatus target) {
        return VALID_TRANSITIONS.getOrDefault(this, Set.of()).contains(target);
    }
}
