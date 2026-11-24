package com.moyeolog.moyelog_BE.dto;

import com.moyeolog.moyelog_BE.validation.CodePointSize;
import jakarta.validation.constraints.NotBlank;
import lombok.*;

/**
 * 모임 만들기·수정 요청.
 * 이름은 trim 후 1~50자(코드 포인트)로 GroupService 에서 검사한다.
 * colorTheme 은 indigo, blue, emerald, orange, rose, amber, purple 중 하나(없으면 indigo).
 * 수정(PUT) 때만: removeProfileImage·removeBackgroundImage 가 true 면 그 사진을 지운다.
 * 같은 요청에 새 파일(image·bgImage)이 있으면 새 파일이 이긴다.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class GroupRequest {
    @NotBlank(message = "모임 이름을 입력해 주세요.")
    private String name;

    @CodePointSize(max = 255, message = "모임 소개는 255자까지 쓸 수 있어요.")
    private String description;

    private String colorTheme;

    /** true 면 프로필 사진을 지운다 (수정 때만, 선택) */
    private Boolean removeProfileImage;

    /** true 면 배경 사진을 지운다 (수정 때만, 선택) */
    private Boolean removeBackgroundImage;
}
