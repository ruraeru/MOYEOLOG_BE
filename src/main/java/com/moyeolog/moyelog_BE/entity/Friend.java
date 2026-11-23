package com.moyeolog.moyelog_BE.entity;

import com.moyeolog.moyelog_BE.enums.FriendStatus;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "friends", uniqueConstraints = {
        // 두 사람 쌍당 관계는 하나. 요청 방향과 상관없이 (작은 id, 큰 id) 로 정규화한다.
        @UniqueConstraint(name = "uk_friends_pair", columnNames = {"user_low_id", "user_high_id"})
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class Friend {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(columnDefinition = "BINARY(16)")
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "requester_id", nullable = false)
    private User requester;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "receiver_id", nullable = false)
    private User receiver;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private FriendStatus status;

    @Column(name = "user_low_id", columnDefinition = "BINARY(16)", nullable = false, updatable = false)
    private UUID userLowId;

    @Column(name = "user_high_id", columnDefinition = "BINARY(16)", nullable = false, updatable = false)
    private UUID userHighId;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        UUID a = requester.getId();
        UUID b = receiver.getId();
        boolean aFirst = compareAsBytes(a, b) <= 0;
        userLowId = aFirst ? a : b;
        userHighId = aFirst ? b : a;
    }

    /** MySQL BINARY(16) 비교(LEAST/GREATEST)와 같은 순서: 바이트 단위 부호 없는 비교. */
    public static int compareAsBytes(UUID a, UUID b) {
        int high = Long.compareUnsigned(a.getMostSignificantBits(), b.getMostSignificantBits());
        return high != 0 ? high : Long.compareUnsigned(a.getLeastSignificantBits(), b.getLeastSignificantBits());
    }

    public boolean involves(UUID userId) {
        return requester.getId().equals(userId) || receiver.getId().equals(userId);
    }

    public void accept() {
        this.status = FriendStatus.ACCEPTED;
    }
}
