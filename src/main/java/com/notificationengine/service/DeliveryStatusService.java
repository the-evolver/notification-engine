package com.notificationengine.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.notificationengine.exception.InvalidStateTransitionException;
import com.notificationengine.model.dto.NotificationResponse;
import com.notificationengine.model.entity.DeliveryAuditLog;
import com.notificationengine.model.entity.Notification;
import com.notificationengine.model.enums.NotificationStatus;
import com.notificationengine.repository.DeliveryAuditLogRepository;
import com.notificationengine.repository.NotificationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;

/**
 * Manages the notification delivery state machine:
 *   QUEUED → DISPATCHED → DELIVERED
 *     │          │
 *     └──────────└──→ FAILED
 *
 * Every transition is:
 *  1. Validated against allowed transitions
 *  2. Persisted atomically in MySQL
 *  3. Audit-logged
 *  4. Cached in Redis for <50ms reads
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DeliveryStatusService {

    private static final String CACHE_PREFIX = "notif:status:";
    private static final Duration CACHE_TTL = Duration.ofMinutes(30);

    private final NotificationRepository notificationRepository;
    private final DeliveryAuditLogRepository auditLogRepository;
    private final RedisTemplate<String, Object> redisTemplate;
    private final ObjectMapper objectMapper;

    /**
     * Transition a notification to a new status with full audit trail.
     */
    @Transactional
    public Notification transition(Long notificationId, NotificationStatus newStatus, String message) {
        Notification notification = notificationRepository.findById(notificationId)
                .orElseThrow(() -> new IllegalArgumentException("Notification not found: " + notificationId));

        NotificationStatus currentStatus = notification.getStatus();

        if (!currentStatus.canTransitionTo(newStatus)) {
            throw new InvalidStateTransitionException(currentStatus, newStatus);
        }

        // Update status with timestamp
        notification.setStatus(newStatus);
        LocalDateTime now = LocalDateTime.now();

        switch (newStatus) {
            case DISPATCHED -> notification.setDispatchedAt(now);
            case DELIVERED  -> notification.setDeliveredAt(now);
            case FAILED     -> {
                notification.setFailedAt(now);
                notification.setLastError(message);
            }
            default -> {}
        }
        notification.setUpdatedAt(now);

        Notification saved = notificationRepository.save(notification);

        // Audit log
        auditLogRepository.save(DeliveryAuditLog.builder()
                .notificationId(notificationId)
                .previousStatus(currentStatus)
                .newStatus(newStatus)
                .message(message)
                .build());

        // Update Redis cache
        cacheNotificationStatus(saved);

        log.info("Notification {} transitioned: {} → {} | {}",
                notificationId, currentStatus, newStatus, message != null ? message : "");

        return saved;
    }

    /**
     * Get notification status — cache-first with MySQL fallback.
     * This is the <50ms read path.
     */
    public NotificationResponse getStatus(Long notificationId) {
        // Try Redis first
        String cacheKey = CACHE_PREFIX + notificationId;
        Object cached = redisTemplate.opsForValue().get(cacheKey);

        if (cached != null) {
            try {
                return objectMapper.convertValue(cached, NotificationResponse.class);
            } catch (Exception e) {
                log.warn("Cache deserialization failed for {}, falling back to DB", notificationId);
            }
        }

        // Fallback to MySQL
        Notification notification = notificationRepository.findById(notificationId)
                .orElseThrow(() -> new IllegalArgumentException("Notification not found: " + notificationId));

        NotificationResponse response = toResponse(notification);
        cacheNotificationStatus(notification);
        return response;
    }

    /**
     * Get status by idempotency key.
     */
    public NotificationResponse getStatusByIdempotencyKey(String idempotencyKey) {
        Notification notification = notificationRepository.findByIdempotencyKey(idempotencyKey)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Notification not found with key: " + idempotencyKey));
        return toResponse(notification);
    }

    private void cacheNotificationStatus(Notification notification) {
        try {
            String cacheKey = CACHE_PREFIX + notification.getId();
            NotificationResponse response = toResponse(notification);
            redisTemplate.opsForValue().set(cacheKey, response, CACHE_TTL);
        } catch (Exception e) {
            log.warn("Failed to cache notification status: {}", notification.getId(), e);
        }
    }

    public static NotificationResponse toResponse(Notification n) {
        return NotificationResponse.builder()
                .id(n.getId())
                .idempotencyKey(n.getIdempotencyKey())
                .userId(n.getUserId())
                .channel(n.getChannel())
                .priority(n.getPriority())
                .status(n.getStatus())
                .recipient(n.getRecipient())
                .retryCount(n.getRetryCount())
                .lastError(n.getLastError())
                .dispatchedAt(n.getDispatchedAt())
                .deliveredAt(n.getDeliveredAt())
                .failedAt(n.getFailedAt())
                .createdAt(n.getCreatedAt())
                .build();
    }
}
