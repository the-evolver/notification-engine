package com.notificationengine.service.channel;

import com.notificationengine.exception.DispatchFailedException;
import com.notificationengine.model.dto.NotificationEvent;
import com.notificationengine.model.enums.NotificationChannel;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Random;
import java.util.concurrent.TimeUnit;

/**
 * Push notification channel dispatcher.
 * In production, integrates with FCM/APNs.
 */
@Component
@Slf4j
public class PushDispatcher implements ChannelDispatcher {

    private final boolean enabled;
    private final Counter successCounter;
    private final Counter failureCounter;
    private final Timer dispatchTimer;
    private final Random random = new Random();

    public PushDispatcher(
            @Value("${notification.channels.push.enabled}") boolean enabled,
            MeterRegistry meterRegistry) {
        this.enabled = enabled;
        this.successCounter = Counter.builder("notification.dispatch.success")
                .tag("channel", "PUSH").register(meterRegistry);
        this.failureCounter = Counter.builder("notification.dispatch.failure")
                .tag("channel", "PUSH").register(meterRegistry);
        this.dispatchTimer = Timer.builder("notification.dispatch.duration")
                .tag("channel", "PUSH").register(meterRegistry);
    }

    @Override
    public NotificationChannel getChannel() {
        return NotificationChannel.PUSH;
    }

    @Override
    public void dispatch(NotificationEvent event) {
        long start = System.nanoTime();

        try {
            log.info("[PUSH] Sending to device: {} | NotificationId: {}",
                    event.getRecipient(), event.getNotificationId());

            // Simulate FCM latency (30-100ms)
            Thread.sleep(30 + random.nextInt(70));

            // Simulate 2% failure rate (invalid device tokens, etc.)
            if (random.nextInt(100) < 2) {
                throw new RuntimeException("Invalid device token");
            }

            log.info("[PUSH] Successfully sent to device: {} | NotificationId: {}",
                    event.getRecipient(), event.getNotificationId());
            successCounter.increment();

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            failureCounter.increment();
            throw new DispatchFailedException("Push dispatch interrupted", e, true);
        } catch (RuntimeException e) {
            failureCounter.increment();
            throw new DispatchFailedException("Push dispatch failed: " + e.getMessage(), e, true);
        } finally {
            dispatchTimer.record(System.nanoTime() - start, TimeUnit.NANOSECONDS);
        }
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }
}
