package com.moyeolog.moyelog_BE.service;

import com.moyeolog.moyelog_BE.dto.AuthSyncRequest;
import com.moyeolog.moyelog_BE.dto.AuthSyncResponse;
import com.moyeolog.moyelog_BE.entity.RefreshToken;
import com.moyeolog.moyelog_BE.entity.User;
import com.moyeolog.moyelog_BE.repository.RefreshTokenRepository;
import com.moyeolog.moyelog_BE.repository.UserRepository;
import com.moyeolog.moyelog_BE.security.JwtProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
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
    private static final String CHARACTERS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";

    @Transactional
    public AuthSyncResponse syncUser(AuthSyncRequest request) {
        String nickname = request.getNickname();
        if (nickname == null || nickname.trim().isEmpty()) {
            nickname = "사용자_" + request.getKakaoId().substring(Math.max(0, request.getKakaoId().length() - 4));
        }

        String email = request.getEmail();
        if (email != null && email.trim().isEmpty()) {
            email = null;
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
                        return userRepository.findByEmail(finalEmail)
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

        saveOrUpdateRefreshToken(user.getId(), refreshToken);

        return AuthSyncResponse.builder()
                .accessToken(accessToken)
                .refreshToken(refreshToken)
                .user(user)
                .build();
    }

    @Transactional
    public Map<String, String> refreshToken(String refreshToken) {
        if (!jwtProvider.validateToken(refreshToken)) {
            throw new RuntimeException("유효하지 않은 리프레시 토큰입니다.");
        }

        RefreshToken storedToken = refreshTokenRepository.findByToken(refreshToken)
                .orElseThrow(() -> new RuntimeException("존재하지 않는 리프레시 토큰입니다."));

        if (storedToken.getExpiryDate().isBefore(LocalDateTime.now())) {
            refreshTokenRepository.delete(storedToken);
            throw new RuntimeException("만료된 리프레시 토큰입니다.");
        }

        String userId = storedToken.getUserId().toString();
        String newAccessToken = jwtProvider.createAccessToken(userId);
        String newRefreshToken = jwtProvider.createRefreshToken(userId);

        storedToken.updateToken(newRefreshToken, LocalDateTime.now().plusDays(7));

        return Map.of(
                "accessToken", newAccessToken,
                "refreshToken", newRefreshToken
        );
    }

    private void saveOrUpdateRefreshToken(UUID userId, String token) {
        RefreshToken refreshToken = refreshTokenRepository.findByUserId(userId)
                .map(existingToken -> {
                    existingToken.updateToken(token, LocalDateTime.now().plusDays(7));
                    return existingToken;
                })
                .orElseGet(() -> RefreshToken.builder()
                        .userId(userId)
                        .token(token)
                        .expiryDate(LocalDateTime.now().plusDays(7))
                        .build());
        
        refreshTokenRepository.save(refreshToken);
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
