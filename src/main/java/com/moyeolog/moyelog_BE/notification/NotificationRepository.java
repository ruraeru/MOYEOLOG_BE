package com.moyeolog.moyelog_BE.notification;

import com.moyeolog.moyelog_BE.event.NotificationType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface NotificationRepository extends JpaRepository<Notification, UUID> {

    @EntityGraph(attributePaths = "actor")
    List<Notification> findByRecipient_IdOrderByCreatedAtDescIdDesc(UUID recipientId, Pageable pageable);

    Optional<Notification> findByIdAndRecipient_Id(UUID id, UUID recipientId);

    long countByRecipient_IdAndReadAtIsNull(UUID recipientId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE Notification n SET n.readAt = :now WHERE n.recipient.id = :userId AND n.readAt IS NULL")
    int markAllRead(@Param("userId") UUID userId, @Param("now") LocalDateTime now);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("DELETE FROM Notification n WHERE n.recipient.id = :userId OR n.actor.id = :userId")
    int deleteAllInvolving(@Param("userId") UUID userId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("DELETE FROM Notification n WHERE n.recipient.id = :recipientId AND n.actor.id = :actorId AND n.type = :type")
    int deleteByRecipientAndActorAndType(@Param("recipientId") UUID recipientId,
                                         @Param("actorId") UUID actorId,
                                         @Param("type") NotificationType type);
}
