package com.notificationengine.repository;

import com.notificationengine.model.entity.Notification;
import com.notificationengine.model.enums.NotificationChannel;
import com.notificationengine.model.enums.NotificationStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface NotificationRepository extends JpaRepository<Notification, Long> {

    Optional<Notification> findByIdempotencyKey(String idempotencyKey);

    boolean existsByIdempotencyKey(String idempotencyKey);

    Page<Notification> findByUserId(String userId, Pageable pageable);

    Page<Notification> findByUserIdAndStatus(String userId, NotificationStatus status, Pageable pageable);

    Page<Notification> findByUserIdAndChannel(String userId, NotificationChannel channel, Pageable pageable);

    List<Notification> findByStatus(NotificationStatus status);

    @Query("SELECT n FROM Notification n WHERE n.status = :status AND n.nextRetryAt <= :now")
    List<Notification> findRetryableNotifications(
            @Param("status") NotificationStatus status,
            @Param("now") LocalDateTime now
    );

    @Modifying
    @Query("UPDATE Notification n SET n.status = :newStatus, n.updatedAt = :now " +
           "WHERE n.id = :id AND n.status = :currentStatus")
    int updateStatusAtomically(
            @Param("id") Long id,
            @Param("currentStatus") NotificationStatus currentStatus,
            @Param("newStatus") NotificationStatus newStatus,
            @Param("now") LocalDateTime now
    );

    @Query("SELECT n.status, COUNT(n) FROM Notification n GROUP BY n.status")
    List<Object[]> countByStatusGrouped();

    @Query("SELECT n.channel, n.status, COUNT(n) FROM Notification n GROUP BY n.channel, n.status")
    List<Object[]> countByChannelAndStatusGrouped();

    @Query("SELECT COUNT(n) FROM Notification n WHERE n.createdAt >= :since")
    long countCreatedSince(@Param("since") LocalDateTime since);
}
