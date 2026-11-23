package com.moyeolog.moyelog_BE.dto;

import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AuthSyncResponse {
    private String accessToken;
    private String refreshToken;
    private UserMeResponse user;
}
