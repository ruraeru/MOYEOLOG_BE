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
    private UUID authorId;
    private String authorNickname;
    private String authorProfileImage;
    private UUID lastModifierId;
    private String lastModifierNickname;
    private UUID groupId;
    private List<String> tags;
    private MemoInsightResponse insight;
    private Boolean isFavorite;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
