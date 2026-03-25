package com.notificationengine.retry;

import com.notificationengine.kafka.producer.NotificationProducer;
import com.notificationengine.model.dto.NotificationEvent;
import com.notificationengine.model.entity.Notification;
import com.notificationengine.model.enums.NotificationStatus;
import com.notificationengine.repository.NotificationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Scheduled task that polls MySQL for notifications due for retry
 * and re-publishes them to the appropriate Kafka topic.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class RetryScheduler {

    private final NotificationRepository notificationRepository;
    private final NotificationProducer notificationProducer;

    @Scheduled(fixedDelayString = "5000") // Poll every 5 seconds
    public void processRetries() {
        List<Notification> retryable = notificationRepository.findRetryableNotifications(
                NotificationStatus.QUEUED, LocalDateTime.now());

        if (retryable.isEmpty()) {
            return;
        }

        log.info("Found {} notifications due for retry", retryable.size());

        for (Notification notification : retryable) {
            try {
                NotificationEvent event = NotificationEvent.builder()
                        .notificationId(notification.getId())
                        .idempotencyKey(notification.getIdempotencyKey())
                        .userId(notification.getUserId())
                        .channel(notification.getChannel())
                        .priority(notification.getPriority())
                        .subject(notification.getSubject())
                        .body(notification.getBody())
                        .recipient(notification.getRecipient())
                        .retryCount(notification.getRetryCount())
                        .maxRetries(notification.getMaxRetries())
                        .createdAt(notification.getCreatedAt())
                        .build();

                notificationProducer.publish(event);

                // Clear next_retry_at so it won't be picked up again immediately
                notification.setNextRetryAt(null);
                notificationRepository.save(notification);

                log.info("Re-published notification {} for retry attempt {}",
                        notification.getId(), notification.getRetryCount());

            } catch (Exception e) {
                log.error("Failed to re-publish notification {} for retry",
                        notification.getId(), e);
            }
        }
    }
}
