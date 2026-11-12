package com.moyeolog.moyelog_BE.controller;

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
        if (internalApiSecret == null || internalApiSecret.isBlank()
                || !TokenHasher.constantTimeEquals(internalApiSecret, internalSecret)) {
            log.warn("Rejected sync request: missing or invalid internal secret");
            throw new InvalidTokenException("허용되지 않은 요청입니다.");
        }

        AuthSyncResponse response = authService.syncUser(request);
        log.info("Successfully synced user: {}", response.getUser().getId());
        return ResponseEntity.ok(response);
    }

    @PostMapping("/refresh")
    public ResponseEntity<Map<String, String>> refresh(@RequestBody Map<String, Object> request) {
        Object refreshToken = request.get("refreshToken");
        if (!(refreshToken instanceof String token) || token.isBlank()) {
            throw new InvalidTokenException("유효하지 않은 리프레시 토큰입니다.");
        }
        return ResponseEntity.ok(authService.refreshToken(token));
    }
}
