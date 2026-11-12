package com.moyeolog.moyelog_BE.dto;

import jakarta.validation.constraints.Size;
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
    @Size(max = 255, message = "메모 제목은 255자를 초과할 수 없습니다.")
    private String title;
    
    @NotBlank(message = "메모 내용은 필수입니다.")
    @Size(max = 100000, message = "메모 내용은 100,000자를 초과할 수 없습니다.")
    private String content;
    
    private String imageUrl;
    
    private UUID groupId;
    
    @Size(max = 50, message = "태그는 50개까지 등록할 수 있습니다.")
    private List<@Size(max = 50, message = "태그는 50자를 초과할 수 없습니다.") String> tags;
    private List<UUID> taggedMemoIds;
    private List<UUID> taggedScheduleIds;
}
