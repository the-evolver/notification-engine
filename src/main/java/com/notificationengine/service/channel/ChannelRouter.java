package com.notificationengine.service.channel;

import com.notificationengine.model.enums.NotificationChannel;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Routes notification events to the correct channel dispatcher.
 * Uses strategy pattern — each channel registers itself at startup.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ChannelRouter {

    private final List<ChannelDispatcher> dispatchers;
    private final Map<NotificationChannel, ChannelDispatcher> dispatcherMap = new EnumMap<>(NotificationChannel.class);

    @PostConstruct
    public void init() {
        for (ChannelDispatcher dispatcher : dispatchers) {
            dispatcherMap.put(dispatcher.getChannel(), dispatcher);
            log.info("Registered channel dispatcher: {} (enabled={})",
                    dispatcher.getChannel(), dispatcher.isEnabled());
        }
    }

    public ChannelDispatcher getDispatcher(NotificationChannel channel) {
        ChannelDispatcher dispatcher = dispatcherMap.get(channel);
        if (dispatcher == null) {
            throw new IllegalArgumentException("No dispatcher registered for channel: " + channel);
        }
        if (!dispatcher.isEnabled()) {
            throw new IllegalStateException("Channel is disabled: " + channel);
        }
        return dispatcher;
    }
}
