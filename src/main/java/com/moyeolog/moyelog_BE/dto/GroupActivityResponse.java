package com.moyeolog.moyelog_BE.dto;

import lombok.*;
import java.time.LocalDateTime;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class GroupActivityResponse {
    private String type; // 'MEMO' | 'TOPIC'
    private UUID groupId;
    private String groupName;
    private UUID id; // Memo or Topic ID
    private String title;
    private String contentSnippet;
    private String authorNickname;
    private String authorProfileImage;
    private LocalDateTime createdAt;
}
