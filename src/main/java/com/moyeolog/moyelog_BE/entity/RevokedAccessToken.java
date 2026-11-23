package com.moyeolog.moyelog_BE.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * 로그아웃으로 폐기한 액세스 토큰(jti). 토큰이 원래 만료되는 시각까지만 보관한다.
 */
@Entity
@Table(name = "revoked_access_tokens", indexes = {
        @Index(name = "idx_revoked_access_tokens_expires_at", columnList = "expires_at")
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class RevokedAccessToken {

    @Id
    @Column(length = 64)
    private String jti;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;
}
