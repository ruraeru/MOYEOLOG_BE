package com.moyeolog.moyelog_BE.service;

import com.moyeolog.moyelog_BE.dto.AuthSyncRequest;
import com.moyeolog.moyelog_BE.dto.AuthSyncResponse;
import com.moyeolog.moyelog_BE.dto.UserMeResponse;
import com.moyeolog.moyelog_BE.dto.UserSessionResponse;
import com.moyeolog.moyelog_BE.entity.RevokedAccessToken;
import com.moyeolog.moyelog_BE.exception.ResourceNotFoundException;
import com.moyeolog.moyelog_BE.repository.RevokedAccessTokenRepository;
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

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final RevokedAccessTokenRepository revokedAccessTokenRepository;
    private final JwtProvider jwtProvider;
    private final SecureRandom random = new SecureRandom();
    private static final long ROTATION_GRACE_SECONDS = 60;
    private static final String CHARACTERS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";

    /**
     * 카카오 로그인 결과로 사용자를 찾거나 만든다.
     * <ol>
     *   <li>kakaoId 로 찾는다.</li>
     *   <li>못 찾으면, 카카오가 인증한 이메일(emailVerified=true)이고 그 이메일의 기존 계정이 아직 한 번도
     *       카카오에 연결되지 않았을 때(kakao_linked_at IS NULL)만 그 계정에 kakaoId 를 붙인다.</li>
     *   <li>그 밖에는 새 계정. 이메일이 이미 다른 계정에 있으면 새 계정의 email 은 null.</li>
     * </ol>
     * 어떤 경우든 연결된 계정의 kakao_linked_at 을 채운다.
     */
    @Transactional
    public AuthSyncResponse syncUser(AuthSyncRequest request) {
        if (request == null || request.getKakaoId() == null || request.getKakaoId().isBlank()) {
            throw new BadRequestException("kakaoId가 필요해요.");
        }
        if (request.getKakaoId().length() > 255) {
            throw new BadRequestException("kakaoId가 너무 길어요.");
        }

        String kakaoId = request.getKakaoId();
        String nickname = UserService.sanitizeNickname(request.getNickname());
        if (nickname.isEmpty()) {
            nickname = "사용자_" + kakaoId.substring(Math.max(0, kakaoId.length() - 4));
        }

        String email = request.getEmail() == null ? null : request.getEmail().trim();
        if (email != null && (email.isEmpty() || email.length() > 255)) {
            email = null;
        }
        boolean emailVerified = Boolean.TRUE.equals(request.getEmailVerified());
        // 컬럼 길이(255)를 넘는 프로필 이미지 URL 은 저장하지 않는다.
        String profileImage = request.getProfileImage();
        if (profileImage != null && profileImage.length() > 255) {
            profileImage = null;
        }

        LocalDateTime now = LocalDateTime.now();
        User user = userRepository.findByKakaoId(kakaoId).orElse(null);
        if (user != null) {
            // 기존 사용자는 카카오 정보로 덮어쓰지 않고, 누락된 정보만 보완
            if (user.getNickname() == null || user.getNickname().isEmpty()) {
                user.setNickname(nickname);
            }
            if (user.getProfileImage() == null || user.getProfileImage().isEmpty()) {
                user.setProfileImage(profileImage);
            }
            if (email != null && user.getEmail() == null && !emailTaken(email)) {
                user.updateEmail(email);
            }
        } else {
            user = linkableByEmail(email, emailVerified);
            if (user != null) {
                user.linkKakao(kakaoId, now);
            } else {
                user = createUser(kakaoId, nickname, email != null && emailTaken(email) ? null : email, profileImage);
            }
        }
        if (user.getCustomId() == null) {
            user.updateCustomId(generateUniqueCustomId());
        }
        user.markKakaoLinked(now);

        String accessToken = jwtProvider.createAccessToken(user.getId().toString());
        String refreshToken = jwtProvider.createRefreshToken(user.getId().toString());

        addRefreshToken(user.getId(), refreshToken);

        return AuthSyncResponse.builder()
                .accessToken(accessToken)
                .refreshToken(refreshToken)
                .user(UserMeResponse.from(user))
                .build();
    }

    /** 검증된 이메일이고, 그 이메일의 계정이 정확히 하나이며 아직 카카오에 연결된 적이 없을 때만 돌려준다. */
    private User linkableByEmail(String email, boolean emailVerified) {
        if (email == null || !emailVerified) {
            return null;
        }
        List<User> candidates = userRepository.findAllByEmailIgnoreCase(email);
        if (candidates.size() != 1) {
            return null;
        }
        User candidate = candidates.get(0);
        return candidate.getKakaoLinkedAt() == null ? candidate : null;
    }

    private boolean emailTaken(String email) {
        return !userRepository.findAllByEmailIgnoreCase(email).isEmpty();
    }

    /**
     * 로그아웃(서버 간 호출). 리프레시 토큰 행을 지우고, 액세스 토큰의 jti 를 원래 만료 시각까지 거부 목록에 둔다.
     * 이미 폐기됐거나 잘못된 토큰이어도 오류 없이 끝난다(여러 번 불러도 같은 결과).
     */
    @Transactional
    public void logout(String refreshToken, String accessToken) {
        LocalDateTime now = LocalDateTime.now();
        if (refreshToken != null && !refreshToken.isBlank()) {
            String hash = TokenHasher.sha256(refreshToken);
            List<RefreshToken> rows = refreshTokenRepository.findAllForLogout(List.of(hash, refreshToken), hash);
            refreshTokenRepository.deleteAll(rows);
        }
        if (accessToken != null && !accessToken.isBlank()) {
            jwtProvider.parse(accessToken)
                    .filter(token -> token.jti() != null && token.expiresAt() != null)
                    .ifPresent(token -> {
                        LocalDateTime expiresAt = LocalDateTime.ofInstant(token.expiresAt(), ZoneId.systemDefault());
                        if (expiresAt.isAfter(now) && !revokedAccessTokenRepository.existsById(token.jti())) {
                            revokedAccessTokenRepository.save(RevokedAccessToken.builder()
                                    .jti(token.jti())
                                    .expiresAt(expiresAt)
                                    .build());
                        }
                    });
        }
        revokedAccessTokenRepository.deleteExpired(now);
    }

    /** 내 로그인 세션 목록. currentRefreshToken 이 주어지면 그 세션을 current 로 표시한다. */
    @Transactional(readOnly = true)
    public List<UserSessionResponse> getSessions(UUID userId, String currentRefreshToken) {
        String currentHash = currentRefreshToken == null || currentRefreshToken.isBlank()
                ? null : TokenHasher.sha256(currentRefreshToken.trim());
        return refreshTokenRepository.findActiveByUserId(userId, LocalDateTime.now()).stream()
                .map(row -> {
                    boolean current = currentHash != null && (currentHash.equals(row.getToken())
                            || currentRefreshToken.trim().equals(row.getToken())
                            || currentHash.equals(row.getPreviousToken()));
                    LocalDateTime lastUsed = row.getLastUsedAt() != null ? row.getLastUsedAt()
                            : row.getRotatedAt() != null ? row.getRotatedAt() : row.getCreatedAt();
                    return UserSessionResponse.builder()
                            .id(row.getId())
                            .createdAt(row.getCreatedAt())
                            .lastUsedAt(lastUsed)
                            .current(current)
                            .build();
                })
                .toList();
    }

    /** 모든 기기 로그아웃: 리프레시 토큰을 모두 지우고, 지금(초 단위)까지 발급된 액세스 토큰을 거부한다. */
    @Transactional
    public void logoutAll(UUID userId) {
        User user = userRepository.findByIdForUpdate(userId)
                .orElseThrow(() -> new ResourceNotFoundException("사용자", null));
        refreshTokenRepository.deleteAllByUserId(userId);
        user.setTokensValidAfter(LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS));
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
                .orElseThrow(() -> new InvalidTokenException("로그인이 만료됐어요. 다시 로그인해 주세요."));
        if (parsed.isAccess()) {
            throw new InvalidTokenException("로그인이 만료됐어요. 다시 로그인해 주세요.");
        }

        LocalDateTime now = LocalDateTime.now();
        String tokenHash = TokenHasher.sha256(refreshToken);

        // 해시로 저장된 행을 우선 찾고, 해시 도입 이전에 원문으로 저장된 행도 허용한다.
        List<RefreshToken> stored = refreshTokenRepository.findAllByTokenInForUpdate(List.of(tokenHash, refreshToken));
        if (!stored.isEmpty()) {
            RefreshToken storedToken = stored.get(0);
            if (storedToken.getExpiryDate().isBefore(now)
                    || !storedToken.getUserId().toString().equals(parsed.userId())) {
                throw new InvalidTokenException("로그인이 만료됐어요. 다시 로그인해 주세요.");
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

        throw new InvalidTokenException("로그인이 만료됐어요. 다시 로그인해 주세요.");
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

    private User createUser(String kakaoId, String nickname, String email, String profileImage) {
        return userRepository.save(User.builder()
                .kakaoId(kakaoId)
                .customId(generateUniqueCustomId())
                .email(email)
                .nickname(nickname)
                .profileImage(profileImage)
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
