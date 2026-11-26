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
    /** 작성자 customId (사칭 구분용, 없으면 null) */
    private String authorCustomId;
    private String authorProfileImage;
    private UUID groupId;
    private int commentCount;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    /** 고정한 시각(없으면 null). 고정은 모임 전체에 보인다 */
    private LocalDateTime pinnedAt;
    /** 보는 사람이 고정·해제할 수 있는가 (작성자 또는 방장) */
    private boolean canPin;
    /** 보는 사람이 지울 수 있는가 (작성자 또는 방장) */
    private boolean canDelete;
}
