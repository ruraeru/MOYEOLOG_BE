package com.moyeolog.moyelog_BE.notice;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 공지에 붙은 투표. 공지 하나에 하나(notice_id 유일). 공지를 만들 때만 붙이고 내용은 바꾸지 않는다.
 * closesAt 은 정해 둔 마감 시각(없으면 방장이 직접 마감), closedAt 은 방장이 마감한 시각.
 */
@Entity
@Table(name = "group_notice_polls",
        uniqueConstraints = @UniqueConstraint(name = "uk_group_notice_poll", columnNames = "notice_id"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class GroupNoticePoll {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(columnDefinition = "BINARY(16)")
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "notice_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private GroupNotice notice;

    @Column(nullable = false)
    private boolean multiple;

    @Column(nullable = false)
    private boolean anonymous;

    @Column(name = "closes_at")
    private LocalDateTime closesAt;

    @Column(name = "closed_at")
    private LocalDateTime closedAt;

    /** 방장의 마감. 이미 닫혀 있으면 처음 시각을 둔다. */
    public void close(LocalDateTime now) {
        if (closedAt == null) closedAt = now;
    }

    public boolean isClosed(LocalDateTime now) {
        return closedAt != null || (closesAt != null && closesAt.isBefore(now));
    }
}
