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
    /** 비밀번호 로그인을 쓸 수 있는지 */
    private boolean hasPassword;
    /** 카카오 계정이 연결돼 있는지 (kakaoId 자체는 내보내지 않는다) */
    private boolean kakaoLinked;
    /** email 의 소유가 확인됐는지 */
    private boolean emailVerified;

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
                .hasPassword(user.getPasswordHash() != null)
                .kakaoLinked(user.getKakaoId() != null)
                .emailVerified(user.getEmail() != null && user.getEmailVerifiedAt() != null)
                .build();
    }
}
