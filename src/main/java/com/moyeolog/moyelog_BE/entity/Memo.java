package com.moyeolog.moyelog_BE.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;
import java.util.List;
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

    @Column(columnDefinition = "TEXT")
    private String content;

    @Column(name = "image_url", columnDefinition = "LONGTEXT")
    private String imageUrl;

    @Column(name = "is_favorite")
    @Builder.Default
    private Boolean isFavorite = false;

    @ManyToMany
    @JoinTable(
        name = "memo_tagged_memos",
        joinColumns = @JoinColumn(name = "memo_id"),
        inverseJoinColumns = @JoinColumn(name = "tagged_memo_id")
    )
    private List<Memo> taggedMemos;

    @ManyToMany(mappedBy = "taggedMemos")
    private List<Schedule> taggedSchedules;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
        if (isFavorite == null) isFavorite = false;
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
    }

    public void toggleFavorite() {
        if (this.isFavorite == null) {
            this.isFavorite = true;
        } else {
            this.isFavorite = !this.isFavorite;
        }
    }
}
