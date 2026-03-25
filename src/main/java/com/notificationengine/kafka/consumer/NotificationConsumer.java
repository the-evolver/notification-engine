package com.notificationengine.kafka.consumer;

import com.notificationengine.exception.DispatchFailedException;
import com.notificationengine.model.dto.NotificationEvent;
import com.notificationengine.model.enums.NotificationStatus;
import com.notificationengine.retry.RetryService;
import com.notificationengine.service.DeliveryStatusService;
import com.notificationengine.service.IdempotencyService;
import com.notificationengine.service.channel.ChannelDispatcher;
import com.notificationengine.service.channel.ChannelRouter;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

/**
 * Kafka consumers for notification processing.
 *
 * Two separate consumer groups with different thread pools:
 *  - High-priority: 6 concurrent threads (fast processing)
 *  - Low-priority:  3 concurrent threads (cost-efficient)
 *
 * Processing pipeline per message:
 *  1. Idempotency check (Redis) → skip if duplicate
 *  2. Transition status: QUEUED → DISPATCHED
 *  3. Route to channel dispatcher (Email/SMS/Push)
 *  4. On success: transition DISPATCHED → DELIVERED
 *  5. On failure: exponential backoff retry or DLQ
 *  6. Manual ACK to Kafka
 */
@Component
@Slf4j
public class NotificationConsumer {

    private final IdempotencyService idempotencyService;
    private final DeliveryStatusService deliveryStatusService;
    private final ChannelRouter channelRouter;
    private final RetryService retryService;
    private final Counter processedCounter;
    private final Counter skippedCounter;
    private final Timer processingTimer;

    public NotificationConsumer(
            IdempotencyService idempotencyService,
            DeliveryStatusService deliveryStatusService,
            ChannelRouter channelRouter,
            RetryService retryService,
            MeterRegistry meterRegistry) {
        this.idempotencyService = idempotencyService;
        this.deliveryStatusService = deliveryStatusService;
        this.channelRouter = channelRouter;
        this.retryService = retryService;
        this.processedCounter = Counter.builder("notification.consumer.processed").register(meterRegistry);
        this.skippedCounter = Counter.builder("notification.consumer.skipped").register(meterRegistry);
        this.processingTimer = Timer.builder("notification.consumer.processing.time").register(meterRegistry);
    }

    // ─── High-priority consumer (6 threads) ─────────────────

    @KafkaListener(
            topics = "${notification.kafka.topics.high-priority}",
            containerFactory = "highPriorityContainerFactory"
    )
    public void consumeHighPriority(ConsumerRecord<String, NotificationEvent> record,
                                     Acknowledgment ack) {
        processNotification(record, ack, "HIGH");
    }

    // ─── Low-priority consumer (3 threads) ──────────────────

    @KafkaListener(
            topics = "${notification.kafka.topics.low-priority}",
            containerFactory = "lowPriorityContainerFactory"
    )
    public void consumeLowPriority(ConsumerRecord<String, NotificationEvent> record,
                                    Acknowledgment ack) {
        processNotification(record, ack, "LOW");
    }

    // ─── DLQ consumer (manual reprocessing) ─────────────────

    @KafkaListener(
            topics = "${notification.kafka.topics.dlq}",
            containerFactory = "dlqContainerFactory"
    )
    public void consumeDlq(ConsumerRecord<String, NotificationEvent> record,
                            Acknowledgment ack) {
        NotificationEvent event = record.value();
        log.warn("[DLQ] Received failed notification: id={}, channel={}, retries={}, recipient={}",
                event.getNotificationId(), event.getChannel(),
                event.getRetryCount(), event.getRecipient());

        // DLQ messages are logged and acknowledged.
        // Manual reprocessing available via admin API.
        ack.acknowledge();
    }

    // ─── Core processing pipeline ───────────────────────────

    private void processNotification(ConsumerRecord<String, NotificationEvent> record,
                                      Acknowledgment ack, String priority) {
        NotificationEvent event = record.value();
        long startTime = System.nanoTime();

        log.info("[{}] Processing notification: id={}, channel={}, recipient={}, retry={}",
                priority, event.getNotificationId(), event.getChannel(),
                event.getRecipient(), event.getRetryCount());

        try {
            // Step 1: Idempotency check
            if (!idempotencyService.tryAcquire(event.getIdempotencyKey())) {
                log.info("Duplicate notification skipped: {}", event.getIdempotencyKey());
                skippedCounter.increment();
                ack.acknowledge();
                return;
            }

            // Step 2: Transition QUEUED → DISPATCHED
            deliveryStatusService.transition(
                    event.getNotificationId(),
                    NotificationStatus.DISPATCHED,
                    "Dispatching via " + event.getChannel()
            );

            // Step 3: Route to channel and dispatch
            ChannelDispatcher dispatcher = channelRouter.getDispatcher(event.getChannel());
            dispatcher.dispatch(event);

            // Step 4: Success → DISPATCHED → DELIVERED
            deliveryStatusService.transition(
                    event.getNotificationId(),
                    NotificationStatus.DELIVERED,
                    "Successfully delivered via " + event.getChannel()
            );

            // Step 5: Mark idempotency as completed
            idempotencyService.markCompleted(event.getIdempotencyKey(), event.getNotificationId());

            processedCounter.increment();
            log.info("[{}] Notification delivered: id={}, channel={}, recipient={}",
                    priority, event.getNotificationId(), event.getChannel(), event.getRecipient());

        } catch (DispatchFailedException e) {
            handleDispatchFailure(event, e);
        } catch (Exception e) {
            log.error("Unexpected error processing notification: {}", event.getNotificationId(), e);
            handleDispatchFailure(event,
                    new DispatchFailedException("Unexpected error: " + e.getMessage(), e, true));
        } finally {
            // Always ACK — retry is handled by our own retry mechanism, not Kafka's
            ack.acknowledge();
            processingTimer.record(System.nanoTime() - startTime, java.util.concurrent.TimeUnit.NANOSECONDS);
        }
    }

    private void handleDispatchFailure(NotificationEvent event, DispatchFailedException e) {
        log.error("Dispatch failed for notification {}: {} (retryable={})",
                event.getNotificationId(), e.getMessage(), e.isRetryable());

        // Release idempotency lock so retry can re-acquire
        idempotencyService.release(event.getIdempotencyKey());

        if (e.isRetryable()) {
            retryService.handleFailure(event, e.getMessage());
        } else {
            // Non-retryable: go directly to FAILED
            deliveryStatusService.transition(
                    event.getNotificationId(),
                    NotificationStatus.FAILED,
                    "Non-retryable failure: " + e.getMessage()
            );
        }
    }
}
