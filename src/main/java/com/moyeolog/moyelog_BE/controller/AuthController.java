package com.moyeolog.moyelog_BE.controller;

import com.moyeolog.moyelog_BE.dto.AuthLogoutRequest;
import com.moyeolog.moyelog_BE.dto.AuthSyncRequest;
import com.moyeolog.moyelog_BE.dto.AuthSyncResponse;
import com.moyeolog.moyelog_BE.exception.InvalidTokenException;
import com.moyeolog.moyelog_BE.security.TokenHasher;
import com.moyeolog.moyelog_BE.service.AuthService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
@Slf4j
public class AuthController {

    public static final String INTERNAL_SECRET_HEADER = "X-Internal-Secret";

    private final AuthService authService;

    /**
     * 프론트엔드 서버(NextAuth)만 알고 있는 공유 비밀값. 환경변수 INTERNAL_API_SECRET.
     * 비어 있으면 /api/auth/sync 는 항상 401 입니다 (안전한 기본값).
     */
    @Value("${internal.api-secret:}")
    private String internalApiSecret;

    @PostMapping("/sync")
    public ResponseEntity<AuthSyncResponse> sync(
            @RequestHeader(value = INTERNAL_SECRET_HEADER, required = false) String internalSecret,
            @RequestBody AuthSyncRequest request) {
        requireInternalSecret(internalSecret, "sync");
        AuthSyncResponse response = authService.syncUser(request);
        log.info("Successfully synced user: {}", response.getUser().getId());
        return ResponseEntity.ok(response);
    }

    /**
     * 로그아웃 (서버 간 호출, nginx 가 외부에서 막는다). 리프레시 토큰을 폐기하고 액세스 토큰을 만료 시각까지 거부한다.
     * 본문이 비어 있거나 이미 폐기된 토큰이어도 204.
     */
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(
            @RequestHeader(value = INTERNAL_SECRET_HEADER, required = false) String internalSecret,
            @RequestBody(required = false) AuthLogoutRequest request) {
        requireInternalSecret(internalSecret, "logout");
        if (request != null) {
            authService.logout(request.getRefreshToken(), request.getAccessToken());
        }
        return ResponseEntity.noContent().build();
    }

    private void requireInternalSecret(String internalSecret, String action) {
        if (internalApiSecret == null || internalApiSecret.isBlank()
                || !TokenHasher.constantTimeEquals(internalApiSecret, internalSecret)) {
            log.warn("Rejected {} request: missing or invalid internal secret", action);
            throw new InvalidTokenException("허용되지 않은 요청이에요.");
        }
    }

    @PostMapping("/refresh")
    public ResponseEntity<Map<String, String>> refresh(@RequestBody Map<String, Object> request) {
        Object refreshToken = request.get("refreshToken");
        if (!(refreshToken instanceof String token) || token.isBlank()) {
            throw new InvalidTokenException("로그인이 만료됐어요. 다시 로그인해 주세요.");
        }
        return ResponseEntity.ok(authService.refreshToken(token));
    }
}
