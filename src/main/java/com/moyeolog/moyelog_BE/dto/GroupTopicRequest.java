package com.moyeolog.moyelog_BE.dto;

import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.NotBlank;
import lombok.*;

import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class GroupTopicRequest {
    @NotBlank(message = "토픽 제목은 필수입니다.")
    @Size(max = 255, message = "토픽 제목은 255자를 초과할 수 없습니다.")
    private String title;

    @NotBlank(message = "토픽 내용은 필수입니다.")
    @Size(max = 100000, message = "토픽 내용은 100,000자를 초과할 수 없습니다.")
    private String content;

    // null, data:image/(png|jpeg|gif|webp);base64,... 또는 /uploads/<파일명> 만 허용 (GroupTopicService 에서 검증)
    @Size(max = 10000000, message = "이미지가 너무 큽니다.")
    private String imageUrl;
}
