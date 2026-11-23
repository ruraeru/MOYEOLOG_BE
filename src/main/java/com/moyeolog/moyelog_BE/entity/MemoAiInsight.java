package com.moyeolog.moyelog_BE.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

@Entity
@Table(name = "memo_ai_insights")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class MemoAiInsight {

    @Id
    @Column(name = "memo_id", columnDefinition = "BINARY(16)")
    private UUID memoId;

    @OneToOne(fetch = FetchType.LAZY)
    @MapsId
    @JoinColumn(name = "memo_id")
    private Memo memo;

    @Column(name = "ocr_text", columnDefinition = "MEDIUMTEXT")
    private String ocrText;

    @Column(columnDefinition = "TEXT")
    private String summary;

    @Convert(converter = StringListConverter.class)
    @Column(columnDefinition = "JSON")
    private List<String> keywords;

    @Column(name = "analyzed_at")
    private LocalDateTime analyzedAt;

    /** 분석에 쓴 본문·이미지 주소의 SHA-256. 지금 내용과 다르면 결과가 오래된 것(stale). 예전 행은 null. */
    @Column(name = "source_hash", length = 64)
    private String sourceHash;

    @PrePersist
    protected void onAnalyze() {
        analyzedAt = LocalDateTime.now();
    }

    public void update(String ocrText, String summary, List<String> keywords, String sourceHash) {
        this.ocrText = ocrText;
        this.summary = summary;
        this.keywords = keywords;
        this.sourceHash = sourceHash;
        this.analyzedAt = LocalDateTime.now();
    }

    @Converter
    public static class StringListConverter implements AttributeConverter<List<String>, String> {
        private static final ObjectMapper mapper = new ObjectMapper();

        @Override
        public String convertToDatabaseColumn(List<String> attribute) {
            if (attribute == null) {
                return "[]";
            }
            try {
                return mapper.writeValueAsString(attribute);
            } catch (Exception e) {
                return "[]";
            }
        }

        @Override
        public List<String> convertToEntityAttribute(String dbData) {
            if (dbData == null || dbData.isBlank()) {
                return List.of();
            }
            try {
                List<String> values = mapper.readValue(dbData, new TypeReference<List<String>>() {});
                return values != null ? values : List.of();
            } catch (Exception e) {
                return List.of();
            }
        }
    }
}
