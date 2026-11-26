package com.moyeolog.moyelog_BE.account;

import com.moyeolog.moyelog_BE.account.EmailVerification.Purpose;
import com.moyeolog.moyelog_BE.entity.User;
import com.moyeolog.moyelog_BE.event.UserDeletingEvent;
import com.moyeolog.moyelog_BE.exception.BadRequestException;
import com.moyeolog.moyelog_BE.exception.ConflictException;
import com.moyeolog.moyelog_BE.exception.InvalidTokenException;
import com.moyeolog.moyelog_BE.exception.ResourceNotFoundException;
import com.moyeolog.moyelog_BE.exception.UnauthorizedAccessException;
import com.moyeolog.moyelog_BE.repository.UserRepository;
import com.moyeolog.moyelog_BE.security.TokenHasher;
import com.moyeolog.moyelog_BE.service.AuthService;
import com.moyeolog.moyelog_BE.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.Base64;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * 이메일 가입·인증·비밀번호 찾기·비밀번호 설정·이메일 등록.
 * 계정이 있는지 응답으로 드러내지 않는다: 메일 요청은 계정 유무와 관계없이 같은 응답이고, 메일은 커밋 뒤 별도 스레드에서 보낸다.
 * 확인되지 않은 이메일(email_verified_at IS NULL)의 계정에는 메일 링크로 비밀번호를 붙이지 않는다.
 */
@Service
@RequiredArgsConstructor
public class AccountService {

    static final String INVALID_TOKEN_MESSAGE = "인증 링크가 올바르지 않거나 만료됐어요. 다시 요청해 주세요.";
    static final String WRONG_PASSWORD_MESSAGE = "비밀번호가 올바르지 않아요.";
    static final String USED_TOKEN_MESSAGE = "이미 사용한 링크예요.";
    static final String LOCKED_TOKEN_MESSAGE = "비밀번호를 여러 번 틀려 이 링크는 더 쓸 수 없어요. 가입을 다시 신청해 주세요.";
    static final String EXPIRED_TOKEN_MESSAGE = "링크가 만료됐어요. 다시 요청해 주세요.";
    static final String SAME_PASSWORD_MESSAGE = "지금과 같은 비밀번호예요. 다른 비밀번호를 입력해 주세요.";
    static final String WRONG_CURRENT_PASSWORD_MESSAGE = "지금 비밀번호가 올바르지 않아요.";
    static final String KAKAO_RELOGIN_MESSAGE = "보안을 위해 카카오로 다시 로그인한 뒤 10분 안에 다시 시도해 주세요.";
    private static final int KAKAO_RELOGIN_MINUTES = 10;
    static final String EMAIL_LOGIN_REQUIRED_MESSAGE = "이메일을 등록한 계정으로 로그인한 뒤 다시 눌러 주세요.";
    private static final Pattern EMAIL_PATTERN = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
    private static final int TOKEN_MINUTES = 30;
    private static final long MINUTE_MS = 60_000L;
    private static final long HOUR_MS = 60 * MINUTE_MS;
    private static final long DAY_MS = 24 * HOUR_MS;
    private static final int SENDS_PER_HOUR = 5;
    private static final int EMAIL_CHANGES_PER_DAY = 5;
    private static final int RATE_MAP_PURGE_SIZE = 10_000;

    private final UserRepository userRepository;
    private final EmailVerificationRepository verificationRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuthService authService;
    private final AccountMailer mailer;
    private final SecureRandom random = new SecureRandom();
    // shortcut: 발송 제한을 앱 메모리에 둔다(재시작하면 초기화, 인스턴스가 하나일 때만 정확). 여러 대로 늘리면 DB·Redis 로 옮긴다.
    private final Map<String, Deque<Long>> recentSends = new ConcurrentHashMap<>();
    private final Map<String, Deque<Long>> recentEmailChanges = new ConcurrentHashMap<>();

    // ─── 공개 (/api/account/**) ─────────────────────────────────────

