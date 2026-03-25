package com.notificationengine.kafka.producer;

import com.notificationengine.model.dto.NotificationEvent;
import com.notificationengine.model.enums.Priority;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;

/**
 * Publishes notification events to priority-tiered Kafka topics.
 *
 * HIGH priority → notifications.high-priority (6 partitions, 6 consumers)
 * LOW priority  → notifications.low-priority  (3 partitions, 3 consumers)
 *
 * Partition key = userId (ensures ordered delivery per user).
 */
@Component
@Slf4j
public class NotificationProducer {

    @Value("${notification.kafka.topics.high-priority}")
    private String highPriorityTopic;

    @Value("${notification.kafka.topics.low-priority}")
    private String lowPriorityTopic;

    private final KafkaTemplate<String, NotificationEvent> kafkaTemplate;
    private final Counter publishedCounter;

    public NotificationProducer(
            KafkaTemplate<String, NotificationEvent> kafkaTemplate,
            MeterRegistry meterRegistry) {
        this.kafkaTemplate = kafkaTemplate;
        this.publishedCounter = Counter.builder("notification.kafka.published")
                .register(meterRegistry);
    }

    /**
     * Publish event to the appropriate priority topic.
     * Uses userId as partition key for ordered delivery per user.
     */
    public CompletableFuture<SendResult<String, NotificationEvent>> publish(NotificationEvent event) {
        String topic = event.getPriority() == Priority.HIGH ? highPriorityTopic : lowPriorityTopic;
        String partitionKey = event.getUserId(); // Ordered delivery per user

        log.info("Publishing notification {} to topic '{}' | channel={} | recipient={}",
                event.getNotificationId(), topic, event.getChannel(), event.getRecipient());

        CompletableFuture<SendResult<String, NotificationEvent>> future =
                kafkaTemplate.send(topic, partitionKey, event);

        future.whenComplete((result, ex) -> {
            if (ex != null) {
                log.error("Failed to publish notification {} to Kafka",
                        event.getNotificationId(), ex);
            } else {
                log.debug("Published notification {} to partition {} offset {}",
                        event.getNotificationId(),
                        result.getRecordMetadata().partition(),
                        result.getRecordMetadata().offset());
                publishedCounter.increment();
            }
        });

        return future;
    }
}
