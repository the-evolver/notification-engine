package com.notificationengine.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * Redis-backed idempotency service.
 * Prevents duplicate notification sends during consumer retries.
 *
 * Key format: idemp:{idempotencyKey}
 * Value: notification ID (or "processing")
 * TTL: configurable (default 24h)
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class IdempotencyService {

    private static final String KEY_PREFIX = "idemp:";
    private static final String PROCESSING = "processing";

    private final StringRedisTemplate redisTemplate;

    @Value("${notification.idempotency.ttl-hours}")
    private int ttlHours;

    /**
     * Try to acquire the idempotency lock.
     * Returns true if this is the first time we've seen this key (proceed with send).
     * Returns false if already processed (skip the send).
     */
    public boolean tryAcquire(String idempotencyKey) {
        String key = KEY_PREFIX + idempotencyKey;
        Boolean acquired = redisTemplate.opsForValue()
                .setIfAbsent(key, PROCESSING, Duration.ofHours(ttlHours));
        if (Boolean.TRUE.equals(acquired)) {
            log.debug("Idempotency lock acquired for key: {}", idempotencyKey);
            return true;
        }
        log.info("Duplicate detected, skipping key: {}", idempotencyKey);
        return false;
    }

    /**
     * Mark key as successfully processed with the notification ID.
     */
    public void markCompleted(String idempotencyKey, Long notificationId) {
        String key = KEY_PREFIX + idempotencyKey;
        redisTemplate.opsForValue().set(key, String.valueOf(notificationId), Duration.ofHours(ttlHours));
        log.debug("Idempotency key marked completed: {} -> {}", idempotencyKey, notificationId);
    }

    /**
     * Release the lock on failure so it can be retried.
     */
    public void release(String idempotencyKey) {
        String key = KEY_PREFIX + idempotencyKey;
        redisTemplate.delete(key);
        log.debug("Idempotency key released: {}", idempotencyKey);
    }

    /**
     * Check if a key has already been processed.
     */
    public boolean isProcessed(String idempotencyKey) {
        String key = KEY_PREFIX + idempotencyKey;
        String value = redisTemplate.opsForValue().get(key);
        return value != null && !PROCESSING.equals(value);
    }
}
