package com.moyeolog.moyelog_BE.dto;

import lombok.*;

import java.time.LocalDateTime;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class GroupTopicResponse {
    private UUID id;
    private String title;
    private String content;
    private String imageUrl;
    private UUID authorId;
    private String authorNickname;
    private String authorProfileImage;
    private int commentCount;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
