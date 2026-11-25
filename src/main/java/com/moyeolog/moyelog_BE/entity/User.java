package com.moyeolog.moyelog_BE.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.ColumnDefault;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "users")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(columnDefinition = "BINARY(16)")
    private UUID id;

    @Column(name = "custom_id", unique = true, length = 8)
    private String customId;

    /** 이메일로만 가입한 계정은 NULL (카카오를 아직 연결하지 않음). */
    @JsonIgnore
    @Column(name = "kakao_id", unique = true)
    private String kakaoId;

    /** BCrypt 해시. NULL 이면 비밀번호 로그인을 쓸 수 없는 계정(카카오로만 가입). */
    @JsonIgnore
    @Column(name = "password_hash", length = 100)
    private String passwordHash;

    /** email 의 소유가 확인된 시각(인증 메일 또는 카카오의 '인증됨'). NULL 이면 확인되지 않은 이메일. */
    @JsonIgnore
    @Column(name = "email_verified_at")
    private LocalDateTime emailVerifiedAt;

    /** 연속으로 틀린 비밀번호 횟수. 로그인에 성공하면 0. */
    @JsonIgnore
    @Builder.Default
    @ColumnDefault("0")
    @Column(name = "failed_login_count", nullable = false)
    private int failedLoginCount = 0;

    /** 이 시각까지 비밀번호 로그인을 받지 않는다. */
    @JsonIgnore
    @Column(name = "login_locked_until")
    private LocalDateTime loginLockedUntil;

    /**
     * 카카오 계정이 이 사용자에 연결(가입·로그인·이메일 연결)된 시각.
     * NULL 이면 아직 한 번도 연결되지 않은 예전 계정이며, 검증된 이메일로 한 번만 다른 kakaoId 를 연결할 수 있다.
     */
    @JsonIgnore
    @Column(name = "kakao_linked_at")
    private LocalDateTime kakaoLinkedAt;

    /**
     * 마지막으로 카카오 로그인(sync)·카카오 연결(link-kakao)에 성공한 시각.
     * 비밀번호가 없는 계정이 로그인 방법을 바꿀 때 '방금 카카오로 로그인했는지'를 이것으로 확인한다.
     */
    @JsonIgnore
    @Column(name = "last_kakao_login_at")
    private LocalDateTime lastKakaoLoginAt;

    /** 이 시각(초 단위) 이전에 발급된 액세스 토큰은 거부한다 (모든 기기 로그아웃). */
    @JsonIgnore
    @Column(name = "tokens_valid_after")
    private LocalDateTime tokensValidAfter;

    /** 친구에게 이메일을 보여 줄지 여부. 기본은 비공개. */
    @Builder.Default
    @ColumnDefault("0")
    @Column(name = "email_visible", nullable = false)
    private boolean emailVisible = false;

    @Column(unique = true)
    private String email;

    /** 보이는 글자 20자(grapheme), 코드 포인트 100 이하(UserService). 컬럼은 UTF-16 기준 DB(H2)도 담도록 200. */
    @Column(nullable = false, length = 200)
    private String nickname;


    @Column(name = "profile_image")
    private String profileImage;

    @Column(columnDefinition = "TEXT")
    private String bio;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }

    public void updateProfile(String nickname, String profileImage) {
        this.nickname = nickname;
        this.profileImage = profileImage;
    }

    public void updateBio(String bio) {
        this.bio = bio;
    }

    public void updateEmail(String email) {
        this.email = email;
    }

    public void updateKakaoId(String kakaoId) {
        this.kakaoId = kakaoId;
    }

    public void updateCustomId(String customId) {
        this.customId = customId;
    }

    public void linkKakao(String kakaoId, LocalDateTime now) {
        this.kakaoId = kakaoId;
        this.kakaoLinkedAt = now;
    }

    public void markKakaoLinked(LocalDateTime now) {
        if (this.kakaoLinkedAt == null) {
            this.kakaoLinkedAt = now;
        }
    }
}
