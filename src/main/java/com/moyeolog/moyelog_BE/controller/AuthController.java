package com.moyeolog.moyelog_BE.controller;

import com.moyeolog.moyelog_BE.dto.AuthLinkKakaoRequest;
import com.moyeolog.moyelog_BE.dto.AuthLoginRequest;
import com.moyeolog.moyelog_BE.dto.AuthLogoutRequest;
import com.moyeolog.moyelog_BE.dto.AuthSyncRequest;
import com.moyeolog.moyelog_BE.dto.AuthSyncResponse;
import com.moyeolog.moyelog_BE.dto.LinkPreviewResponse;
import com.moyeolog.moyelog_BE.exception.BadRequestException;
import com.moyeolog.moyelog_BE.exception.InvalidTokenException;
import com.moyeolog.moyelog_BE.security.TokenHasher;
import com.moyeolog.moyelog_BE.service.AuthService;
import com.moyeolog.moyelog_BE.service.GroupService;
import com.moyeolog.moyelog_BE.service.UserService;
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
    private final GroupService groupService;
    private final UserService userService;

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
     * 이메일 + 비밀번호 로그인 (서버 간 호출, nginx 가 외부에서 막는다). 응답은 sync 와 같은 모양.
     * 실패는 이유와 관계없이 같은 401 문구(계정 유무·잠금 여부를 드러내지 않는다).
     */
    @PostMapping("/login")
    public ResponseEntity<AuthSyncResponse> login(
            @RequestHeader(value = INTERNAL_SECRET_HEADER, required = false) String internalSecret,
            @RequestBody(required = false) AuthLoginRequest request) {
        requireInternalSecret(internalSecret, "login");
        AuthSyncResponse response = request == null
                ? authService.loginWithPassword(null, null, null)
                : authService.loginWithPassword(request.getEmail(), request.getPassword(), request.getClientIp());
        log.info("Password login: {}", response.getUser().getId());
        return ResponseEntity.ok(response);
    }

    /**
     * 지금 로그인한 사용자(본문의 accessToken)에 카카오 계정을 연결한다 (서버 간 호출, nginx 가 외부에서 막는다).
     * 다른 계정에 이미 연결된 카카오 계정이면 409.
     */
    @PostMapping("/link-kakao")
    public ResponseEntity<AuthSyncResponse> linkKakao(
            @RequestHeader(value = INTERNAL_SECRET_HEADER, required = false) String internalSecret,
            @RequestBody(required = false) AuthLinkKakaoRequest request) {
        requireInternalSecret(internalSecret, "link-kakao");
        AuthSyncResponse response = authService.linkKakao(request);
        log.info("Linked kakao account to user: {}", response.getUser().getId());
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

    /**
     * 초대 링크를 공유했을 때의 미리보기 정보 (서버 간 호출). 메신저·SNS 의 미리보기 수집기는 로그인하지 않으므로
     * 프론트 서버가 페이지의 Open Graph 메타를 만들 때 부른다. type = group(code = 초대 코드) | friend(code = customId).
     * /api/auth/** 는 nginx 가 프론트로 보내 외부에서 닿지 않고, 내부 비밀값이 없으면 401 이다.
     */
    @GetMapping("/link-preview")
    public ResponseEntity<LinkPreviewResponse> linkPreview(
            @RequestHeader(value = INTERNAL_SECRET_HEADER, required = false) String internalSecret,
            @RequestParam String type,
            @RequestParam String code) {
        requireInternalSecret(internalSecret, "link-preview");
        return switch (type) {
            case "group" -> ResponseEntity.ok(groupService.linkPreviewByCode(code));
            case "friend" -> ResponseEntity.ok(userService.linkPreviewByCustomId(code));
            default -> throw new BadRequestException("알 수 없는 링크 종류예요.");
        };
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
