package com.notificationengine.service.channel;

import com.notificationengine.model.dto.NotificationEvent;
import com.notificationengine.model.enums.NotificationChannel;

/**
 * Strategy interface for multi-channel notification dispatch.
 * Each channel (Email, SMS, Push) implements this interface.
 */
public interface ChannelDispatcher {

    /**
     * @return The channel this dispatcher handles.
     */
    NotificationChannel getChannel();

    /**
     * Send the notification via this channel.
     * Should throw DispatchFailedException on failure.
     */
    void dispatch(NotificationEvent event);

    /**
     * @return Whether this channel is currently enabled.
     */
    boolean isEnabled();
}
