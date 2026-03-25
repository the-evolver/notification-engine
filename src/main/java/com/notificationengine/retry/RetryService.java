package com.notificationengine.retry;

import com.notificationengine.model.dto.NotificationEvent;
import com.notificationengine.model.entity.Notification;
import com.notificationengine.model.enums.NotificationStatus;
import com.notificationengine.repository.NotificationRepository;
import com.notificationengine.service.DeliveryStatusService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * Handles retry logic with exponential backoff.
 *
 * Strategy:
 *  - On failure, increment retry count
 *  - Compute next retry time: initialInterval * multiplier^retryCount
 *  - If max retries exceeded → route to DLQ
 *  - DLQ messages can be manually reprocessed via admin API
 */
@Service
@Slf4j
public class RetryService {

    @Value("${notification.retry.max-attempts}")
    private int maxAttempts;

    @Value("${notification.retry.initial-interval-ms}")
    private long initialIntervalMs;

    @Value("${notification.retry.multiplier}")
    private double multiplier;

    @Value("${notification.retry.max-interval-ms}")
    private long maxIntervalMs;

    @Value("${notification.kafka.topics.dlq}")
    private String dlqTopic;

    private final NotificationRepository notificationRepository;
    private final DeliveryStatusService deliveryStatusService;
    private final KafkaTemplate<String, NotificationEvent> kafkaTemplate;
    private final Counter retryCounter;
    private final Counter dlqCounter;

    public RetryService(
            NotificationRepository notificationRepository,
            DeliveryStatusService deliveryStatusService,
            KafkaTemplate<String, NotificationEvent> kafkaTemplate,
            MeterRegistry meterRegistry) {
        this.notificationRepository = notificationRepository;
        this.deliveryStatusService = deliveryStatusService;
        this.kafkaTemplate = kafkaTemplate;
        this.retryCounter = Counter.builder("notification.retry.count").register(meterRegistry);
        this.dlqCounter = Counter.builder("notification.dlq.count").register(meterRegistry);
    }

    /**
     * Handle a failed dispatch. Either schedule retry or route to DLQ.
     *
     * @return true if scheduled for retry, false if sent to DLQ
     */
    @Transactional
    public boolean handleFailure(NotificationEvent event, String errorMessage) {
        Long notificationId = event.getNotificationId();
        int currentRetry = event.getRetryCount() != null ? event.getRetryCount() : 0;

        if (currentRetry >= maxAttempts) {
            // Max retries exceeded → DLQ
            routeToDlq(event, errorMessage);
            return false;
        }

        // Schedule retry with exponential backoff
        int nextRetry = currentRetry + 1;
        long delayMs = computeBackoff(nextRetry);
        LocalDateTime nextRetryAt = LocalDateTime.now().plusNanos(delayMs * 1_000_000);

        Notification notification = notificationRepository.findById(notificationId)
                .orElse(null);

        if (notification != null) {
            notification.setRetryCount(nextRetry);
            notification.setNextRetryAt(nextRetryAt);
            notification.setLastError(errorMessage);
            notificationRepository.save(notification);
        }

        log.warn("Scheduling retry {}/{} for notification {} in {}ms | Error: {}",
                nextRetry, maxAttempts, notificationId, delayMs, errorMessage);

        retryCounter.increment();
        return true;
    }

    /**
     * Route to Dead Letter Queue after max retries exhausted.
     */
    private void routeToDlq(NotificationEvent event, String errorMessage) {
        log.error("Max retries exhausted for notification {}. Routing to DLQ. Error: {}",
                event.getNotificationId(), errorMessage);

        // Transition to FAILED
        deliveryStatusService.transition(
                event.getNotificationId(),
                NotificationStatus.FAILED,
                "Max retries exhausted: " + errorMessage
        );

        // Send to DLQ topic
        kafkaTemplate.send(dlqTopic, event.getIdempotencyKey(), event);

        dlqCounter.increment();
    }

    /**
     * Compute exponential backoff delay.
     * Formula: min(initialInterval * multiplier^retry, maxInterval)
     */
    long computeBackoff(int retryAttempt) {
        long delay = (long) (initialIntervalMs * Math.pow(multiplier, retryAttempt - 1));
        return Math.min(delay, maxIntervalMs);
    }
}
