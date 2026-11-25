package com.moyeolog.moyelog_BE.account;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.ColumnDefault;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 메일 링크 토큰. 토큰 원문은 저장하지 않고 SHA-256(TokenHasher.sha256)만 저장한다. 1회용, 만료 30분.
 * SIGNUP: 이메일 가입 대기(계정은 인증 뒤에 만든다. password_hash·nickname 을 여기 둔다).
 * PASSWORD: 인증된 이메일 계정의 비밀번호 (재)설정. EMAIL: 로그인한 사용자의 이메일 등록·변경.
 */
@Entity
@Table(name = "email_verifications", indexes = {
        @Index(name = "idx_email_verifications_email", columnList = "email"),
        @Index(name = "idx_email_verifications_user", columnList = "user_id")
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class EmailVerification {

    public enum Purpose { SIGNUP, PASSWORD, EMAIL }

    static final int MAX_FAILED_ATTEMPTS = 5;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Purpose purpose;

    /** trim + 소문자 */
    @Column(nullable = false)
    private String email;

    @Column(name = "token_hash", nullable = false, unique = true, length = 64)
    private String tokenHash;

    /** PASSWORD·EMAIL 의 대상 사용자. SIGNUP 은 NULL. */
    @Column(name = "user_id", columnDefinition = "BINARY(16)")
    private UUID userId;

    /** SIGNUP 대기용 BCrypt 해시 */
    @Column(name = "password_hash", length = 100)
    private String passwordHash;

    /** SIGNUP 대기용 닉네임 (UserService.sanitizeNickname 을 거친 값) */
    @Column(length = 200)
    private String nickname;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @Column(name = "used_at")
    private LocalDateTime usedAt;

    /** SIGNUP 확정 때 비밀번호를 틀린 횟수. MAX_FAILED_ATTEMPTS 번이면 그 토큰은 못 쓴다. */
    @ColumnDefault("0")
    @Column(name = "failed_attempts", nullable = false)
    private int failedAttempts;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    EmailVerification(Purpose purpose, String email, String tokenHash, UUID userId,
                      String passwordHash, String nickname, LocalDateTime now, LocalDateTime expiresAt) {
        this.purpose = purpose;
        this.email = email;
        this.tokenHash = tokenHash;
        this.userId = userId;
        this.passwordHash = passwordHash;
        this.nickname = nickname;
        this.createdAt = now;
        this.expiresAt = expiresAt;
    }

    boolean usable(LocalDateTime now) {
        return usedAt == null && expiresAt.isAfter(now);
    }

    void markUsed(LocalDateTime now) {
        this.usedAt = now;
    }

    void recordFailedAttempt(LocalDateTime now) {
        failedAttempts++;
        if (failedAttempts >= MAX_FAILED_ATTEMPTS) {
            usedAt = now;
        }
    }
}
