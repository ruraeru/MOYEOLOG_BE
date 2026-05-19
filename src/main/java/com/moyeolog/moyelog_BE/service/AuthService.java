package com.moyeolog.moyelog_BE.service;

import com.moyeolog.moyelog_BE.dto.AuthSyncRequest;
import com.moyeolog.moyelog_BE.dto.AuthSyncResponse;
import com.moyeolog.moyelog_BE.entity.User;
import com.moyeolog.moyelog_BE.repository.UserRepository;
import com.moyeolog.moyelog_BE.security.JwtProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final JwtProvider jwtProvider;

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
        return userRepository.save(User.builder()
                .kakaoId(request.getKakaoId())
                .email(email)
                .nickname(nickname)
                .profileImage(request.getProfileImage())
                .build());
    }
}
