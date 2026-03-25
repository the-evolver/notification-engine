package com.notificationengine.model.dto;

import com.notificationengine.model.enums.NotificationChannel;
import com.notificationengine.model.enums.Priority;
import lombok.*;

import java.io.Serializable;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NotificationEvent implements Serializable {
    private Long notificationId;
    private String idempotencyKey;
    private String userId;
    private NotificationChannel channel;
    private Priority priority;
    private String subject;
    private String body;
    private String recipient;
    private Integer retryCount;
    private Integer maxRetries;
    private LocalDateTime createdAt;
}
