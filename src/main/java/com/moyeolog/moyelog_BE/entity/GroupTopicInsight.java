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

    /** 분석에 쓴 본문·이미지 주소의 SHA-256. 지금 내용과 다르면 결과가 오래된 것(stale). 예전 행은 null. */
    @Column(name = "source_hash", length = 64)
    private String sourceHash;

    @PrePersist
    protected void onCreate() {
        analyzedAt = LocalDateTime.now();
    }

    public void update(String ocrText, String summary, List<String> keywords, String sourceHash) {
        this.ocrText = ocrText;
        this.summary = summary;
        this.keywords = keywords;
        this.sourceHash = sourceHash;
        this.analyzedAt = LocalDateTime.now();
    }
}
