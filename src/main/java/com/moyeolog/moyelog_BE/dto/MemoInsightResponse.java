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
    private List<String> keywords;
    private LocalDateTime analyzedAt;
    /** 분석 뒤 메모·토픽의 본문이나 이미지가 바뀌었으면 true */
    private boolean stale;
}
