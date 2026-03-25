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
 * Email channel dispatcher.
 * In production, this would integrate with SES/SendGrid/Mailgun.
 * Currently simulates real-world latency and occasional failures.
 */
@Component
@Slf4j
public class EmailDispatcher implements ChannelDispatcher {

    private final boolean enabled;
    private final Counter successCounter;
    private final Counter failureCounter;
    private final Timer dispatchTimer;
    private final Random random = new Random();

    public EmailDispatcher(
            @Value("${notification.channels.email.enabled}") boolean enabled,
            MeterRegistry meterRegistry) {
        this.enabled = enabled;
        this.successCounter = Counter.builder("notification.dispatch.success")
                .tag("channel", "EMAIL").register(meterRegistry);
        this.failureCounter = Counter.builder("notification.dispatch.failure")
                .tag("channel", "EMAIL").register(meterRegistry);
        this.dispatchTimer = Timer.builder("notification.dispatch.duration")
                .tag("channel", "EMAIL").register(meterRegistry);
    }

    @Override
    public NotificationChannel getChannel() {
        return NotificationChannel.EMAIL;
    }

    @Override
    public void dispatch(NotificationEvent event) {
        long start = System.nanoTime();

        try {
            log.info("[EMAIL] Sending to: {} | Subject: {} | NotificationId: {}",
                    event.getRecipient(), event.getSubject(), event.getNotificationId());

            // Simulate SMTP latency (50-200ms)
            Thread.sleep(50 + random.nextInt(150));

            // Simulate 5% failure rate for realism
            if (random.nextInt(100) < 5) {
                throw new RuntimeException("SMTP connection timeout");
            }

            log.info("[EMAIL] Successfully sent to: {} | NotificationId: {}",
                    event.getRecipient(), event.getNotificationId());
            successCounter.increment();

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            failureCounter.increment();
            throw new DispatchFailedException("Email dispatch interrupted", e, true);
        } catch (RuntimeException e) {
            failureCounter.increment();
            throw new DispatchFailedException("Email dispatch failed: " + e.getMessage(), e, true);
        } finally {
            dispatchTimer.record(System.nanoTime() - start, TimeUnit.NANOSECONDS);
        }
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }
}
