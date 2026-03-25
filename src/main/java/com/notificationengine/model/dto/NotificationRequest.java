package com.notificationengine.model.dto;

import com.notificationengine.model.enums.NotificationChannel;
import com.notificationengine.model.enums.Priority;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.*;

import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NotificationRequest {

    @NotBlank(message = "Idempotency key is required")
    private String idempotencyKey;

    @NotBlank(message = "User ID is required")
    private String userId;

    @NotNull(message = "Channel is required")
    private NotificationChannel channel;

    @Builder.Default
    private Priority priority = Priority.LOW;

    /** Either provide templateCode + templateParams, or raw subject + body */
    private String templateCode;
    private Map<String, String> templateParams;

    private String subject;
    private String body;

    @NotBlank(message = "Recipient is required")
    private String recipient;

    private Map<String, String> metadata;
}
