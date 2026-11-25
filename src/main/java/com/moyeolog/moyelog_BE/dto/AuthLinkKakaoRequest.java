package com.moyeolog.moyelog_BE.dto;

import lombok.*;

/**
 * POST /api/auth/link-kakao (프론트 서버만 부른다).
 * accessToken 은 연결을 요청한, 지금 로그인한 사용자의 액세스 토큰이다. 나머지는 카카오 로그인 결과(sync 와 같은 값).
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AuthLinkKakaoRequest {
    private String accessToken;
    private String kakaoId;
    private String email;
    /** 카카오가 이메일을 유효·인증됨으로 알려 줬을 때만 true. */
    private Boolean emailVerified;
    private String profileImage;

    /** 토큰이 로그에 남지 않게 한다. */
    @Override
    public String toString() {
        return "AuthLinkKakaoRequest(kakaoId=" + kakaoId + ")";
    }
}
