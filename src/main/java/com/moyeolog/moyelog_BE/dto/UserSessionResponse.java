package com.moyeolog.moyelog_BE.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/** 로그인 세션 하나(리프레시 토큰 행 단위). GET /api/users/me/sessions */
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserSessionResponse {
    private Long id;
    private LocalDateTime createdAt;
    private LocalDateTime lastUsedAt;
    private boolean current;
}
