package com.moyeolog.moyelog_BE.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 로그인(기기) 한 번당 한 행. 사용자 한 명이 여러 행을 가질 수 있습니다.
 */
@Entity
@Table(name = "refresh_tokens", indexes = {
        @Index(name = "idx_refresh_tokens_previous_token", columnList = "previous_token"),
        @Index(name = "idx_refresh_tokens_user_id", columnList = "user_id")
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class RefreshToken {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * 현재 리프레시 토큰의 SHA-256 해시(조회용 키).
     * 해시 도입 이전에 만들어진 행에는 토큰 원문이 들어 있으며, 다음 갱신 때 해시로 바뀝니다.
     */
    @Column(nullable = false, unique = true)
    private String token;

    /** 현재 리프레시 토큰 원문. 직전 토큰으로 재요청이 왔을 때(유예 구간) 그대로 돌려주기 위해 보관합니다. */
    @Column(name = "current_token", length = 512)
    private String currentToken;

    /** 직전 리프레시 토큰의 SHA-256 해시 */
    @Column(name = "previous_token", length = 64)
    private String previousToken;

    /** 마지막으로 토큰을 교체한 시각 */
    @Column(name = "rotated_at")
    private LocalDateTime rotatedAt;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "expiry_date", nullable = false)
    private LocalDateTime expiryDate;

    /**
     * @param presentedTokenHash 방금 제시된(이제 직전이 되는) 토큰의 SHA-256 해시.
     *                           예전 행은 token 컬럼에 원문이 들어 있으므로 token 값을 그대로 옮기지 않는다.
     */
    public void rotate(String presentedTokenHash, String newTokenHash, String newToken, LocalDateTime expiryDate, LocalDateTime now) {
        this.previousToken = presentedTokenHash;
        this.token = newTokenHash;
        this.currentToken = newToken;
        this.expiryDate = expiryDate;
        this.rotatedAt = now;
    }
}
