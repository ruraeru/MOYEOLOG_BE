package com.moyeolog.moyelog_BE.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "memo_shares", uniqueConstraints = {
        @UniqueConstraint(name = "uk_memo_shares_memo_user", columnNames = {"memo_id", "shared_to_id"})
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class MemoShare {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(columnDefinition = "BINARY(16)")
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "memo_id", nullable = false)
    private Memo memo;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "shared_to_id", nullable = false)
    private User sharedTo;

    @Column(name = "shared_at", updatable = false)
    private LocalDateTime sharedAt;

    /** 공유받은 사람이 메모 내용을 고칠 수 있는가 (기본 읽기 전용). 기존 행은 DEFAULT FALSE. */
    @Builder.Default
    @Column(name = "can_edit", nullable = false, columnDefinition = "BOOLEAN DEFAULT FALSE")
    private boolean canEdit = false;

    @PrePersist
    protected void onShare() {
        sharedAt = LocalDateTime.now();
    }

    public void changeCanEdit(boolean canEdit) {
        this.canEdit = canEdit;
    }
}
