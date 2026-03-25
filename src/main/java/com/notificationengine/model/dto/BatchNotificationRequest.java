package com.notificationengine.model.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.*;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BatchNotificationRequest {

    @NotEmpty(message = "At least one notification is required")
    @Size(max = 1000, message = "Maximum 1000 notifications per batch")
    @Valid
    private List<NotificationRequest> notifications;
}
