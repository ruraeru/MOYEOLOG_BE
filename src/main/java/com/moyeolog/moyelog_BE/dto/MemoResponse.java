package com.moyeolog.moyelog_BE.dto;

import lombok.*;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MemoResponse {
    private UUID id;
    private String title;
    private String content;
    private String imageUrl;
    private UUID groupId;
    private List<String> tags;
    private MemoInsightResponse insight;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
