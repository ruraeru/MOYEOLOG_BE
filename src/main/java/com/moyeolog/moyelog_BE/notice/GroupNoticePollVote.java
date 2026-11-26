package com.moyeolog.moyelog_BE.notice;

import com.moyeolog.moyelog_BE.entity.User;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 한 사람이 고른 선택지 하나. (선택지, 사용자) 유일. 복수 선택이면 한 사람이 여러 줄.
 * poll 은 같은 투표의 줄을 한 번에 읽고 지우려고 둔다(option.poll 과 같다).
 */
@Entity
@Table(name = "group_notice_poll_votes",
        uniqueConstraints = @UniqueConstraint(name = "uk_group_notice_poll_vote", columnNames = {"option_id", "user_id"}),
        indexes = @Index(name = "idx_group_notice_poll_vote_poll", columnList = "poll_id"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class GroupNoticePollVote {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(columnDefinition = "BINARY(16)")
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "poll_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private GroupNoticePoll poll;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "option_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private GroupNoticePollOption option;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "voted_at", nullable = false)
    private LocalDateTime votedAt;

    @PrePersist
    protected void onCreate() {
        if (votedAt == null) votedAt = LocalDateTime.now();
    }
}
