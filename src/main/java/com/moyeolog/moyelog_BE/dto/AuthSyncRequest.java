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
}
