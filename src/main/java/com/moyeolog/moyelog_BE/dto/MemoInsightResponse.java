package com.moyeolog.moyelog_BE.dto;

import lombok.*;
import java.time.LocalDateTime;
import java.util.List;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MemoInsightResponse {
    private String ocrText;
    private String summary;
    private String emotion;
    private List<String> keywords;
    private LocalDateTime analyzedAt;
}
