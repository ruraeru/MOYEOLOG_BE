package com.moyeolog.moyelog_BE.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.*;
import java.util.UUID;
import java.util.List;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MemoRequest {
    @NotBlank(message = "메모 제목은 필수입니다.")
    private String title;
    
    @NotBlank(message = "메모 내용은 필수입니다.")
    private String content;
    
    private String imageUrl;
    
    private UUID groupId;
    
    private List<String> tags;
    private List<UUID> taggedMemoIds;
    private List<UUID> taggedScheduleIds;
}
