package com.moyeolog.moyelog_BE.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 사용자별 개인 메모 고정. 고정한 본인에게만 보인다. (user, memo) 쌍은 하나만 존재할 수 있습니다.
 * 모임 메모의 고정은 이 표가 아니라 memos.pinned_at(모임 공통)을 쓴다.
 */
@Entity
@Table(name = "memo_pins", uniqueConstraints = {
        @UniqueConstraint(name = "uk_memo_pins_user_memo", columnNames = {"user_id", "memo_id"})
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class MemoPin {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(columnDefinition = "BINARY(16)")
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "memo_id", nullable = false)
    private Memo memo;

    @Column(name = "pinned_at", updatable = false)
    private LocalDateTime pinnedAt;

    @PrePersist
    protected void onCreate() {
        if (pinnedAt == null) {
            pinnedAt = LocalDateTime.now();
        }
    }
}
