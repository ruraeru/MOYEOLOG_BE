package com.moyeolog.moyelog_BE.dto;

import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AuthSyncRequest {
    private String kakaoId;
    private String email;
    private String nickname;
    private String profileImage;
    /** 카카오가 이메일을 유효·인증됨으로 알려 줬을 때만 true (is_email_valid && is_email_verified). */
    private Boolean emailVerified;
}
