package com.moyeolog.moyelog_BE.dto;

import com.moyeolog.moyelog_BE.entity.User;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

/** GET /api/users/me 응답. kakaoId 는 내보내지 않는다. */
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserMeResponse {
    private UUID id;
    private String customId;
    private String nickname;
    private String email;
    private String profileImage;
    private String bio;
    private boolean emailVisible;
    private LocalDateTime createdAt;

    public static UserMeResponse from(User user) {
        return UserMeResponse.builder()
                .id(user.getId())
                .customId(user.getCustomId())
                .nickname(user.getNickname())
                .email(user.getEmail())
                .profileImage(user.getProfileImage())
                .bio(user.getBio())
                .emailVisible(user.isEmailVisible())
                .createdAt(user.getCreatedAt())
                .build();
    }
}