    /**
     * 이메일 가입 요청. 계정 유무와 관계없이 같은 결과(202).
     * 그 이메일이 인증된 계정이 있으면 '이미 가입됨' + 비밀번호 설정 링크, 미인증이지만 카카오 계정이 갖고 있으면
     * 링크 없는 안내 메일, 그 밖에는(카카오 없는 미인증 계정만 있어도) SIGNUP 진행.
     */
    @Transactional
    public void signup(String rawEmail, String password, String rawNickname) {
        String email = normalizeEmail(rawEmail);
        // 프로필의 닉네임 수정과 같은 규칙(2~20자, 쓸 수 없는 문자 400). 고쳐서 받지 않는다.
        String nickname = UserService.validateNickname(rawNickname);
        validatePassword(password);
        mailer.requireAvailable();
        // 비밀번호를 버리는 경우에도 해시해 응답 시간을 맞춘다.
        String passwordHash = passwordEncoder.encode(password);
        if (!allowSend(email)) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        List<User> matches = exactMatches(email);
        User owner = verifiedOwner(matches);
        if (owner != null) {
            String token = issue(Purpose.PASSWORD, email, owner.getId(), null, null, now);
            mailer.sendAlreadyRegistered(email, token);
        } else if (hasUnverifiedKakaoHolder(matches)) {
            // 기존 카카오 사용자가 같은 이메일로 가입하면 확정 때 그 계정의 이메일이 지워져 계정이 둘로 갈린다(review2 3번).
            mailer.sendKakaoAccountNotice(email);
        } else {
            String token = issue(Purpose.SIGNUP, email, null, passwordHash, nickname, now);
            mailer.sendSignupVerify(email, token);
        }
    }

    /**
     * 메일 링크 확정. 메일을 연 사람이 신청한 사람인지 확인한다(리뷰 1).
     * SIGNUP: 가입 때 정한 비밀번호가 맞아야 계정을 만든다. 틀리면 400, 토큰 하나에 5번 틀리면 그 토큰은 못 쓴다
     *         (틀린 횟수를 남기려고 BadRequestException 은 롤백하지 않는다. 그 전에 다른 쓰기는 없다).
     * EMAIL: 토큰의 사용자로 로그인한 요청이어야 한다. 아니면 401.
     * 확정 때 같은 이메일을 가진 다른 미인증 계정의 email 은 비운다(AuthService.claimVerifiedEmail).
     */
    @Transactional(noRollbackFor = BadRequestException.class)
    public AccountDtos.VerifyResponse verify(String token, String password, String principalUserId) {
        LocalDateTime now = LocalDateTime.now();
        EmailVerification v = usableToken(token, now);
        switch (v.getPurpose()) {
            case SIGNUP -> {
                if (!passwordMatches(password, v.getPasswordHash())) {
                    v.recordFailedAttempt(now);
                    int left = EmailVerification.MAX_FAILED_ATTEMPTS - v.getFailedAttempts();
                    throw new BadRequestException(left > 0 ? wrongPasswordMessage(left) : LOCKED_TOKEN_MESSAGE);
                }
                if (verifiedOwner(v.getEmail()) != null) {
                    throw new ConflictException("이미 가입된 이메일이에요. 로그인해 주세요.");
                }
                User user = userRepository.save(User.builder()
                        .kakaoId(null)
                        .customId(authService.generateUniqueCustomId())
                        .nickname(v.getNickname())
                        .passwordHash(v.getPasswordHash())
                        .build());
                authService.claimVerifiedEmail(user, v.getEmail(), now);
            }
            case EMAIL -> {
                if (principalUserId == null || !principalUserId.equals(v.getUserId().toString())) {
                    throw new InvalidTokenException(EMAIL_LOGIN_REQUIRED_MESSAGE);
                }
                User user = userRepository.findByIdForUpdate(v.getUserId())
                        .orElseThrow(() -> new BadRequestException(INVALID_TOKEN_MESSAGE));
                User owner = verifiedOwner(v.getEmail());
                if (owner != null && !owner.getId().equals(user.getId())) {
                    throw new ConflictException("이미 다른 계정이 쓰는 이메일이에요.");
                }
                String oldVerifiedEmail = user.getEmailVerifiedAt() != null ? user.getEmail() : null;
                authService.claimVerifiedEmail(user, v.getEmail(), now);
                if (oldVerifiedEmail != null && !sameEmail(v.getEmail(), oldVerifiedEmail)) {
                    mailer.sendEmailChangedNotice(oldVerifiedEmail);
                }
            }
            default -> throw new BadRequestException(INVALID_TOKEN_MESSAGE);
        }
        v.markUsed(now);
        return new AccountDtos.VerifyResponse(v.getPurpose().name(), v.getEmail());
    }

    /** 틀린 비밀번호 문구. 남은 기회를 알려 준다(다 쓰면 그 링크는 못 쓴다). */
    static String wrongPasswordMessage(int left) {
        return WRONG_PASSWORD_MESSAGE + " 남은 기회 " + left + "번. 기억나지 않으면 가입을 다시 신청해 주세요.";
    }

