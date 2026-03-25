package com.notificationengine.model.dto;

import com.notificationengine.model.enums.NotificationChannel;
import com.notificationengine.model.enums.NotificationStatus;
import com.notificationengine.model.enums.Priority;
import lombok.*;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NotificationResponse {
    private Long id;
    private String idempotencyKey;
    private String userId;
    private NotificationChannel channel;
    private Priority priority;
    private NotificationStatus status;
    private String recipient;
    private Integer retryCount;
    private String lastError;
    private LocalDateTime dispatchedAt;
    private LocalDateTime deliveredAt;
    private LocalDateTime failedAt;
    private LocalDateTime createdAt;
}
