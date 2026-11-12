package com.moyeolog.moyelog_BE.dto;

import tools.jackson.databind.annotation.JsonDeserialize;
import com.moyeolog.moyelog_BE.config.LenientLocalDateTimeDeserializer;
import jakarta.validation.constraints.Size;
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
    @Size(max = 255, message = "일정 제목은 255자를 초과할 수 없습니다.")
    private String title;
    
    @Size(max = 5000, message = "일정 설명은 5,000자를 초과할 수 없습니다.")
    private String description;
    
    // 오프셋 없는 한국 시간(Asia/Seoul) 벽시계 값: yyyy-MM-dd'T'HH:mm:ss
    // 예전 형식(...Z, +09:00 등 오프셋 포함)은 Asia/Seoul 시각으로 변환해서 받는다.
    @NotNull(message = "시작 시간은 필수입니다.")
    @JsonDeserialize(using = LenientLocalDateTimeDeserializer.class)
    private LocalDateTime startTime;
    
    @NotNull(message = "종료 시간은 필수입니다.")
    @JsonDeserialize(using = LenientLocalDateTimeDeserializer.class)
    private LocalDateTime endTime;
    
    @Size(max = 255, message = "장소는 255자를 초과할 수 없습니다.")
    private String location;
    
    private UUID groupId;
    
    private List<UUID> taggedMemoIds;
    private List<UUID> participantIds;
}
