package com.moyeolog.moyelog_BE.dto;

import lombok.*;

import java.time.LocalDateTime;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class GroupTopicCommentResponse {
    private UUID id;
    private String content;
    private UUID authorId;
    private String authorNickname;
    /** 작성자 customId (사칭 구분용, 없으면 null) */
    private String authorCustomId;
    private String authorProfileImage;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
