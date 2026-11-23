package com.moyeolog.moyelog_BE.notification;

import com.moyeolog.moyelog_BE.entity.User;
import com.moyeolog.moyelog_BE.event.NotificationType;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.UUID;

/** 사용자에게 보이는 알림 한 건. NotificationEvent 를 받아 커밋 뒤에 저장한다. */
@Entity
@Table(name = "notifications",
        indexes = @Index(name = "idx_notifications_recipient_created", columnList = "recipient_id, created_at"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class Notification {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(columnDefinition = "BINARY(16)")
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "recipient_id", nullable = false)
    private User recipient;

    /** 행동한 사람. 시스템 알림이면 null */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "actor_id")
    private User actor;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private NotificationType type;

    @Column(nullable = false, length = 1000)
    private String message;

    @Column(length = 300)
    private String link;

    /** 읽은 시각. null 이면 안 읽음 */
    @Column(name = "read_at")
    private LocalDateTime readAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) createdAt = LocalDateTime.now();
    }

    public boolean isRead() {
        return readAt != null;
    }

    public void markRead() {
        if (readAt == null) readAt = LocalDateTime.now();
    }
}
