package com.notificationengine.controller;

import com.notificationengine.model.entity.DeliveryAuditLog;
import com.notificationengine.repository.DeliveryAuditLogRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/admin")
@RequiredArgsConstructor
public class AdminController {

    private final DeliveryAuditLogRepository auditLogRepository;

    /**
     * Get the full delivery audit trail for a notification.
     * Shows every state transition with timestamps.
     */
    @GetMapping("/audit/{notificationId}")
    public ResponseEntity<List<DeliveryAuditLog>> getAuditTrail(@PathVariable Long notificationId) {
        List<DeliveryAuditLog> logs = auditLogRepository
                .findByNotificationIdOrderByCreatedAtAsc(notificationId);
        return ResponseEntity.ok(logs);
    }

    /**
     * Simple liveness check.
     */
    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        return ResponseEntity.ok(Map.of(
                "status", "UP",
                "service", "notification-engine",
                "timestamp", LocalDateTime.now().toString()
        ));
    }
}
