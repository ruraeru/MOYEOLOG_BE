package com.moyeolog.moyelog_BE.dto;

import com.moyeolog.moyelog_BE.validation.CodePointSize;
import lombok.*;

/**
 * PUT /api/users/me 의 user 파트. 보낸(null 이 아닌) 필드만 바꾼다.
 * 닉네임 규칙(앞뒤 공백 제거 후 2~20자, 제어·보이지 않는 문자 금지)은 UserService 에서 검사한다.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserUpdateRequest {
    private String nickname;

    @CodePointSize(max = 200, message = "자기소개는 200자까지 쓸 수 있어요.")
    private String bio;

    private Boolean emailVisible;
}
