package com.moyeolog.moyelog_BE.notice;

import com.moyeolog.moyelog_BE.entity.Group;
import com.moyeolog.moyelog_BE.entity.User;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 모임 공지. 방장만 쓰고 고치고 지운다. 내용은 마크다운.
 * 모임이 지워지면 DB 가 함께 지운다(ON DELETE CASCADE). 읽음·투표도 공지를 따라 지워진다.
 * pinnedAt 은 맨 위 고정 시각(없으면 고정 아님). 고정은 updatedAt 을 바꾸지 않는다.
 */
@Entity
@Table(name = "group_notices", indexes = @Index(name = "idx_group_notice_group", columnList = "group_id"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class GroupNotice {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(columnDefinition = "BINARY(16)")
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "group_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private Group group;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "author_id", nullable = false)
    private User author;

    @Column(nullable = false, length = 100)
    private String title;

    @Column(columnDefinition = "LONGTEXT", nullable = false)
    private String content;

    @Column(name = "pinned_at")
    private LocalDateTime pinnedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        if (createdAt == null) createdAt = now;
        if (updatedAt == null) updatedAt = createdAt;
    }

    public void edit(String title, String content) {
        this.title = title;
        this.content = content;
        this.updatedAt = LocalDateTime.now();
    }

    public void changePinnedAt(LocalDateTime pinnedAt) {
        this.pinnedAt = pinnedAt;
    }

    /** 회원 탈퇴로 작성자가 사라질 때 지금 방장에게 넘긴다. */
    public void changeAuthor(User author) {
        this.author = author;
    }
}
