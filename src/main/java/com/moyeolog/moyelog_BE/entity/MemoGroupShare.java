package com.moyeolog.moyelog_BE.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 개인 메모를 작성자가 속한 모임에 공유한 것. 그 모임의 지금 멤버는 읽기만 한다(모임에서 쓴 메모와 다르다).
 * 메모 삭제, 모임 삭제, 작성자가 모임을 나가거나 내보내질 때 함께 지운다.
 */
@Entity
@Table(name = "memo_group_shares", uniqueConstraints = {
        @UniqueConstraint(name = "uk_memo_group_shares_memo_group", columnNames = {"memo_id", "group_id"})
}, indexes = @Index(name = "idx_memo_group_shares_group", columnList = "group_id"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class MemoGroupShare {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(columnDefinition = "BINARY(16)")
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "memo_id", nullable = false)
    private Memo memo;

    @Column(name = "group_id", nullable = false, columnDefinition = "BINARY(16)")
    private UUID groupId;

    @Column(name = "shared_at", updatable = false)
    private LocalDateTime sharedAt;

    @PrePersist
    protected void onShare() {
        sharedAt = LocalDateTime.now();
    }
}
