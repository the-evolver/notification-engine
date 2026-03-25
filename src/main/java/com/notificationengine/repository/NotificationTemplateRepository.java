package com.notificationengine.repository;

import com.notificationengine.model.entity.NotificationTemplate;
import com.notificationengine.model.enums.NotificationChannel;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface NotificationTemplateRepository extends JpaRepository<NotificationTemplate, Long> {

    Optional<NotificationTemplate> findByTemplateCodeAndIsActiveTrue(String templateCode);

    List<NotificationTemplate> findByChannelAndIsActiveTrue(NotificationChannel channel);

    boolean existsByTemplateCode(String templateCode);
}
