package com.moyeolog.moyelog_BE.message;

import com.moyeolog.moyelog_BE.entity.User;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.ColumnDefault;

import java.time.LocalDateTime;
import java.util.UUID;

/** 친구 사이 1:1 메시지 */
@Entity
@Table(name = "messages", indexes = {
        @Index(name = "idx_messages_recipient_created", columnList = "recipient_id, created_at"),
        @Index(name = "idx_messages_sender_created", columnList = "sender_id, created_at")
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class Message {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(columnDefinition = "BINARY(16)")
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "sender_id", nullable = false)
    private User sender;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "recipient_id", nullable = false)
    private User recipient;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    /** 받는 사람이 읽은 시각. null 이면 안 읽음 */
    @Column(name = "read_at")
    private LocalDateTime readAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /** 보낸 사람이 대화를 지워서 보낸 사람 쪽에서는 안 보인다 (받는 사람 쪽에는 그대로) */
    @Builder.Default
    @ColumnDefault("false")
    @Column(name = "hidden_by_sender", nullable = false)
    private boolean hiddenBySender = false;

    /** 받는 사람이 대화를 지워서 받는 사람 쪽에서는 안 보인다 */
    @Builder.Default
    @ColumnDefault("false")
    @Column(name = "hidden_by_recipient", nullable = false)
    private boolean hiddenByRecipient = false;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) createdAt = LocalDateTime.now();
    }

    public boolean isRead() {
        return readAt != null;
    }
}
