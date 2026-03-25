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
 * SMS channel dispatcher.
 * In production, integrates with Twilio/MSG91/Gupshup.
 */
@Component
@Slf4j
public class SmsDispatcher implements ChannelDispatcher {

    private final boolean enabled;
    private final Counter successCounter;
    private final Counter failureCounter;
    private final Timer dispatchTimer;
    private final Random random = new Random();

    public SmsDispatcher(
            @Value("${notification.channels.sms.enabled}") boolean enabled,
            MeterRegistry meterRegistry) {
        this.enabled = enabled;
        this.successCounter = Counter.builder("notification.dispatch.success")
                .tag("channel", "SMS").register(meterRegistry);
        this.failureCounter = Counter.builder("notification.dispatch.failure")
                .tag("channel", "SMS").register(meterRegistry);
        this.dispatchTimer = Timer.builder("notification.dispatch.duration")
                .tag("channel", "SMS").register(meterRegistry);
    }

    @Override
    public NotificationChannel getChannel() {
        return NotificationChannel.SMS;
    }

    @Override
    public void dispatch(NotificationEvent event) {
        long start = System.nanoTime();

        try {
            log.info("[SMS] Sending to: {} | NotificationId: {}",
                    event.getRecipient(), event.getNotificationId());

            // Simulate SMS gateway latency (100-300ms)
            Thread.sleep(100 + random.nextInt(200));

            // Simulate 3% failure rate
            if (random.nextInt(100) < 3) {
                throw new RuntimeException("SMS gateway rate limit exceeded");
            }

            log.info("[SMS] Successfully sent to: {} | NotificationId: {}",
                    event.getRecipient(), event.getNotificationId());
            successCounter.increment();

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            failureCounter.increment();
            throw new DispatchFailedException("SMS dispatch interrupted", e, true);
        } catch (RuntimeException e) {
            failureCounter.increment();
            throw new DispatchFailedException("SMS dispatch failed: " + e.getMessage(), e, true);
        } finally {
            dispatchTimer.record(System.nanoTime() - start, TimeUnit.NANOSECONDS);
        }
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }
}
