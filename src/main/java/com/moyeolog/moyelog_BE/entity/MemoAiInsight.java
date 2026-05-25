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

    @Column(name = "ocr_text", columnDefinition = "TEXT")
    private String ocrText;

    @Column(columnDefinition = "TEXT")
    private String summary;

    @Column(length = 50)
    private String emotion;

    @Convert(converter = StringListConverter.class)
    @Column(columnDefinition = "JSON")
    private List<String> keywords;

    @Column(name = "analyzed_at")
    private LocalDateTime analyzedAt;

    @PrePersist
    protected void onAnalyze() {
        analyzedAt = LocalDateTime.now();
    }

    public void update(String ocrText, String summary, String emotion, List<String> keywords) {
        this.ocrText = ocrText;
        this.summary = summary;
        this.emotion = emotion;
        this.keywords = keywords;
        this.analyzedAt = LocalDateTime.now();
    }

    @Converter
    public static class StringListConverter implements AttributeConverter<List<String>, String> {
        private static final ObjectMapper mapper = new ObjectMapper();

        @Override
        public String convertToDatabaseColumn(List<String> attribute) {
            try {
                return mapper.writeValueAsString(attribute);
            } catch (Exception e) {
                return "[]";
            }
        }

        @Override
        public List<String> convertToEntityAttribute(String dbData) {
            try {
                return mapper.readValue(dbData, new TypeReference<List<String>>() {});
            } catch (Exception e) {
                return List.of();
            }
        }
    }
}