    /**
     * 메일 링크를 쓰지 않고 상태만 본다(/verify 화면이 열자마자 쓸 수 없는 링크를 알리고, 인증할 주소를 보여 주려고).
     * 쓸 수 없으면 verify 와 같은 400 문구. 토큰을 가진 사람(메일 받은 사람)에게만 이메일을 준다.
     */
    @Transactional(readOnly = true)
    public AccountDtos.VerifyResponse previewToken(String token) {
        EmailVerification v = usableToken(token, LocalDateTime.now(), false);
        if (v.getPurpose() == Purpose.PASSWORD) {
            throw new BadRequestException(INVALID_TOKEN_MESSAGE);
        }
        return new AccountDtos.VerifyResponse(v.getPurpose().name(), v.getEmail());
    }

    /**
     * 비밀번호 찾기(처음 설정 포함). 인증된 이메일의 계정이 있을 때만 링크 메일, 미인증 카카오 계정만 있으면 안내 메일.
     * 응답은 항상 같다(202).
     */
    @Transactional
    public void requestPasswordReset(String rawEmail) {
        String email = normalizeEmail(rawEmail);
        mailer.requireAvailable();
        if (!allowSend(email)) {
            return;
        }
        List<User> matches = exactMatches(email);
        User owner = verifiedOwner(matches);
        if (owner == null) {
            if (hasUnverifiedKakaoHolder(matches)) {
                mailer.sendKakaoAccountNotice(email);
            }
            return;
        }
        String token = issue(Purpose.PASSWORD, email, owner.getId(), null, null, LocalDateTime.now());
        mailer.sendPasswordReset(email, token);
    }

    /** 메일 링크로 비밀번호 저장. 잠금 해제, 남은 링크 무효화, 모든 기기 로그아웃, 알림 메일. */
    @Transactional
    public void confirmPasswordReset(String token, String newPassword) {
        validatePassword(newPassword);
        LocalDateTime now = LocalDateTime.now();
        EmailVerification v = usableToken(token, now);
        if (v.getPurpose() != Purpose.PASSWORD) {
            throw new BadRequestException(INVALID_TOKEN_MESSAGE);
        }
        User user = userRepository.findByIdForUpdate(v.getUserId())
                .orElseThrow(() -> new BadRequestException(INVALID_TOKEN_MESSAGE));
        // 메일을 보낸 뒤 이메일이 바뀌었거나 인증이 풀렸으면 그 링크로는 바꾸지 않는다.
        if (user.getEmailVerifiedAt() == null || !sameEmail(v.getEmail(), user.getEmail())) {
            throw new BadRequestException(INVALID_TOKEN_MESSAGE);
        }
        user.setPasswordHash(passwordEncoder.encode(newPassword));
        user.setFailedLoginCount(0);
        user.setLoginLockedUntil(null);
        v.markUsed(now);
        verificationRepository.markAllUsed(user.getId(), Purpose.PASSWORD, now);
        authService.logoutAll(user.getId());
        authService.clearLoginFailures(user.getEmail());
        mailer.sendPasswordChangedNotice(user.getEmail());
    }

    /** 재설정 링크를 열었을 때 미리 확인(소모하지 않는다). 쓸 수 없으면 confirm 과 같은 400. */
    @Transactional
    public void checkPasswordResetToken(String token) {
        EmailVerification v = usableToken(token, LocalDateTime.now());
        if (v.getPurpose() != Purpose.PASSWORD) {
            throw new BadRequestException(INVALID_TOKEN_MESSAGE);
        }
        User user = userRepository.findById(v.getUserId())
                .orElseThrow(() -> new BadRequestException(INVALID_TOKEN_MESSAGE));
        if (user.getEmailVerifiedAt() == null || !sameEmail(v.getEmail(), user.getEmail())) {
            throw new BadRequestException(INVALID_TOKEN_MESSAGE);
        }
    }

    // ─── 로그인한 사용자 (/api/users/me/**) ─────────────────────────

