package com.moyeolog.moyelog_BE.service;

import com.moyeolog.moyelog_BE.dto.LinkPreviewResponse;
import com.moyeolog.moyelog_BE.dto.UserMeResponse;
import com.moyeolog.moyelog_BE.dto.UserProfileResponse;
import com.moyeolog.moyelog_BE.dto.UserUpdateRequest;
import com.moyeolog.moyelog_BE.entity.User;
import com.moyeolog.moyelog_BE.enums.FriendStatus;
import com.moyeolog.moyelog_BE.event.UserDeletingEvent;
import com.moyeolog.moyelog_BE.exception.BadRequestException;
import com.moyeolog.moyelog_BE.exception.ResourceNotFoundException;
import com.moyeolog.moyelog_BE.repository.RefreshTokenRepository;
import com.moyeolog.moyelog_BE.repository.UserRepository;
import com.moyeolog.moyelog_BE.validation.CodePointSizeValidator;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

import java.text.BreakIterator;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class UserService {

    public static final int NICKNAME_MIN = 2;
    public static final int NICKNAME_MAX = 20;
    /** DB 컬럼(users.nickname VARCHAR(200)) 보호용 상한. 이모지 조합 20자도 대부분 이 안에 든다. */
    static final int NICKNAME_MAX_CODE_POINTS = 100;
    public static final int BIO_MAX = 200;
    private static final int CUSTOM_ID_MAX = 32;
    private static final String USER_NOT_FOUND = "사용자를 찾을 수 없어요.";

    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final FileService fileService;
    private final ApplicationEventPublisher eventPublisher;

    @PersistenceContext
    private EntityManager entityManager;

    // ─── 내 정보 ────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public User getUser(UUID id) {
        return userRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException(USER_NOT_FOUND));
    }

    @Transactional(readOnly = true)
    public UserMeResponse getMe(UUID id) {
        return UserMeResponse.from(getUser(id));
    }

    /**
     * 보낸 필드만 바꾼다. 같은 사용자의 동시 요청은 행 잠금으로 차례로 처리해,
     * 사진을 동시에 바꿔도 마지막 사진 하나만 남고 나머지 파일은 지워진다.
     */
    @Transactional
    public UserMeResponse updateUser(UUID id, UserUpdateRequest request, MultipartFile image) {
        // 검증을 먼저 끝내서, 잘못된 요청 때문에 파일만 저장되는 일이 없게 한다.
        String nickname = null;
        if (request != null && request.getNickname() != null) {
            nickname = validateNickname(request.getNickname());
        }
        String bio = request != null ? request.getBio() : null;
        if (bio != null && CodePointSizeValidator.length(bio) > BIO_MAX) {
            throw new BadRequestException("자기소개는 200자까지 쓸 수 있어요.");
        }

        User user = userRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new ResourceNotFoundException(USER_NOT_FOUND));

        if (nickname != null) {
            user.setNickname(nickname);
        }
        if (bio != null) {
            user.setBio(bio.isBlank() ? null : bio);
        }
        if (request != null && request.getEmailVisible() != null) {
            user.setEmailVisible(request.getEmailVisible());
        }

        if (image != null && !image.isEmpty()) {
            String previousImage = user.getProfileImage();
            String newImage = FileService.UPLOAD_URL_PREFIX + fileService.storeFile(image);
            deleteFileOnRollback(newImage);
            user.setProfileImage(newImage);
            // 교체된 이전 프로필 이미지는 커밋 후 삭제 (외부 URL 은 무시됨)
            fileService.deleteFileAfterCommit(previousImage);
        }

        return UserMeResponse.from(userRepository.save(user));
    }

    @Transactional
    public UserMeResponse deleteProfileImage(UUID id) {
        User user = userRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new ResourceNotFoundException(USER_NOT_FOUND));
        String previousImage = user.getProfileImage();
        user.setProfileImage(null);
        fileService.deleteFileAfterCommit(previousImage);
        return UserMeResponse.from(user);
    }

    /**
     * 회원 탈퇴. 각 기능이 UserDeletingEvent 리스너로 자기 데이터를 지운 뒤(같은 트랜잭션, 동기)
     * 리프레시 토큰과 사용자 행을 지운다. 어느 하나라도 실패하면 전부 되돌린다.
     */
    @Transactional
    public void deleteAccount(UUID id) {
        User user = userRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new ResourceNotFoundException(USER_NOT_FOUND));
        String profileImage = user.getProfileImage();

        eventPublisher.publishEvent(new UserDeletingEvent(id));

        refreshTokenRepository.deleteAllByUserId(id);
        userRepository.delete(user);
        userRepository.flush();
        fileService.deleteFileAfterCommit(profileImage);
        log.info("User deleted: {}", id);
    }

    // ─── 다른 사용자 프로필 ─────────────────────────────────────────

    @Transactional(readOnly = true)
    public UserProfileResponse getProfile(UUID viewerId, String targetId) {
        UUID id;
        try {
            id = UUID.fromString(targetId);
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new ResourceNotFoundException(USER_NOT_FOUND);
        }
        User target = userRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException(USER_NOT_FOUND));
        return toProfile(viewerId, target);
    }

    /** 친구 초대 링크용. 대소문자를 무시하되, 정확히 같은 값이 있으면 그것을 고른다. */
    @Transactional(readOnly = true)
    public UserProfileResponse getProfileByCustomId(UUID viewerId, String customId) {
        return toProfile(viewerId, findByCustomIdOrThrow(customId));
    }

    /** 친구 초대 링크를 공유했을 때 미리보기에 쓸 정보. 로그인 없이 보이므로 닉네임만 준다. */
    @Transactional(readOnly = true)
    public LinkPreviewResponse linkPreviewByCustomId(String customId) {
        return new LinkPreviewResponse(findByCustomIdOrThrow(customId).getNickname(), null, null, null);
    }

    private User findByCustomIdOrThrow(String customId) {
        String code = customId == null ? "" : customId.trim();
        if (code.isEmpty() || code.length() > CUSTOM_ID_MAX) {
            throw new ResourceNotFoundException(USER_NOT_FOUND);
        }
        List<User> matches = userRepository.findAllByCustomIdIgnoreCase(code);
        return matches.stream()
                .filter(u -> code.equals(u.getCustomId()))
                .findFirst()
                .or(() -> matches.stream().findFirst())
                .orElseThrow(() -> new ResourceNotFoundException(USER_NOT_FOUND));
    }

    private UserProfileResponse toProfile(UUID viewerId, User target) {
        return UserProfileResponse.builder()
                .id(target.getId())
                .customId(target.getCustomId())
                .nickname(target.getNickname())
                .profileImage(target.getProfileImage())
                .bio(target.getBio())
                .friendStatus(friendStatus(viewerId, target.getId()))
                .mutualGroups(mutualGroups(viewerId, target.getId()))
                .build();
    }

    private UserProfileResponse.FriendStatus friendStatus(UUID viewerId, UUID targetId) {
        if (viewerId.equals(targetId)) {
            return UserProfileResponse.FriendStatus.SELF;
        }
        List<Object[]> rows = entityManager.createQuery(
                        "SELECT f.status, f.requester.id FROM Friend f "
                                + "WHERE (f.requester.id = :a AND f.receiver.id = :b) "
                                + "OR (f.requester.id = :b AND f.receiver.id = :a)", Object[].class)
                .setParameter("a", viewerId)
                .setParameter("b", targetId)
                .getResultList();
        if (rows.stream().anyMatch(r -> r[0] == FriendStatus.ACCEPTED)) {
            return UserProfileResponse.FriendStatus.FRIEND;
        }
        if (rows.stream().anyMatch(r -> r[0] == FriendStatus.PENDING && viewerId.equals(r[1]))) {
            return UserProfileResponse.FriendStatus.PENDING_SENT;
        }
        if (rows.stream().anyMatch(r -> r[0] == FriendStatus.PENDING)) {
            return UserProfileResponse.FriendStatus.PENDING_RECEIVED;
        }
        return UserProfileResponse.FriendStatus.NONE;
    }

    private List<UserProfileResponse.MutualGroup> mutualGroups(UUID viewerId, UUID targetId) {
        return entityManager.createQuery(
                        "SELECT DISTINCT g.id, g.name FROM GroupMember m1 JOIN m1.group g "
                                + "WHERE m1.user.id = :a AND EXISTS ("
                                + "SELECT 1 FROM GroupMember m2 WHERE m2.group = g AND m2.user.id = :b)", Object[].class)
                .setParameter("a", viewerId)
                .setParameter("b", targetId)
                .getResultList().stream()
                .map(r -> new UserProfileResponse.MutualGroup((UUID) r[0], (String) r[1]))
                .sorted(Comparator.comparing(UserProfileResponse.MutualGroup::name,
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
    }

    // ─── 닉네임 규칙 ────────────────────────────────────────────────

    /**
     * 사용자가 입력한 닉네임을 검사하고, 앞뒤 공백을 지운 값을 돌려준다.
     * 길이는 사용자가 보는 글자(grapheme) 기준 2~20자. 제어·방향·보이지 않는 공백 문자가 하나라도 있으면 400.
     * ZWJ·ZWNJ·이체 선택자·태그 문자(이모지 조합용)는 허용하지만, 보이는 글자 없이 이것들만 있으면 400.
     */
    public static String validateNickname(String raw) {
        String nickname = raw == null ? "" : raw.strip();
        if (nickname.isEmpty()) {
            throw new BadRequestException("닉네임을 입력해 주세요.");
        }
        if (nickname.codePoints().anyMatch(UserService::isInvisibleOrControl) || !hasVisibleCharacter(nickname)) {
            throw new BadRequestException("닉네임에 쓸 수 없는 문자가 들어 있어요.");
        }
        int length = graphemeLength(nickname);
        if (length < NICKNAME_MIN || length > NICKNAME_MAX
                || CodePointSizeValidator.length(nickname) > NICKNAME_MAX_CODE_POINTS) {
            throw new BadRequestException("닉네임은 2자 이상 20자 이하로 입력해 주세요.");
        }
        return nickname;
    }

    /**
     * 카카오에서 받은 닉네임 정리용(오류 대신 고쳐서 쓴다): 쓸 수 없는 문자를 빼고(ZWJ·이체 선택자는 남김) 앞뒤 공백을 지운 뒤
     * grapheme 경계에서 20자로 자른다. 보이는 글자가 없으면 빈 문자열.
     */
    public static String sanitizeNickname(String raw) {
        if (raw == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(raw.length());
        raw.codePoints()
                .filter(cp -> !isInvisibleOrControl(cp))
                .forEach(sb::appendCodePoint);
        String result = truncateGraphemes(sb.toString().strip(), NICKNAME_MAX, NICKNAME_MAX_CODE_POINTS).strip();
        return hasVisibleCharacter(result) ? result : "";
    }

    /** 사용자가 보는 글자 수 (확장 grapheme cluster: 👨‍👩‍👧, ❤️, 🏳️‍🌈 는 각각 1자) */
    public static int graphemeLength(String text) {
        if (text == null || text.isEmpty()) return 0;
        BreakIterator it = BreakIterator.getCharacterInstance();
        it.setText(text);
        int count = 0;
        while (it.next() != BreakIterator.DONE) count++;
        return count;
    }

    /** grapheme 경계에서 최대 maxGraphemes 글자, maxCodePoints 코드 포인트 이하로 자른다. */
    static String truncateGraphemes(String text, int maxGraphemes, int maxCodePoints) {
        if (text == null || text.isEmpty()) return "";
        BreakIterator it = BreakIterator.getCharacterInstance();
        it.setText(text);
        int end = 0;
        int count = 0;
        for (int next = it.next(); next != BreakIterator.DONE && count < maxGraphemes; next = it.next()) {
            if (text.codePointCount(0, next) > maxCodePoints) break;
            end = next;
            count++;
        }
        return text.substring(0, end);
    }

    /** 이모지 조합용으로 허용하지만 그 자체로는 보이지 않는 문자: ZWNJ, ZWJ, 이체 선택자, 태그 문자 */
    static boolean isJoinerOrSelector(int cp) {
        return cp == 0x200C || cp == 0x200D
                || (cp >= 0xFE00 && cp <= 0xFE0F)
                || (cp >= 0xE0100 && cp <= 0xE01EF)
                || (cp >= 0xE0020 && cp <= 0xE007F);
    }

    /** 공백도 아니고 ZWJ·이체 선택자 같은 조합용 문자도 아닌 글자가 하나라도 있는지 */
    static boolean hasVisibleCharacter(String text) {
        return text != null && text.codePoints().anyMatch(cp ->
                !isJoinerOrSelector(cp) && !Character.isWhitespace(cp) && !Character.isSpaceChar(cp));
    }

    /**
     * 닉네임에 쓸 수 없는 문자: 제어문자(Cc), 줄·문단 구분자, 방향 제어·보이지 않는 공백
     * (U+200B, U+200E, U+200F, U+2028~U+202E, U+2060~U+2064, U+2066~U+2069, U+FEFF 와 그 밖의 서식 문자 Cf),
     * 짝 없는 서로게이트, 미할당·사용자 정의 영역, 비어 보이는 한글 채움 문자·점자 빈칸.
     * 단 이모지 조합에 쓰는 ZWJ·ZWNJ·이체 선택자·태그 문자는 허용한다(isJoinerOrSelector).
     */
    static boolean isInvisibleOrControl(int cp) {
        if (isJoinerOrSelector(cp)) {
            return false;
        }
        int type = Character.getType(cp);
        if (type == Character.CONTROL || type == Character.FORMAT || type == Character.UNASSIGNED
                || type == Character.PRIVATE_USE || type == Character.SURROGATE
                || type == Character.LINE_SEPARATOR || type == Character.PARAGRAPH_SEPARATOR) {
            return true;
        }
        return cp == 0x200B || cp == 0x200E || cp == 0x200F
                || (cp >= 0x2028 && cp <= 0x202E)
                || (cp >= 0x2060 && cp <= 0x2064)
                || (cp >= 0x2066 && cp <= 0x2069)
                || cp == 0xFEFF
                || cp == 0x115F || cp == 0x1160 || cp == 0x3164 || cp == 0xFFA0 || cp == 0x2800 || cp == 0x180E;
    }

    // ─── helpers ────────────────────────────────────────────────────

    /** 트랜잭션이 롤백되면 방금 저장한 파일을 지운다 (고아 파일 방지). */
    private void deleteFileOnRollback(String imageUrl) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status == STATUS_ROLLED_BACK) {
                    fileService.deleteFile(imageUrl);
                }
            }
        });
    }
}
