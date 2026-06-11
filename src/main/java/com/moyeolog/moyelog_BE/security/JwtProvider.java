package com.moyeolog.moyelog_BE.security;

import io.jsonwebtoken.*;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.util.Date;

@Component
public class JwtProvider {

    @Value("${jwt.secret}")
    private String salt;

    @Value("${jwt.expiration}")
    private long accessTokenExpirationTime;

    @Value("${jwt.refresh-expiration:604800000}") // Default 7 days
    private long refreshTokenExpirationTime;

    private SecretKey secretKey;

    @PostConstruct
    protected void init() {
        byte[] keyBytes = Decoders.BASE64.decode(salt);
        this.secretKey = Keys.hmacShaKeyFor(keyBytes);
    }

    public String createAccessToken(String userId) {
        return createToken(userId, accessTokenExpirationTime);
    }

    public String createRefreshToken(String userId) {
        return createToken(userId, refreshTokenExpirationTime);
    }

    private String createToken(String userId, long expiration) {
        Claims claims = Jwts.claims().subject(userId).build();
        Date now = new Date();
        return Jwts.builder()
                .claims(claims)
                .issuedAt(now)
                .expiration(new Date(now.getTime() + expiration))
                .signWith(secretKey)
                .compact();
    }

    public String getUserId(String token) {
        return Jwts.parser()
                .verifyWith(secretKey)
                .build()
                .parseSignedClaims(token)
                .getPayload()
                .getSubject();
    }

    public boolean validateToken(String token) {
        try {
            Jwts.parser().verifyWith(secretKey).build().parseSignedClaims(token);
            return true;
        } catch (ExpiredJwtException e) {
            System.err.println("JWT Token expired: " + e.getMessage());
        } catch (MalformedJwtException e) {
            System.err.println("JWT Token malformed: " + e.getMessage());
        } catch (SecurityException | UnsupportedJwtException e) {
            System.err.println("JWT Token invalid: " + e.getMessage());
        } catch (IllegalArgumentException e) {
            System.err.println("JWT Token claims string is empty: " + e.getMessage());
        }
        return false;
    }
}
