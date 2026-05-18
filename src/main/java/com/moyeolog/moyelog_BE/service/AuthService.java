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
            nickname = "User_" + request.getKakaoId().substring(Math.max(0, request.getKakaoId().length() - 4));
        }

        final String finalNickname = nickname;
        User user = userRepository.findByKakaoId(request.getKakaoId())
                .map(existingUser -> {
                    existingUser.updateProfile(finalNickname, request.getProfileImage());
                    return existingUser;
                })
                .orElseGet(() -> userRepository.save(User.builder()
                        .kakaoId(request.getKakaoId())
                        .email(request.getEmail())
                        .nickname(finalNickname)
                        .profileImage(request.getProfileImage())
                        .build()));

        String token = jwtProvider.createToken(user.getId().toString());

        return AuthSyncResponse.builder()
                .accessToken(token)
                .user(user)
                .build();
    }
}
