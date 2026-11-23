package com.moyeolog.moyelog_BE.dto;

import com.moyeolog.moyelog_BE.validation.CodePointSize;
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
    @NotBlank(message = "토픽 제목을 입력해 주세요.")
    @CodePointSize(max = 255, message = "토픽 제목은 255자까지 쓸 수 있어요.")
    private String title;

    @NotBlank(message = "토픽 내용을 입력해 주세요.")
    @CodePointSize(max = 100000, message = "토픽 내용은 100,000자까지 쓸 수 있어요.")
    private String content;

    // null, data:image/(png|jpeg|gif|webp);base64,... (서버가 /uploads 파일로 저장) 또는 본인이 올린 /uploads/<파일명> 만 허용
    @Size(max = 10000000, message = "이미지가 너무 커요. 10MB 이하로 올려 주세요.")
    private String imageUrl;
}
