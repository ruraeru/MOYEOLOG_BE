package com.moyeolog.moyelog_BE.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ScheduleRequest {
    @NotBlank(message = "일정 제목은 필수입니다.")
    private String title;
    
    private String description;
    
    @NotNull(message = "시작 시간은 필수입니다.")
    private LocalDateTime startTime;
    
    @NotNull(message = "종료 시간은 필수입니다.")
    private LocalDateTime endTime;
    
    private String location;
    
    private UUID groupId;
    
    private List<UUID> taggedMemoIds;
    private List<UUID> participantIds;
}
