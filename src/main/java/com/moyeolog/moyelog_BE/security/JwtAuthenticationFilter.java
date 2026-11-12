package com.moyeolog.moyelog_BE.security;

import com.moyeolog.moyelog_BE.repository.RefreshTokenRepository;
import com.moyeolog.moyelog_BE.repository.UserRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Authorization 헤더의 액세스 토큰으로 인증 정보를 설정합니다.
 * 토큰이 없거나 잘못된 경우에는 아무 것도 하지 않고(인증하지 않고) 다음 필터로 넘깁니다.
 * 따라서 permitAll 경로는 잘못된 토큰이 붙어 있어도 그대로 통과하고,
 * 인증이 필요한 경로는 SecurityConfig 의 entry point 가 401 을 돌려줍니다.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtProvider jwtProvider;
    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;

    /**
     * 토큰 종류 클레임(typ)이 없는 예전 토큰을 액세스 토큰으로 받아들일지 여부.
     * 배포 후 기존 로그인 세션이 끊기지 않도록 기본값은 true 이며,
     * 예전 토큰이 모두 만료된 뒤(리프레시 토큰 유효기간, 기본 7일)에는 false 로 바꿔도 됩니다.
     */
    @Value("${jwt.accept-legacy-tokens:true}")
    private boolean acceptLegacyTokens;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        String token = parseJwt(request);
        if (token != null) {
            try {
                authenticate(token, request);
            } catch (RuntimeException e) {
                // 인증 처리 중 어떤 문제가 생겨도 필터 밖으로 예외를 던지지 않는다 (인증하지 않은 상태로 진행).
                SecurityContextHolder.clearContext();
                log.warn("JWT authentication skipped for {}: {}", request.getRequestURI(), e.getClass().getSimpleName());
            }
        }

        filterChain.doFilter(request, response);
    }

    private void authenticate(String token, HttpServletRequest request) {
        Optional<JwtProvider.ParsedToken> parsed = jwtProvider.parse(token);
        if (parsed.isEmpty()) {
            log.debug("JWT validation failed for request: {}", request.getRequestURI());
            return;
        }

        JwtProvider.ParsedToken parsedToken = parsed.get();
        if (!isUsableAsAccessToken(parsedToken, token)) {
            log.debug("Token of wrong type presented as access token: {}", request.getRequestURI());
            return;
        }

        UUID userId;
        try {
            userId = UUID.fromString(parsedToken.userId());
        } catch (IllegalArgumentException e) {
            return;
        }

        // 탈퇴/삭제된 사용자의 토큰은 인증하지 않는다 → 보호된 경로에서 401
        if (!userRepository.existsById(userId)) {
            log.debug("JWT subject no longer exists: {}", userId);
            return;
        }

        UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                userId.toString(), null, List.of(new SimpleGrantedAuthority("ROLE_USER")));
        authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }

    private boolean isUsableAsAccessToken(JwtProvider.ParsedToken parsedToken, String rawToken) {
        if (parsedToken.isAccess()) {
            return true;
        }
        if (parsedToken.isLegacy() && acceptLegacyTokens) {
            // 예전 형식 토큰은 종류를 알 수 없으므로, 저장되어 있는 리프레시 토큰이면 거부한다.
            return !refreshTokenRepository.existsByTokenIn(List.of(rawToken, TokenHasher.sha256(rawToken)));
        }
        return false;
    }

    private String parseJwt(HttpServletRequest request) {
        String headerAuth = request.getHeader("Authorization");

        if (StringUtils.hasText(headerAuth) && headerAuth.startsWith("Bearer ")) {
            String token = headerAuth.substring(7).trim();
            return token.isEmpty() ? null : token;
        }

        return null;
    }
}
