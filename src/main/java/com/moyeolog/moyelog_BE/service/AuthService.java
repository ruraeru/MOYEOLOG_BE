package com.moyeolog.moyelog_BE.service;

import com.moyeolog.moyelog_BE.dto.AuthLinkKakaoRequest;
import com.moyeolog.moyelog_BE.dto.AuthSyncRequest;
import com.moyeolog.moyelog_BE.dto.AuthSyncResponse;
import com.moyeolog.moyelog_BE.dto.UserMeResponse;
import com.moyeolog.moyelog_BE.dto.UserSessionResponse;
import com.moyeolog.moyelog_BE.entity.RevokedAccessToken;
import com.moyeolog.moyelog_BE.event.LiveCloseEvent;
import com.moyeolog.moyelog_BE.exception.ResourceNotFoundException;
import com.moyeolog.moyelog_BE.repository.RevokedAccessTokenRepository;
import com.moyeolog.moyelog_BE.entity.RefreshToken;
import com.moyeolog.moyelog_BE.entity.User;
import com.moyeolog.moyelog_BE.exception.BadRequestException;
import com.moyeolog.moyelog_BE.exception.ConflictException;
import com.moyeolog.moyelog_BE.exception.InvalidTokenException;
import com.moyeolog.moyelog_BE.repository.RefreshTokenRepository;
import com.moyeolog.moyelog_BE.repository.UserRepository;
import com.moyeolog.moyelog_BE.security.JwtProvider;
import com.moyeolog.moyelog_BE.security.TokenHasher;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final RevokedAccessTokenRepository revokedAccessTokenRepository;
    private final JwtProvider jwtProvider;
    private final PasswordEncoder passwordEncoder;
    private final org.springframework.context.ApplicationEventPublisher eventPublisher;
    private final SecureRandom random = new SecureRandom();
    private static final long ROTATION_GRACE_SECONDS = 60;
    private static final String CHARACTERS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";

    public static final String LOGIN_FAILED_MESSAGE = "이메일 또는 비밀번호가 올바르지 않아요.";
    public static final String EMAIL_IN_USE_MESSAGE = "이미 다른 계정에서 쓰는 이메일이에요.";
    public static final String KAKAO_IN_USE_MESSAGE = "이미 다른 계정에 연결된 카카오 계정이에요.";
    private static final String SESSION_EXPIRED_MESSAGE = "로그인이 만료됐어요. 다시 로그인해 주세요.";
    /** 계정 단위: 연속으로 이만큼 틀리면 LOGIN_LOCK_MINUTES 동안 그 계정의 비밀번호 로그인을 받지 않는다. */
    static final int MAX_FAILED_LOGINS = 100;
    /** (이메일, IP) 단위: 연속으로 이만큼 틀리면 그 조합만 LOGIN_LOCK_MINUTES 동안 막는다. */
    static final int MAX_FAILED_LOGINS_PER_PAIR = 10;
    /** 이 개수를 넘으면 오래된 항목을 지운다. */
    private static final int LOGIN_PAIR_MAX_ENTRIES = 50_000;
    static final long LOGIN_LOCK_MINUTES = 10;
    /** BCrypt 가 보는 최대 길이 */
    static final int MAX_PASSWORD_BYTES = 72;

    // shortcut: 앱 메모리(인스턴스 하나 기준, 재시작하면 초기화). 백엔드를 여러 대로 늘리면 DB·Redis 로 옮긴다.
    private final ConcurrentHashMap<String, LoginPairFailures> loginPairFailures = new ConcurrentHashMap<>();

    /** 없는 계정에도 BCrypt 비교를 한 번 하기 위한 해시(처음 쓸 때 만든다). */
    private volatile String dummyPasswordHash;

    /**
     * 카카오 로그인 결과로 사용자를 찾거나 만든다.
     * <ol>
     *   <li>kakaoId 로 찾는다.</li>
     *   <li>못 찾으면, 카카오가 인증한 이메일(emailVerified=true)이고 그 이메일의 기존 계정이 아직 한 번도
     *       카카오에 연결되지 않았을 때(kakao_linked_at IS NULL)만 그 계정에 kakaoId 를 붙인다.
     *       이메일로 가입한 계정(kakao_id IS NULL)은 email_verified_at 이 있어야 한다.</li>
     *   <li>그 밖에는 새 계정. 이메일이 이미 다른 계정에 있으면 새 계정의 email 은 null.</li>
     * </ol>
     * 어떤 경우든 연결된 계정의 kakao_linked_at 을 채운다.
     */
    @Transactional
    public AuthSyncResponse syncUser(AuthSyncRequest request) {
        if (request == null) {
            throw new BadRequestException("kakaoId가 필요해요.");
        }
        String kakaoId = requireKakaoId(request.getKakaoId());
        String nickname = UserService.sanitizeNickname(request.getNickname());
        if (nickname.isEmpty()) {
            nickname = "사용자_" + kakaoId.substring(Math.max(0, kakaoId.length() - 4));
        }

        String email = normalizeEmail(request.getEmail());
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
        } else {
            user = linkableByEmail(email, emailVerified);
            if (user != null) {
                user.linkKakao(kakaoId, now);
            } else {
                // 이메일은 인증된 것만, claimVerifiedEmail 로 넣는다(아래).
                user = createUser(kakaoId, nickname, profileImage);
            }
        }
        user.markKakaoLinked(now);
        user.setLastKakaoLoginAt(now);
        applyVerifiedKakaoEmail(user, email, emailVerified, now);
        return issueTokens(user);
    }

    /**
     * 검증된 이메일이고, 그 이메일의 계정이 정확히 하나이며 아직 카카오에 연결된 적이 없을 때만 돌려준다.
     * 카카오 없이 이메일로 가입한 계정(kakao_id IS NULL)은 그 이메일이 인증돼 있어야 한다.
     * 카카오 연결을 해제한 계정은 kakao_linked_at 이 남아 있어 다시 자동으로 붙지 않는다.
     */
    private User linkableByEmail(String email, boolean emailVerified) {
        if (email == null || !emailVerified) {
            return null;
        }
        List<User> candidates = exactEmailMatches(userRepository.findAllByEmailIgnoreCase(email), email);
        if (candidates.size() != 1) {
            return null;
        }
        User candidate = candidates.get(0);
        if (candidate.getKakaoLinkedAt() != null) {
            return null;
        }
        return candidate.getKakaoId() != null || candidate.getEmailVerifiedAt() != null ? candidate : null;
    }

    /**
     * 카카오가 <b>인증됨</b>으로 알려 준 이메일만 계정에 반영한다(미인증 이메일은 저장하지 않는다).
     * 계정 이메일과 같으면(정확히) 인증 표시, 계정 이메일이 비어 있으면 채운다. 다른 이메일이 있으면 그대로 둔다.
     * 인증된 다른 계정이 그 이메일을 쓰면 아무것도 하지 않는다. 같은 이메일의 미인증 계정은 비운다(claimVerifiedEmail).
     */
    private void applyVerifiedKakaoEmail(User user, String email, boolean emailVerified, LocalDateTime now) {
        if (!emailVerified || email == null) {
            return;
        }
        boolean same = email.equals(normalizeEmail(user.getEmail()));
        if (same ? user.getEmailVerifiedAt() != null : user.getEmail() != null) {
            return;
        }
        if (verifiedEmailTakenByOther(email, user)) {
            return;
        }
        claimVerifiedEmail(user, email, now);
    }

    /** DB 콜레이션(악센트·대소문자 무시)으로 찾은 결과에서 정규화 값이 정확히 같은 것만 남긴다. */
    private static List<User> exactEmailMatches(List<User> users, String email) {
        return users.stream().filter(u -> email.equals(normalizeEmail(u.getEmail()))).toList();
    }

    /**
     * 이메일 + 비밀번호 로그인 (프론트 서버만 부른다).
     * 계정이 없음·비밀번호 없음·틀림·잠김을 모두 같은 401 로 돌려준다. 계정이 없어도 BCrypt 비교를 한 번 한다.
     * 잠금은 두 단계다.
     * <ul>
     *   <li>(이메일, clientIp) 조합: 연속 10회 실패하면 그 조합만 10분 차단. 없는 이메일도 똑같이 센다.
     *       차단 중 시도는 계정 단위 횟수에 넣지 않는다.</li>
     *   <li>계정: 연속 100회 실패하면 10분 잠금(여러 IP 로 나눠 추측하는 것 방지).</li>
     * </ul>
     * 잠긴 동안에는 맞는 비밀번호도 401 이다(429 를 주면 계정이 있다는 것이 드러난다).
     * 실패 횟수는 401 을 던져도 저장돼야 하므로 롤백하지 않는다.
     */
    @Transactional(noRollbackFor = InvalidTokenException.class)
    public AuthSyncResponse loginWithPassword(String rawEmail, String password, String clientIp) {
        String email = normalizeEmail(rawEmail);
        boolean usablePassword = password != null && !password.isEmpty()
                && password.getBytes(StandardCharsets.UTF_8).length <= MAX_PASSWORD_BYTES;
        LocalDateTime now = LocalDateTime.now();
        String pairKey = loginPairKey(email, clientIp);

        // id 만 먼저 찾고 행 잠금으로 다시 읽는다(동시 시도에서도 실패 횟수가 정확하게 쌓이게).
        // DB 콜레이션은 악센트를 무시하므로 정규화한 값이 정확히 같은 계정만 받는다.
        User user = null;
        if (email != null) {
            for (UUID id : userRepository.findIdsByEmailIgnoreCase(email)) {
                User candidate = userRepository.findByIdForUpdate(id).orElse(null);
                if (candidate != null && email.equals(normalizeEmail(candidate.getEmail()))) {
                    user = candidate;
                    break;
                }
            }
        }
        String hash = user != null && user.getPasswordHash() != null ? user.getPasswordHash() : dummyPasswordHash();
        boolean matches = passwordEncoder.matches(usablePassword ? password : "-", hash) && usablePassword;

        if (loginPairBlocked(pairKey, now)) {
            throw new InvalidTokenException(LOGIN_FAILED_MESSAGE);
        }
        if (user == null || user.getPasswordHash() == null) {
            recordLoginPairFailure(pairKey, now);
            throw new InvalidTokenException(LOGIN_FAILED_MESSAGE);
        }
        if (user.getLoginLockedUntil() != null && user.getLoginLockedUntil().isAfter(now)) {
            recordLoginPairFailure(pairKey, now);
            throw new InvalidTokenException(LOGIN_FAILED_MESSAGE);
        }
        if (!matches) {
            recordLoginPairFailure(pairKey, now);
            int failed = user.getFailedLoginCount() + 1;
            if (failed >= MAX_FAILED_LOGINS) {
                user.setLoginLockedUntil(now.plusMinutes(LOGIN_LOCK_MINUTES));
                failed = 0;
            }
            user.setFailedLoginCount(failed);
            throw new InvalidTokenException(LOGIN_FAILED_MESSAGE);
        }
        loginPairFailures.remove(pairKey);
        user.setFailedLoginCount(0);
        user.setLoginLockedUntil(null);
        return issueTokens(user);
    }

    /** (이메일, IP) 조합의 연속 실패. lastFailureAt 에서 LOGIN_LOCK_MINUTES 가 지나면 처음부터 센다. */
    private record LoginPairFailures(int count, LocalDateTime lastFailureAt, LocalDateTime blockedUntil) {
    }

    private static String loginPairKey(String email, String clientIp) {
        String ip = clientIp == null || clientIp.isBlank() ? "unknown" : clientIp.trim();
        if (ip.length() > 64) {
            ip = ip.substring(0, 64);
        }
        return (email == null ? "" : email) + "\n" + ip;
    }

    /** 비밀번호를 재설정하면 그 이메일의 (이메일, IP) 연속 실패·차단을 모두 지운다(메일을 받은 본인이 바로 로그인할 수 있게). */
    public void clearLoginFailures(String rawEmail) {
        String email = normalizeEmail(rawEmail);
        if (email == null) {
            return;
        }
        String prefix = email + "\n";
        loginPairFailures.keySet().removeIf(k -> k.startsWith(prefix));
    }

    private boolean loginPairBlocked(String key, LocalDateTime now) {
        LoginPairFailures f = loginPairFailures.get(key);
        return f != null && f.blockedUntil() != null && f.blockedUntil().isAfter(now);
    }

    private void recordLoginPairFailure(String key, LocalDateTime now) {
        loginPairFailures.compute(key, (k, f) -> {
            int count = f == null || f.lastFailureAt().isBefore(now.minusMinutes(LOGIN_LOCK_MINUTES)) ? 1 : f.count() + 1;
            return count >= MAX_FAILED_LOGINS_PER_PAIR
                    ? new LoginPairFailures(0, now, now.plusMinutes(LOGIN_LOCK_MINUTES))
                    : new LoginPairFailures(count, now, null);
        });
        if (loginPairFailures.size() > LOGIN_PAIR_MAX_ENTRIES) {
            LocalDateTime cutoff = now.minusMinutes(LOGIN_LOCK_MINUTES);
            loginPairFailures.values().removeIf(f -> f.lastFailureAt().isBefore(cutoff)
                    && (f.blockedUntil() == null || f.blockedUntil().isBefore(now)));
        }
    }

    /**
     * 지금 로그인한 사용자(accessToken)에 카카오 계정을 붙인다 (프론트 서버만 부른다). 계정 병합은 하지 않는다.
     * 그 kakaoId 가 다른 계정에 있거나, 이 사용자에게 이미 다른 kakaoId 가 있으면 409.
     */
    @Transactional
    public AuthSyncResponse linkKakao(AuthLinkKakaoRequest request) {
        if (request == null) {
            throw new BadRequestException("kakaoId가 필요해요.");
        }
        User user = loggedInUserForUpdate(request.getAccessToken());
        String kakaoId = requireKakaoId(request.getKakaoId());

        User owner = userRepository.findByKakaoId(kakaoId).orElse(null);
        if (owner != null && !owner.getId().equals(user.getId())) {
            throw new ConflictException(KAKAO_IN_USE_MESSAGE);
        }
        if (user.getKakaoId() != null && !user.getKakaoId().equals(kakaoId)) {
            throw new ConflictException("이미 다른 카카오 계정이 연결돼 있어요. 연결을 해제한 뒤 다시 시도해 주세요.");
        }

        LocalDateTime now = LocalDateTime.now();
        String email = normalizeEmail(request.getEmail());
        boolean emailVerified = Boolean.TRUE.equals(request.getEmailVerified());
        // 조회·flush 가 kakaoId 변경보다 먼저 일어나게 이메일을 먼저 처리한다(유니크 경합은 아래 saveAndFlush 에서 409).
        applyVerifiedKakaoEmail(user, email, emailVerified, now);
        user.updateKakaoId(kakaoId);
        user.setKakaoLinkedAt(now);
        user.setLastKakaoLoginAt(now);
        String profileImage = request.getProfileImage();
        if ((user.getProfileImage() == null || user.getProfileImage().isEmpty())
                && profileImage != null && profileImage.length() <= 255) {
            user.setProfileImage(profileImage);
        }
        try {
            userRepository.saveAndFlush(user);
        } catch (DataIntegrityViolationException e) {
            // 같은 kakaoId 를 다른 요청이 먼저 붙였다(유니크 제약).
            throw new ConflictException(KAKAO_IN_USE_MESSAGE);
        }
        return issueTokens(user);
    }

    /**
     * 카카오 연결 해제. 비밀번호와 인증된 이메일이 있어야 하고(다른 로그인 방법이 남아야 하므로), 현재 비밀번호가 맞아야 한다.
     * kakao_linked_at 은 그대로 둔다: 지워지면 옛 계정 이전 규칙으로 아무 카카오 계정이나 다시 붙을 수 있다.
     */
    @Transactional
    public UserMeResponse unlinkKakao(UUID userId, String currentPassword) {
        User user = userRepository.findByIdForUpdate(userId)
                .orElseThrow(() -> new ResourceNotFoundException("사용자", null));
        if (user.getPasswordHash() == null || user.getEmail() == null || user.getEmailVerifiedAt() == null) {
            throw new BadRequestException("비밀번호를 먼저 설정해 주세요.");
        }
        // 세션만 가진 사람이 로그인 방법을 바꾸지 못하게 현재 비밀번호로 다시 확인한다.
        boolean usable = currentPassword != null && !currentPassword.isEmpty()
                && currentPassword.getBytes(StandardCharsets.UTF_8).length <= MAX_PASSWORD_BYTES;
        if (!usable || !passwordEncoder.matches(currentPassword, user.getPasswordHash())) {
            throw new BadRequestException("현재 비밀번호가 올바르지 않아요.");
        }
        user.updateKakaoId(null);
        return UserMeResponse.from(user);
    }

    /**
     * 인증으로 확정된 이메일을 owner 에 준다. 같은 이메일(DB 콜레이션 기준)을 가진 다른 <b>미인증</b> 계정의 email 은
     * 먼저 비우고 flush 한다(유니크 제약). 다른 <b>인증된</b> 계정이 그 이메일을 쓰고 있으면 409.
     * 호출 전 owner 의 email 에 이 값을 미리 넣어 두거나, 이 email 을 가진 새 User 를 저장해 두면 안 된다
     * (이 안의 조회가 auto-flush 하면서 유니크 제약에 걸린다). 새 계정은 email 없이 저장한 뒤 이것을 부른다.
     * 트랜잭션 안에서 불러야 한다.
     *
     * @param email normalizeEmail 을 거친 값
     */
    @Transactional
    public void claimVerifiedEmail(User owner, String email, LocalDateTime now) {
        if (email == null) {
            throw new BadRequestException("이메일 형식이 올바르지 않아요.");
        }
        List<User> others = userRepository.findAllByEmailIgnoreCase(email).stream()
                .filter(u -> owner.getId() == null || !u.getId().equals(owner.getId()))
                .toList();
        if (others.stream().anyMatch(u -> u.getEmailVerifiedAt() != null)) {
            throw new ConflictException(EMAIL_IN_USE_MESSAGE);
        }
        if (!others.isEmpty()) {
            others.forEach(u -> u.updateEmail(null));
            userRepository.flush();
        }
        owner.updateEmail(email);
        owner.setEmailVerifiedAt(now);
    }

    /** 인증된 다른 계정이 이 이메일(DB 콜레이션 기준)을 쓰고 있는지. 미인증 계정은 세지 않는다. */
    public boolean verifiedEmailTakenByOther(String email, User self) {
        return userRepository.findAllByEmailIgnoreCase(email).stream()
                .anyMatch(u -> u.getEmailVerifiedAt() != null
                        && (self == null || self.getId() == null || !u.getId().equals(self.getId())));
    }

    /** 이메일은 저장·비교 전에 trim + 소문자. 비었거나 255자를 넘으면 null. */
    public static String normalizeEmail(String raw) {
        if (raw == null) {
            return null;
        }
        String email = raw.trim().toLowerCase(Locale.ROOT);
        return email.isEmpty() || email.length() > 255 ? null : email;
    }

    private String requireKakaoId(String kakaoId) {
        if (kakaoId == null || kakaoId.isBlank()) {
            throw new BadRequestException("kakaoId가 필요해요.");
        }
        if (kakaoId.length() > 255) {
            throw new BadRequestException("kakaoId가 너무 길어요.");
        }
        return kakaoId;
    }

    /**
     * JwtAuthenticationFilter 와 같은 기준으로 액세스 토큰을 확인하고 그 사용자를 행 잠금으로 읽는다:
     * 액세스 토큰(typ=access)·서명·만료, 거부 목록(jti), 사용자 존재, 모든 기기 로그아웃 이전 발급 거부.
     * 예전 형식(typ 없음) 토큰은 받지 않는다.
     */
    private User loggedInUserForUpdate(String accessToken) {
        if (accessToken == null || accessToken.isBlank()) {
            throw new InvalidTokenException(SESSION_EXPIRED_MESSAGE);
        }
        JwtProvider.ParsedToken token = jwtProvider.parse(accessToken.trim())
                .filter(JwtProvider.ParsedToken::isAccess)
                .orElseThrow(() -> new InvalidTokenException(SESSION_EXPIRED_MESSAGE));
        UUID userId;
        try {
            userId = UUID.fromString(token.userId());
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new InvalidTokenException(SESSION_EXPIRED_MESSAGE);
        }
        if (token.jti() != null && revokedAccessTokenRepository.existsById(token.jti())) {
            throw new InvalidTokenException(SESSION_EXPIRED_MESSAGE);
        }
        User user = userRepository.findByIdForUpdate(userId)
                .orElseThrow(() -> new InvalidTokenException(SESSION_EXPIRED_MESSAGE));
        // JwtAuthenticationFilter.issuedBeforeLogoutAll 과 같은 규칙(그 초 안에 발급된 토큰까지 거부)
        LocalDateTime validAfter = user.getTokensValidAfter();
        if (validAfter != null && (token.issuedAt() == null
                || token.issuedAt().getEpochSecond() <= validAfter.atZone(ZoneId.systemDefault()).toEpochSecond())) {
            throw new InvalidTokenException(SESSION_EXPIRED_MESSAGE);
        }
        return user;
    }

    /** 새 액세스·리프레시 토큰을 발급하고(기기마다 새 행) sync 와 같은 모양으로 돌려준다. */
    private AuthSyncResponse issueTokens(User user) {
        if (user.getCustomId() == null) {
            user.updateCustomId(generateUniqueCustomId());
        }
        String accessToken = jwtProvider.createAccessToken(user.getId().toString());
        String refreshToken = jwtProvider.createRefreshToken(user.getId().toString());
        addRefreshToken(user.getId(), refreshToken);
        return AuthSyncResponse.builder()
                .accessToken(accessToken)
                .refreshToken(refreshToken)
                .user(UserMeResponse.from(user))
                .build();
    }

    private String dummyPasswordHash() {
        String hash = dummyPasswordHash;
        if (hash == null) {
            hash = passwordEncoder.encode("timing-equalizer-" + UUID.randomUUID());
            dummyPasswordHash = hash;
        }
        return hash;
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
                        // 이 기기(이 토큰)로 연 실시간 연결을 커밋 뒤에 닫는다
                        closeLiveConnections(token.userId(), token.jti());
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
        closeLiveConnections(userId.toString(), null);
    }

    private void closeLiveConnections(String userId, String jti) {
        try {
            eventPublisher.publishEvent(new LiveCloseEvent(UUID.fromString(userId), jti));
        } catch (IllegalArgumentException ignored) {
            // 사용자 ID 가 UUID 가 아닌 토큰은 연결도 없다
        }
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

    private User createUser(String kakaoId, String nickname, String profileImage) {
        return userRepository.save(User.builder()
                .kakaoId(kakaoId)
                .customId(generateUniqueCustomId())
                .nickname(nickname)
                .profileImage(profileImage)
                .build());
    }

    public String generateUniqueCustomId() {
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
