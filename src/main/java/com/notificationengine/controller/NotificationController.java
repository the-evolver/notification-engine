package com.notificationengine.controller;

import com.notificationengine.model.dto.BatchNotificationRequest;
import com.notificationengine.model.dto.NotificationRequest;
import com.notificationengine.model.dto.NotificationResponse;
import com.notificationengine.service.NotificationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/notifications")
@RequiredArgsConstructor
@Slf4j
public class NotificationController {

    private final NotificationService notificationService;

    // ─── Send single notification ───────────────────────────

    @PostMapping
    public ResponseEntity<NotificationResponse> send(@Valid @RequestBody NotificationRequest request) {
        log.info("POST /api/v1/notifications | key={}, channel={}, priority={}, recipient={}",
                request.getIdempotencyKey(), request.getChannel(),
                request.getPriority(), request.getRecipient());

        NotificationResponse response = notificationService.sendNotification(request);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(response);
    }

    // ─── Send batch notifications ───────────────────────────

    @PostMapping("/batch")
    public ResponseEntity<List<NotificationResponse>> sendBatch(
            @Valid @RequestBody BatchNotificationRequest batchRequest) {
        log.info("POST /api/v1/notifications/batch | count={}",
                batchRequest.getNotifications().size());

        List<NotificationResponse> responses = notificationService.sendBatch(batchRequest);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(responses);
    }

    // ─── Get notification status by ID ──────────────────────

    @GetMapping("/{id}")
    public ResponseEntity<NotificationResponse> getById(@PathVariable Long id) {
        NotificationResponse response = notificationService.getNotificationStatus(id);
        return ResponseEntity.ok(response);
    }

    // ─── Get notification status by idempotency key ─────────

    @GetMapping("/key/{idempotencyKey}")
    public ResponseEntity<NotificationResponse> getByKey(@PathVariable String idempotencyKey) {
        NotificationResponse response = notificationService.getNotificationByKey(idempotencyKey);
        return ResponseEntity.ok(response);
    }

    // ─── List notifications for a user ──────────────────────

    @GetMapping("/user/{userId}")
    public ResponseEntity<Page<NotificationResponse>> getByUser(
            @PathVariable String userId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Page<NotificationResponse> responses = notificationService.getNotificationsByUser(
                userId, PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt")));
        return ResponseEntity.ok(responses);
    }

    // ─── Aggregated stats ───────────────────────────────────

    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> getStats() {
        return ResponseEntity.ok(notificationService.getStats());
    }
}
