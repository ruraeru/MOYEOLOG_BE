package com.moyeolog.moyelog_BE.notice;

import com.moyeolog.moyelog_BE.entity.User;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 한 사람이 공지를 읽은 기록. (공지, 사용자) 유일.
 * 공지가 지워지면 DB 가 함께 지운다. 모임 나가기·회원 탈퇴 때는 서비스가 지운다.
 */
@Entity
@Table(name = "group_notice_reads",
        uniqueConstraints = @UniqueConstraint(name = "uk_group_notice_read", columnNames = {"notice_id", "user_id"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class GroupNoticeRead {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(columnDefinition = "BINARY(16)")
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "notice_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private GroupNotice notice;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "read_at", nullable = false)
    private LocalDateTime readAt;

    @PrePersist
    protected void onCreate() {
        if (readAt == null) readAt = LocalDateTime.now();
    }
}
