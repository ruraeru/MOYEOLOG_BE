package com.moyeolog.moyelog_BE.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "group_topic_insights")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class GroupTopicInsight {

    @Id
    private UUID topicId;

    @OneToOne(fetch = FetchType.LAZY)
    @MapsId
    @JoinColumn(name = "topic_id")
    private GroupTopic topic;

    @Column(name = "ocr_text", columnDefinition = "LONGTEXT")
    private String ocrText;

    @Column(columnDefinition = "TEXT")
    private String summary;

    @Convert(converter = MemoAiInsight.StringListConverter.class)
    @Column(columnDefinition = "JSON")
    private List<String> keywords;

    @Column(name = "analyzed_at")
    private LocalDateTime analyzedAt;

    @PrePersist
    protected void onCreate() {
        analyzedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        analyzedAt = LocalDateTime.now();
    }

    public void update(String ocrText, String summary, List<String> keywords) {
        this.ocrText = ocrText;
        this.summary = summary;
        this.keywords = keywords;
    }
}
