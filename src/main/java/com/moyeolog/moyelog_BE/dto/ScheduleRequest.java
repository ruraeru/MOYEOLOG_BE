package com.moyeolog.moyelog_BE.dto;

import tools.jackson.databind.annotation.JsonDeserialize;
import com.moyeolog.moyelog_BE.config.LenientLocalDateTimeDeserializer;
import com.moyeolog.moyelog_BE.validation.CodePointSize;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
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
    @NotBlank(message = "일정 제목을 입력해 주세요.")
    @CodePointSize(max = 255, message = "일정 제목은 255자까지 쓸 수 있어요.")
    private String title;
    
    @CodePointSize(max = 5000, message = "일정 설명은 5,000자까지 쓸 수 있어요.")
    private String description;
    
    // 오프셋 없는 한국 시간(Asia/Seoul) 벽시계 값: yyyy-MM-dd'T'HH:mm:ss
    // 예전 형식(...Z, +09:00 등 오프셋 포함)은 Asia/Seoul 시각으로 변환해서 받는다.
    @NotNull(message = "시작 시각을 입력해 주세요.")
    @JsonDeserialize(using = LenientLocalDateTimeDeserializer.class)
    private LocalDateTime startTime;
    
    @NotNull(message = "종료 시각을 입력해 주세요.")
    @JsonDeserialize(using = LenientLocalDateTimeDeserializer.class)
    private LocalDateTime endTime;
    
    @CodePointSize(max = 255, message = "장소는 255자까지 쓸 수 있어요.")
    private String location;

    // 지도에서 고른 장소의 좌표. 둘 다 있을 때만 저장한다.
    @DecimalMin(value = "-90", message = "위도가 올바르지 않아요.")
    @DecimalMax(value = "90", message = "위도가 올바르지 않아요.")
    private Double latitude;

    @DecimalMin(value = "-180", message = "경도가 올바르지 않아요.")
    @DecimalMax(value = "180", message = "경도가 올바르지 않아요.")
    private Double longitude;
    
    private UUID groupId;
    
    private List<UUID> taggedMemoIds;
    private List<UUID> participantIds;
}