    /** 처음 설정은 인증된 이메일과 10분 안의 카카오 로그인이 있어야 한다. 바꾸는 경우(현재 비밀번호 필요)는 끝에 모든 기기 로그아웃. 둘 다 알림 메일. */
    @Transactional
    public void changePassword(UUID userId, String currentPassword, String newPassword) {
        validatePassword(newPassword);
        User user = userRepository.findByIdForUpdate(userId)
                .orElseThrow(() -> new ResourceNotFoundException("사용자", userId));
        boolean changing = user.getPasswordHash() != null;
        if (changing) {
            if (!passwordMatches(currentPassword, user.getPasswordHash())) {
                throw new BadRequestException(WRONG_CURRENT_PASSWORD_MESSAGE);
            }
            // 바뀌는 것 없이 모든 기기만 로그아웃되지 않게
            if (passwordMatches(newPassword, user.getPasswordHash())) {
                throw new BadRequestException(SAME_PASSWORD_MESSAGE);
            }
        } else if (user.getEmailVerifiedAt() == null) {
            throw new BadRequestException("이메일을 먼저 인증해 주세요.");
        } else {
            requireRecentKakaoLogin(user);
        }
        user.setPasswordHash(passwordEncoder.encode(newPassword));
        if (user.getEmailVerifiedAt() != null && user.getEmail() != null) {
            mailer.sendPasswordChangedNotice(user.getEmail());
        }
        if (changing) {
            authService.logoutAll(userId);
        }
    }

