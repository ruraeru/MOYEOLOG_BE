package com.moyeolog.moyelog_BE.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Entity
@Table(name = "memos")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class Memo {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(columnDefinition = "BINARY(16)")
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "author_id", nullable = false)
    private User author;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "last_modifier_id")
    private User lastModifier;

    @Column(name = "group_id", columnDefinition = "BINARY(16)")
    private UUID groupId;

    @Column(nullable = false)
    private String title;

    // TEXT(64KB)는 한글 약 2만 자에서 넘치므로 MEDIUMTEXT(16MB) 사용
    @Column(columnDefinition = "MEDIUMTEXT")
    private String content;

    @Column(name = "image_url", columnDefinition = "LONGTEXT")
    private String imageUrl;

    // 즐겨찾기는 사용자별 정보이므로 MemoFavorite(memo_favorites) 로 옮겼습니다.
    // 기존 memos.is_favorite 컬럼은 더 이상 매핑하지 않습니다.

    @ManyToMany
    @JoinTable(
        name = "memo_tagged_memos",
        joinColumns = @JoinColumn(name = "memo_id"),
        inverseJoinColumns = @JoinColumn(name = "tagged_memo_id")
    )
    private List<Memo> taggedMemos;

    @ManyToMany(mappedBy = "taggedMemos")
    private List<Schedule> taggedSchedules;

    /**
     * 메모 쪽에서(본문 언급으로) 연결한 일정 ID.
     * 메모를 고칠 때는 여기에 있는 연결만 끊는다. 일정 쪽에서 만든 연결(일정 → 메모)은 메모 수정으로 사라지지 않는다.
     */
    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "memo_schedule_mentions", joinColumns = @JoinColumn(name = "memo_id"))
    @Column(name = "schedule_id", columnDefinition = "BINARY(16)")
    @Builder.Default
    private Set<UUID> mentionedScheduleIds = new HashSet<>();

    /** 낙관적 잠금. 기존 행은 0 으로 채워진다. */
    @Version
    @Column(name = "version", nullable = false, columnDefinition = "BIGINT NOT NULL DEFAULT 0")
    private Long version;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    public void update(String title, String content, String imageUrl, User modifier, List<Memo> taggedMemos) {
        this.title = title;
        this.content = content;
        this.imageUrl = imageUrl;
        this.lastModifier = modifier;
        this.taggedMemos = taggedMemos;
        this.updatedAt = LocalDateTime.now();
    }

    /** 태그처럼 다른 테이블만 바뀌는 수정도 메모 행을 갱신해 version 을 올린다. */
    public void markModified(User modifier) {
        this.lastModifier = modifier;
        this.updatedAt = LocalDateTime.now();
    }

    public void replaceMentionedSchedules(Set<UUID> scheduleIds) {
        if (this.mentionedScheduleIds == null) {
            this.mentionedScheduleIds = new HashSet<>();
        }
        this.mentionedScheduleIds.clear();
        this.mentionedScheduleIds.addAll(scheduleIds);
    }

    /** 그룹이 삭제될 때 그룹 메모를 작성자의 개인 메모로 되돌립니다. */
    public void detachFromGroup() {
        this.groupId = null;
    }
}
