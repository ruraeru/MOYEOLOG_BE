package com.moyeolog.moyelog_BE.service;

import com.moyeolog.moyelog_BE.dto.AuthSyncRequest;
import com.moyeolog.moyelog_BE.dto.AuthSyncResponse;
import com.moyeolog.moyelog_BE.entity.User;
import com.moyeolog.moyelog_BE.repository.UserRepository;
import com.moyeolog.moyelog_BE.security.JwtProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Random;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
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
                    existingUser.updateProfile(finalNickname, request.getProfileImage());
                    // 이메일이 바뀐 경우 업데이트 (선택 사항)
                    if (finalEmail != null) {
                        existingUser.updateEmail(finalEmail);
                    }
                    // 기존 유저인데 customId가 없는 경우 부여
                    if (existingUser.getCustomId() == null) {
                        // Setter가 없으므로 Reflection이나 Builder 등으로 처리해야 하지만, 
                        // 간단하게 update 메서드를 User 엔티티에 추가하거나 여기서 필드 주입 방식을 고려
                        // 여기서는 User 엔티티에 updateCustomId 메서드를 추가했다고 가정하고 호출 (잠시 후 User 엔티티 수정 예정)
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
                                    existingUser.updateProfile(finalNickname, request.getProfileImage());
                                    if (existingUser.getCustomId() == null) {
                                        existingUser.updateCustomId(generateUniqueCustomId());
                                    }
                                    return existingUser;
                                })
                                .orElseGet(() -> createUser(request, finalNickname, finalEmail));
                    }
                    return createUser(request, finalNickname, finalEmail);
                });

        String token = jwtProvider.createToken(user.getId().toString());

        return AuthSyncResponse.builder()
                .accessToken(token)
                .user(user)
                .build();
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