    /**
     * 이메일 등록·변경 메일. 비밀번호가 있는 계정은 현재 비밀번호, 없는 계정은 10분 안의 카카오 로그인이 필요하다.
     * 이미 인증된 계정이 쓰는 이메일(내 것 포함)이면 같은 결과(202)에 메일만 보내지 않는다. 인증 전까지 기존 이메일 유지.
     * 사용자별 하루 5통.
     */
    @Transactional
    public void requestEmailChange(UUID userId, String rawEmail, String currentPassword) {
        String email = normalizeEmail(rawEmail);
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("사용자", userId));
        if (user.getPasswordHash() != null) {
            if (!passwordMatches(currentPassword, user.getPasswordHash())) {
                throw new BadRequestException(WRONG_CURRENT_PASSWORD_MESSAGE);
            }
        } else {
            requireRecentKakaoLogin(user);
        }
        mailer.requireAvailable();
        if (!allow(recentEmailChanges, userId.toString(), DAY_MS, EMAIL_CHANGES_PER_DAY, 0) || !allowSend(email)) {
            return;
        }
        if (verifiedOwner(email) != null) {
            return;
        }
        String token = issue(Purpose.EMAIL, email, userId, null, null, LocalDateTime.now());
        mailer.sendEmailVerify(email, token);
    }

    /** 회원 탈퇴 직전(같은 트랜잭션): 그 사용자의 메일 링크를 지운다. */
    @EventListener
    public void onUserDeleting(UserDeletingEvent event) {
        verificationRepository.deleteAllByUserId(event.userId());
    }

    // ─── 내부 ───────────────────────────────────────────────────────

    static String normalizeEmail(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new BadRequestException("이메일을 입력해 주세요.");
        }
        String email = raw.trim().toLowerCase(Locale.ROOT);
        if (email.length() > 255 || !EMAIL_PATTERN.matcher(email).matches()) {
            throw new BadRequestException("이메일 형식이 올바르지 않아요.");
        }
        return email;
    }

    /** 8자 이상, UTF-8 72바이트 이하(BCrypt 가 72바이트 뒤를 버리므로). */
    public static void validatePassword(String password) {
        if (password == null || password.isEmpty()) {
            throw new BadRequestException("비밀번호를 입력해 주세요.");
        }
        if (password.isBlank()) {
            throw new BadRequestException("공백만으로는 비밀번호를 만들 수 없어요.");
        }
        if (password.codePointCount(0, password.length()) < 8) {
            throw new BadRequestException("비밀번호는 8자 이상이어야 해요.");
        }
        if (password.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new BadRequestException("비밀번호가 너무 길어요. 영문 기준 72자까지 쓸 수 있어요.");
        }
    }

    private boolean passwordMatches(String raw, String hash) {
        if (raw == null || raw.isEmpty() || raw.getBytes(StandardCharsets.UTF_8).length > 72) {
            return false;
        }
        return passwordEncoder.matches(raw, hash);
    }

    /**
     * 그 이메일이 인증된 계정. DB 콜레이션(utf8mb4_0900_ai_ci)은 악센트를 무시하므로 Java 에서 정확히 한 번 더 거른다(리뷰 9).
     * 미인증 계정은 '사용 중' 으로 치지 않는다(리뷰 3).
     */
    private User verifiedOwner(String email) {
        return verifiedOwner(exactMatches(email));
    }

    private static User verifiedOwner(List<User> exactMatches) {
        return exactMatches.stream().filter(u -> u.getEmailVerifiedAt() != null).findFirst().orElse(null);
    }

    private List<User> exactMatches(String email) {
        return userRepository.findAllByEmailIgnoreCase(email).stream()
                .filter(u -> sameEmail(email, u.getEmail()))
                .toList();
    }

    /** 미인증 이메일을 가진 기존 카카오 계정(마이그레이션 직후 기존 사용자 전원). */
    private static boolean hasUnverifiedKakaoHolder(List<User> exactMatches) {
        return exactMatches.stream().anyMatch(u -> u.getEmailVerifiedAt() == null && u.getKakaoId() != null);
    }

    /**
     * 비밀번호가 없는 계정은 탈취된 액세스 토큰만으로 로그인 방법을 바꾸지 못하게, 방금(10분 안) 카카오로 로그인했어야 한다
     * (review2 2번). last_kakao_login_at 은 sync·link-kakao 가 성공할 때 채운다.
     */
    private static void requireRecentKakaoLogin(User user) {
        LocalDateTime last = user.getLastKakaoLoginAt();
        if (last == null || last.isBefore(LocalDateTime.now().minusMinutes(KAKAO_RELOGIN_MINUTES))) {
            throw new UnauthorizedAccessException(KAKAO_RELOGIN_MESSAGE);
        }
    }

    /** normalized 는 normalizeEmail 을 거친 값. stored 는 예전에 대소문자 그대로 저장됐을 수 있다. */
    static boolean sameEmail(String normalized, String stored) {
        return stored != null && normalized.equals(stored.trim().toLowerCase(Locale.ROOT));
    }

    private EmailVerification usableToken(String token, LocalDateTime now) {
        return usableToken(token, now, true);
    }

    /** 쓸 수 없는 링크는 까닭(이미 씀·비밀번호 여러 번 틀림·만료)을 나눠 알린다. 토큰을 가진 사람만 알 수 있는 정보다. */
    private EmailVerification usableToken(String token, LocalDateTime now, boolean forUpdate) {
        if (token == null || token.isBlank() || token.length() > 100) {
            throw new BadRequestException(INVALID_TOKEN_MESSAGE);
        }
        String hash = TokenHasher.sha256(token.trim());
        EmailVerification v = (forUpdate ? verificationRepository.findByTokenHashForUpdate(hash)
                : verificationRepository.findByTokenHash(hash))
                .orElseThrow(() -> new BadRequestException(INVALID_TOKEN_MESSAGE));
        if (!v.usable(now)) {
            if (v.getUsedAt() != null) {
                throw new BadRequestException(v.getFailedAttempts() >= EmailVerification.MAX_FAILED_ATTEMPTS
                        ? LOCKED_TOKEN_MESSAGE : USED_TOKEN_MESSAGE);
            }
            throw new BadRequestException(EXPIRED_TOKEN_MESSAGE);
        }
        return v;
    }

    /** 32바이트 난수 토큰을 만들고 해시만 저장한다. 원문은 메일에만 담는다. */
    private String issue(Purpose purpose, String email, UUID userId, String passwordHash, String nickname,
                         LocalDateTime now) {
        verificationRepository.deleteExpiredBefore(now.minusDays(1));
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        verificationRepository.save(new EmailVerification(purpose, email, TokenHasher.sha256(token), userId,
                passwordHash, nickname, now, now.plusMinutes(TOKEN_MINUTES)));
        return token;
    }

    /** 같은 이메일로 1분에 1통, 1시간에 5통. 넘으면 false(조용히 보내지 않는다). */
    boolean allowSend(String email) {
        return allow(recentSends, email, HOUR_MS, SENDS_PER_HOUR, MINUTE_MS);
    }

    /** key 마다 window 안에 max 번, 직전과 minGap 이상 떨어져야 true(그리고 기록). */
    private static boolean allow(Map<String, Deque<Long>> log, String key, long window, int max, long minGap) {
        long now = System.currentTimeMillis();
        if (log.size() > RATE_MAP_PURGE_SIZE) {
            log.values().removeIf(q -> {
                synchronized (q) {
                    Long last = q.peekLast();
                    return last == null || now - last >= window;
                }
            });
        }
        Deque<Long> times = log.computeIfAbsent(key, k -> new ArrayDeque<>());
        synchronized (times) {
            while (!times.isEmpty() && now - times.peekFirst() >= window) {
                times.pollFirst();
            }
            if (times.size() >= max || (!times.isEmpty() && now - times.peekLast() < minGap)) {
                return false;
            }
            times.addLast(now);
            return true;
        }
    }
}
