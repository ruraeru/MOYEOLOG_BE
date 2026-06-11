package com.moyeolog.moyelog_BE.dto;

import com.moyeolog.moyelog_BE.entity.User;
import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AuthSyncResponse {
    private String accessToken;
    private String refreshToken;
    private User user;
}
