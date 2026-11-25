package com.moyeolog.moyelog_BE.live;

import com.moyeolog.moyelog_BE.security.JwtProvider;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * 실시간 갱신 스트림. 인증은 다른 API 와 같은 Authorization: Bearer 헤더다(토큰을 쿼리스트링에 넣지 않는다).
 * 인증 없는 요청은 SecurityConfig 가 401 로 막는다.
 */
@RestController
@RequiredArgsConstructor
public class LiveController {

    private final LiveHub liveHub;
    private final JwtProvider jwtProvider;

    @GetMapping(value = "/api/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events(@AuthenticationPrincipal String userId, HttpServletRequest request,
                             HttpServletResponse response) {
        // 중간 프록시(nginx)가 모아 두지 않고 바로 흘려보내게 한다(nginx 설정에도 proxy_buffering off 가 있다).
        response.setHeader("X-Accel-Buffering", "no");
        response.setHeader("Cache-Control", "no-cache");

        // 필터가 이미 검증한 토큰이다. 여기서는 jti(로그아웃한 기기의 연결만 닫기)와 만료 시각만 읽는다.
        Optional<JwtProvider.ParsedToken> token = Optional.ofNullable(request.getHeader("Authorization"))
                .filter(h -> h.startsWith("Bearer "))
                .flatMap(h -> jwtProvider.parse(h.substring(7).trim()));
        String jti = token.map(JwtProvider.ParsedToken::jti).orElse(null);
        long lifetime = token.map(JwtProvider.ParsedToken::expiresAt)
                .map(exp -> exp.toEpochMilli() - Instant.now().toEpochMilli())
                .orElse(Long.MAX_VALUE);
        return liveHub.open(UUID.fromString(userId), jti, lifetime);
    }
}
