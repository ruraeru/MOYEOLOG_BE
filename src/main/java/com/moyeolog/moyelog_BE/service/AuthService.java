package com.moyeolog.moyelog_BE.service;

import com.moyeolog.moyelog_BE.dto.AuthSyncRequest;
import com.moyeolog.moyelog_BE.dto.AuthSyncResponse;
import com.moyeolog.moyelog_BE.entity.RefreshToken;
import com.moyeolog.moyelog_BE.entity.User;
import com.moyeolog.moyelog_BE.exception.BadRequestException;
import com.moyeolog.moyelog_BE.exception.InvalidTokenException;
import com.moyeolog.moyelog_BE.repository.RefreshTokenRepository;
import com.moyeolog.moyelog_BE.repository.UserRepository;
import com.moyeolog.moyelog_BE.security.JwtProvider;
import com.moyeolog.moyelog_BE.security.TokenHasher;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final JwtProvider jwtProvider;
    private final Random random = new Random();
    private static final long ROTATION_GRACE_SECONDS = 60;
    private static final String CHARACTERS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";

    @Transactional
    public AuthSyncResponse syncUser(AuthSyncRequest request) {
        if (request == null || request.getKakaoId() == null || request.getKakaoId().isBlank()) {
            throw new BadRequestException("kakaoId는 필수입니다.");
        }
        if (request.getKakaoId().length() > 255) {
            throw new BadRequestException("kakaoId가 너무 깁니다.");
        }

        String nickname = request.getNickname();
        if (nickname == null || nickname.trim().isEmpty()) {
            nickname = "사용자_" + request.getKakaoId().substring(Math.max(0, request.getKakaoId().length() - 4));
        }
        nickname = nickname.trim();
        if (nickname.length() > 50) {
            nickname = nickname.substring(0, 50);
        }

        String email = request.getEmail();
        if (email != null && (email.trim().isEmpty() || email.length() > 255)) {
            email = null;
        }
        // 컬럼 길이(255)를 넘는 프로필 이미지 URL 은 저장하지 않는다.
        if (request.getProfileImage() != null && request.getProfileImage().length() > 255) {
            request.setProfileImage(null);
        }

        final String finalNickname = nickname;
        final String finalEmail = email;

        // 1. kakaoId로 먼저 찾기
        User user = userRepository.findByKakaoId(request.getKakaoId())
                .map(existingUser -> {
                    // 기존 사용자는 카카오 정보로 덮어쓰지 않고, 이메일/CustomID 등 누락된 정보만 보완
                    if (existingUser.getNickname() == null || existingUser.getNickname().isEmpty()) {
                        existingUser.setNickname(finalNickname);
                    }
                    // 프로필 이미지도 명시적으로 설정된 것이 없을 때만 카카오 정보 사용 (옵션)
                    if (existingUser.getProfileImage() == null || existingUser.getProfileImage().isEmpty()) {
                        existingUser.setProfileImage(request.getProfileImage());
                    }
                    
                    if (finalEmail != null && existingUser.getEmail() == null) {
                        existingUser.updateEmail(finalEmail);
                    }
                    if (existingUser.getCustomId() == null) {
                        existingUser.updateCustomId(generateUniqueCustomId());
                    }
                    return existingUser;
                })
                .orElseGet(() -> {
                    // 2. kakaoId로 못 찾은 경우, 이메일로 찾기 (이미 다른 kakaoId로 가입된 경우 대비)
                    if (finalEmail != null) {
                        return userRepository.findAllByEmail(finalEmail).stream().findFirst()
                                .map(existingUser -> {
                                    // 기존 유저에 kakaoId 연결 (계정 통합)
                                    existingUser.updateKakaoId(request.getKakaoId());
                                    // 닉네임/이미지는 기존 것 유지 (생략 가능)
                                    if (existingUser.getCustomId() == null) {
                                        existingUser.updateCustomId(generateUniqueCustomId());
                                    }
                                    return existingUser;
                                })
                                .orElseGet(() -> createUser(request, finalNickname, finalEmail));
                    }
                    return createUser(request, finalNickname, finalEmail);
                });

        String accessToken = jwtProvider.createAccessToken(user.getId().toString());
        String refreshToken = jwtProvider.createRefreshToken(user.getId().toString());

        addRefreshToken(user.getId(), refreshToken);

        return AuthSyncResponse.builder()
                .accessToken(accessToken)
                .refreshToken(refreshToken)
                .user(user)
                .build();
    }

    /**
     * 리프레시 토큰으로 새 토큰을 발급합니다.
     * - 저장된 현재 토큰이면 교체(rotation)하고 새 액세스/리프레시 토큰을 돌려줍니다.
     * - 방금(60초 이내) 교체된 행의 직전 토큰이면 다시 교체하지 않고, 새 액세스 토큰과 현재 리프레시 토큰을 돌려줍니다.
     *   (탭 두 개 / 서버 컴포넌트가 같은 토큰으로 동시에 갱신을 요청하는 경우)
     * - 그 외에는 모두 401 입니다.
     */
    @Transactional
    public Map<String, String> refreshToken(String refreshToken) {
        JwtProvider.ParsedToken parsed = jwtProvider.parse(refreshToken)
                .orElseThrow(() -> new InvalidTokenException("유효하지 않은 리프레시 토큰입니다."));
        if (parsed.isAccess()) {
            throw new InvalidTokenException("유효하지 않은 리프레시 토큰입니다.");
        }

        LocalDateTime now = LocalDateTime.now();
        String tokenHash = TokenHasher.sha256(refreshToken);

        // 해시로 저장된 행을 우선 찾고, 해시 도입 이전에 원문으로 저장된 행도 허용한다.
        List<RefreshToken> stored = refreshTokenRepository.findAllByTokenInForUpdate(List.of(tokenHash, refreshToken));
        if (!stored.isEmpty()) {
            RefreshToken storedToken = stored.get(0);
            if (storedToken.getExpiryDate().isBefore(now)
                    || !storedToken.getUserId().toString().equals(parsed.userId())) {
                throw new InvalidTokenException("만료되었거나 유효하지 않은 리프레시 토큰입니다.");
            }

            String userId = storedToken.getUserId().toString();
            String newRefreshToken = jwtProvider.createRefreshToken(userId);
            storedToken.rotate(tokenHash, TokenHasher.sha256(newRefreshToken), newRefreshToken, refreshExpiry(now), now);

            return Map.of(
                    "accessToken", jwtProvider.createAccessToken(userId),
                    "refreshToken", newRefreshToken
            );
        }

        // 유예 구간: 직전 토큰으로 다시 요청이 온 경우
        for (RefreshToken rotated : refreshTokenRepository.findAllByPreviousToken(tokenHash)) {
            boolean withinGrace = rotated.getRotatedAt() != null
                    && !rotated.getRotatedAt().isBefore(now.minusSeconds(ROTATION_GRACE_SECONDS));
            if (withinGrace
                    && rotated.getCurrentToken() != null
                    && rotated.getExpiryDate().isAfter(now)
                    && rotated.getUserId().toString().equals(parsed.userId())) {
                return Map.of(
                        "accessToken", jwtProvider.createAccessToken(rotated.getUserId().toString()),
                        "refreshToken", rotated.getCurrentToken()
                );
            }
        }

        throw new InvalidTokenException("유효하지 않은 리프레시 토큰입니다.");
    }

    /** 로그인(기기)마다 새 행을 추가한다. 같은 사용자의 만료된 행은 이때 정리한다. */
    private void addRefreshToken(UUID userId, String token) {
        LocalDateTime now = LocalDateTime.now();
        refreshTokenRepository.deleteAllByUserIdAndExpiryDateBefore(userId, now);
        refreshTokenRepository.save(RefreshToken.builder()
                .userId(userId)
                .token(TokenHasher.sha256(token))
                .currentToken(token)
                .expiryDate(refreshExpiry(now))
                .build());
    }

    private LocalDateTime refreshExpiry(LocalDateTime now) {
        return now.plusNanos(jwtProvider.getRefreshTokenExpirationTime() * 1_000_000L);
    }

    private User createUser(AuthSyncRequest request, String nickname, String email) {
        String customId = generateUniqueCustomId();
        return userRepository.save(User.builder()
                .kakaoId(request.getKakaoId())
                .customId(customId)
                .email(email)
                .nickname(nickname)
                .profileImage(request.getProfileImage())
                .build());
    }

    private String generateUniqueCustomId() {
        String customId;
        do {
            customId = generateRandomId(8);
        } while (userRepository.existsByCustomId(customId));
        return customId;
    }

    private String generateRandomId(int length) {
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(CHARACTERS.charAt(random.nextInt(CHARACTERS.length())));
        }
        return sb.toString();
    }
}
