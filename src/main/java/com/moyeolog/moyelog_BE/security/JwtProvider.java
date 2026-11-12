package com.moyeolog.moyelog_BE.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.util.Date;
import java.util.Optional;
import java.util.UUID;

@Component
@Slf4j
public class JwtProvider {

    public static final String CLAIM_TOKEN_TYPE = "typ";
    public static final String TYPE_ACCESS = "access";
    public static final String TYPE_REFRESH = "refresh";

    /**
     * 검증이 끝난 토큰의 내용.
     * type 은 토큰 종류 클레임이 도입되기 전에 발급된 토큰이면 null 입니다.
     */
    public record ParsedToken(String userId, String type) {
        public boolean isAccess() {
            return TYPE_ACCESS.equals(type);
        }

        public boolean isRefresh() {
            return TYPE_REFRESH.equals(type);
        }

        public boolean isLegacy() {
            return type == null;
        }
    }

    @Value("${jwt.secret}")
    private String salt;

    @Value("${jwt.expiration}")
    private long accessTokenExpirationTime;

    @Getter
    @Value("${jwt.refresh-expiration:604800000}") // Default 7 days
    private long refreshTokenExpirationTime;

    private SecretKey secretKey;

    @PostConstruct
    protected void init() {
        byte[] keyBytes = Decoders.BASE64.decode(salt);
        this.secretKey = Keys.hmacShaKeyFor(keyBytes);
    }

    public String createAccessToken(String userId) {
        return createToken(userId, TYPE_ACCESS, accessTokenExpirationTime);
    }

    public String createRefreshToken(String userId) {
        return createToken(userId, TYPE_REFRESH, refreshTokenExpirationTime);
    }

    private String createToken(String userId, String type, long expiration) {
        Date now = new Date();
        return Jwts.builder()
                .subject(userId)
                .claim(CLAIM_TOKEN_TYPE, type)
                // 같은 초에 발급된 토큰끼리도 서로 다른 문자열이 되도록 한다.
                .id(UUID.randomUUID().toString().replace("-", "").substring(0, 12))
                .issuedAt(now)
                .expiration(new Date(now.getTime() + expiration))
                .signWith(secretKey)
                .compact();
    }

    /**
     * 서명/만료/형식을 검증하고 내용을 돌려줍니다. 어떤 종류의 잘못된 토큰이든 예외 없이 empty 를 반환합니다.
     */
    public Optional<ParsedToken> parse(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(secretKey)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
            String subject = claims.getSubject();
            if (subject == null || subject.isBlank()) {
                return Optional.empty();
            }
            Object type = claims.get(CLAIM_TOKEN_TYPE);
            return Optional.of(new ParsedToken(subject, type != null ? type.toString() : null));
        } catch (JwtException | IllegalArgumentException e) {
            // JwtException 은 ExpiredJwtException, MalformedJwtException, SignatureException,
            // UnsupportedJwtException 등을 모두 포함한다. 토큰 값은 로그에 남기지 않는다.
            log.debug("JWT rejected: {}", e.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    public String getUserId(String token) {
        return parse(token).map(ParsedToken::userId).orElse(null);
    }

    public boolean validateToken(String token) {
        return parse(token).isPresent();
    }
}
