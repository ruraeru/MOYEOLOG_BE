package com.moyeolog.moyelog_BE.dto;

import lombok.*;
import java.util.UUID;
import java.util.List;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MemoRequest {
    private String title;
    private String content;
    private String imageUrl;
    private UUID groupId;
    private List<String> tags;
    private List<UUID> taggedMemoIds;
    private List<UUID> taggedScheduleIds;
}
