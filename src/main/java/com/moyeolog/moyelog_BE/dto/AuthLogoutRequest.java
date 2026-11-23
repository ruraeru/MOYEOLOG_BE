package com.moyeolog.moyelog_BE.dto;

import lombok.*;

/** POST /api/auth/logout (서버 간 호출). 둘 다 선택이며, 있는 것만 폐기한다. */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AuthLogoutRequest {
    private String refreshToken;
    private String accessToken;
}
