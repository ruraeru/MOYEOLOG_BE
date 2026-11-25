package com.moyeolog.moyelog_BE.dto;

import lombok.*;

/** DELETE /api/users/me/kakao 본문. 현재 비밀번호로 다시 확인한다. */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class AuthUnlinkKakaoRequest {
    private String currentPassword;

    /** 비밀번호가 로그에 남지 않게 한다. */
    @Override
    public String toString() {
        return "AuthUnlinkKakaoRequest";
    }
}
