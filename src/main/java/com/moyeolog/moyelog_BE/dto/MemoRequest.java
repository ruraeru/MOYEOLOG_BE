package com.moyeolog.moyelog_BE.dto;

import com.moyeolog.moyelog_BE.validation.CodePointSize;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.*;
import java.util.UUID;
import java.util.List;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MemoRequest {
    // 글자 수는 코드 포인트 기준 (이모지 1개 = 1자). DB 는 utf8mb4 VARCHAR(255) 라 255 글자까지 들어간다.
    @NotBlank(message = "메모 제목을 입력해 주세요.")
    @CodePointSize(max = 255, message = "메모 제목은 255자까지 쓸 수 있어요.")
    private String title;
    
    @NotBlank(message = "메모 내용을 입력해 주세요.")
    @CodePointSize(max = 100000, message = "메모 내용은 100,000자까지 쓸 수 있어요.")
    private String content;
    
    private String imageUrl;
    
    private UUID groupId;
    
    @Size(max = 50, message = "태그는 50개까지 등록할 수 있어요.")
    private List<@CodePointSize(max = 50, message = "태그는 50자까지 쓸 수 있어요.") String> tags;
    private List<UUID> taggedMemoIds;
    /** null/누락이면 기존 일정 연결을 그대로 둔다. */
    private List<UUID> taggedScheduleIds;

    /** 수정 시 보내면 낙관적 잠금: 서버의 version 과 다르면 409. */
    private Long version;

    /** 수정 시 true 면 첨부 이미지를 지운다 (새 이미지 파트가 있으면 새 이미지가 우선). */
    private Boolean removeImage;
}
