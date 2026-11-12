package com.moyeolog.moyelog_BE.dto;

import jakarta.validation.constraints.Size;
import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserUpdateRequest {
    @Size(max = 50, message = "닉네임은 50자를 초과할 수 없습니다.")
    private String nickname;

    @Size(max = 1000, message = "소개는 1000자를 초과할 수 없습니다.")
    private String bio;
}
