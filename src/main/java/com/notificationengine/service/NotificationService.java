package com.notificationengine.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.notificationengine.exception.DuplicateNotificationException;
import com.notificationengine.kafka.producer.NotificationProducer;
import com.notificationengine.model.dto.*;
import com.notificationengine.model.entity.Notification;
import com.notificationengine.model.enums.NotificationStatus;
import com.notificationengine.model.enums.Priority;
import com.notificationengine.repository.NotificationRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Main notification service.
 * Orchestrates: validation → template rendering → MySQL persist → Kafka publish.
 */
@Service
@Slf4j
public class NotificationService {

    private final NotificationRepository notificationRepository;
    private final NotificationProducer notificationProducer;
    private final TemplateEngine templateEngine;
    private final DeliveryStatusService deliveryStatusService;
    private final ObjectMapper objectMapper;
    private final Counter receivedCounter;
    private final Counter batchCounter;

    public NotificationService(
            NotificationRepository notificationRepository,
            NotificationProducer notificationProducer,
            TemplateEngine templateEngine,
            DeliveryStatusService deliveryStatusService,
            ObjectMapper objectMapper,
            MeterRegistry meterRegistry) {
        this.notificationRepository = notificationRepository;
        this.notificationProducer = notificationProducer;
        this.templateEngine = templateEngine;
        this.deliveryStatusService = deliveryStatusService;
        this.objectMapper = objectMapper;
        this.receivedCounter = Counter.builder("notification.received").register(meterRegistry);
        this.batchCounter = Counter.builder("notification.batch.received").register(meterRegistry);
    }

    /**
     * Accept a single notification request.
     * Pipeline: validate → resolve template → persist → publish to Kafka.
     */
    @Transactional
    public NotificationResponse sendNotification(NotificationRequest request) {
        receivedCounter.increment();

        // Check for duplicate
        if (notificationRepository.existsByIdempotencyKey(request.getIdempotencyKey())) {
            Notification existing = notificationRepository
                    .findByIdempotencyKey(request.getIdempotencyKey())
                    .orElseThrow();
            log.info("Duplicate notification request, returning existing: {}",
                    existing.getId());
            return DeliveryStatusService.toResponse(existing);
        }

        // Resolve template if provided
        String subject = request.getSubject();
        String body = request.getBody();

        if (request.getTemplateCode() != null) {
            TemplateEngine.RenderedTemplate rendered = templateEngine.render(
                    request.getTemplateCode(),
                    request.getTemplateParams() != null ? request.getTemplateParams() : Map.of()
            );
            subject = rendered.subject();
            body = rendered.body();
        }

        if (body == null || body.isBlank()) {
            throw new IllegalArgumentException("Notification body is required (provide body or valid templateCode)");
        }

        // Persist to MySQL
        Notification notification = Notification.builder()
                .idempotencyKey(request.getIdempotencyKey())
                .userId(request.getUserId())
                .channel(request.getChannel())
                .priority(request.getPriority() != null ? request.getPriority() : Priority.LOW)
                .status(NotificationStatus.QUEUED)
                .templateCode(request.getTemplateCode())
                .subject(subject)
                .body(body)
                .recipient(request.getRecipient())
                .metadata(serializeMetadata(request.getMetadata()))
                .build();

        Notification saved = notificationRepository.save(notification);
        log.info("Notification persisted: id={}, channel={}, priority={}, recipient={}",
                saved.getId(), saved.getChannel(), saved.getPriority(), saved.getRecipient());

        // Build Kafka event and publish
        NotificationEvent event = NotificationEvent.builder()
                .notificationId(saved.getId())
                .idempotencyKey(saved.getIdempotencyKey())
                .userId(saved.getUserId())
                .channel(saved.getChannel())
                .priority(saved.getPriority())
                .subject(saved.getSubject())
                .body(saved.getBody())
                .recipient(saved.getRecipient())
                .retryCount(0)
                .maxRetries(saved.getMaxRetries())
                .createdAt(saved.getCreatedAt())
                .build();

        notificationProducer.publish(event);

        return DeliveryStatusService.toResponse(saved);
    }

    /**
     * Batch send notifications (up to 1000 per batch).
     */
    @Transactional
    public List<NotificationResponse> sendBatch(BatchNotificationRequest batchRequest) {
        batchCounter.increment();
        log.info("Processing batch of {} notifications", batchRequest.getNotifications().size());

        List<NotificationResponse> responses = new ArrayList<>();
        for (NotificationRequest request : batchRequest.getNotifications()) {
            try {
                responses.add(sendNotification(request));
            } catch (DuplicateNotificationException e) {
                log.warn("Skipping duplicate in batch: {}", request.getIdempotencyKey());
            }
        }
        return responses;
    }

    /**
     * Get notification status by ID.
     */
    public NotificationResponse getNotificationStatus(Long id) {
        return deliveryStatusService.getStatus(id);
    }

    /**
     * Get notification status by idempotency key.
     */
    public NotificationResponse getNotificationByKey(String idempotencyKey) {
        return deliveryStatusService.getStatusByIdempotencyKey(idempotencyKey);
    }

    /**
     * List notifications for a user with pagination.
     */
    public Page<NotificationResponse> getNotificationsByUser(String userId, Pageable pageable) {
        return notificationRepository.findByUserId(userId, pageable)
                .map(DeliveryStatusService::toResponse);
    }

    /**
     * Get aggregated stats.
     */
    public Map<String, Object> getStats() {
        List<Object[]> statusCounts = notificationRepository.countByStatusGrouped();
        List<Object[]> channelCounts = notificationRepository.countByChannelAndStatusGrouped();

        return Map.of(
                "byStatus", statusCounts.stream()
                        .collect(java.util.stream.Collectors.toMap(
                                row -> ((NotificationStatus) row[0]).name(),
                                row -> row[1])),
                "byChannelAndStatus", channelCounts.stream()
                        .map(row -> Map.of(
                                "channel", row[0].toString(),
                                "status", row[1].toString(),
                                "count", row[2]))
                        .toList(),
                "last24h", notificationRepository.countCreatedSince(
                        java.time.LocalDateTime.now().minusHours(24))
        );
    }

    private String serializeMetadata(Map<String, String> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(metadata);
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize metadata", e);
            return null;
        }
    }
}
