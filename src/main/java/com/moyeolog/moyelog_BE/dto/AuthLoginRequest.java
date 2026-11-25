package com.moyeolog.moyelog_BE.dto;

import lombok.*;

/** POST /api/auth/login (프론트 서버만 부른다). 형식 검사는 서비스가 하고, 어떤 실패든 같은 401 이다. */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AuthLoginRequest {
    private String email;
    private String password;
    /** 프론트 서버가 넣는 사용자 IP(nginx 의 X-Real-IP). 없으면 'unknown' 한 묶음으로 센다. */
    private String clientIp;

    /** 비밀번호가 로그에 남지 않게 한다. */
    @Override
    public String toString() {
        return "AuthLoginRequest(email=" + email + ", clientIp=" + clientIp + ")";
    }
}
