package com.moyeolog.moyelog_BE.service;

import com.moyeolog.moyelog_BE.dto.MemoInsightResponse;
import com.moyeolog.moyelog_BE.dto.MemoRequest;
import com.moyeolog.moyelog_BE.dto.MemoResponse;
import com.moyeolog.moyelog_BE.dto.MemoShareRequest;
import com.moyeolog.moyelog_BE.entity.*;
import com.moyeolog.moyelog_BE.event.ChangeEvent;
import com.moyeolog.moyelog_BE.event.ChangeEvents;
import com.moyeolog.moyelog_BE.event.LiveTopics;
import com.moyeolog.moyelog_BE.live.LiveAudience;
import com.moyeolog.moyelog_BE.event.NotificationEvent;
import com.moyeolog.moyelog_BE.event.NotificationType;
import com.moyeolog.moyelog_BE.event.UserDeletingEvent;
import com.moyeolog.moyelog_BE.exception.BadRequestException;
import com.moyeolog.moyelog_BE.exception.ConflictException;
import com.moyeolog.moyelog_BE.exception.UnauthorizedAccessException;
import com.moyeolog.moyelog_BE.repository.*;
import com.moyeolog.moyelog_BE.validation.CodePointSizeValidator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 메모 비즈니스 로직을 처리하는 서비스 클래스입니다.
 *
 * 권한 규칙
 * - 읽기: 작성자, 공유받은 사람, (모임 메모) 현재 모임 멤버, (모임에 공유한 개인 메모) 그 모임의 현재 멤버
 * - 수정·태그: 개인 메모는 작성자, 모임 메모는 현재 모임 멤버 (나간·강퇴된 작성자는 수정 불가),
 *   그리고 '수정 가능'으로 공유받은 친구. 친구 공유는 기본 읽기 전용, 모임에 공유한 개인 메모는 늘 읽기 전용.
 * - 삭제: 작성자, 그리고 모임 메모는 그 모임의 방장 (모임에 공유된 개인 메모는 작성자만)
 * - 공유·공유 해제·분석: 작성자
 * - 내 고정: 모든 메모, 읽을 수 있는 사람 누구나(memo_pins, 사용자별. 고정한 본인에게만 보인다)
 * - 모임 고정: 모임 메모만, 그 모임의 현재 멤버 중 작성자 또는 방장(memos.pinned_at, 멤버 모두에게 보인다).
 *   두 고정은 서로 영향이 없다.
 * - 즐겨찾기: 읽을 수 있는 사람 누구나, 사용자별 (모임 메모 포함. 모임 화면에서는 별을 숨기고 고정을 쓴다)
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MemoService {

    private final MemoRepository memoRepository;
    private final UserRepository userRepository;
    private final FileService fileService;
    private final MemoTagRepository memoTagRepository;
    private final MemoShareRepository memoShareRepository;
    private final MemoGroupShareRepository memoGroupShareRepository;
    private final MemoAiInsightRepository memoAiInsightRepository;
    private final AiAnalysisSupport aiAnalysisSupport;
    private final GroupRepository groupRepository;
    private final GroupMemberRepository groupMemberRepository;
    private final ScheduleRepository scheduleRepository;
    private final FriendRepository friendRepository;
    private final MemoFavoriteRepository memoFavoriteRepository;
    private final MemoPinRepository memoPinRepository;
    private final ApplicationEventPublisher eventPublisher;

    private static final int MAX_TAG_LENGTH = 50;
    private static final int MAX_TAG_COUNT = 50;
    private static final int NOTIFICATION_TITLE_LENGTH = 30;
    private static final String MEMO_NOT_FOUND = "메모를 찾을 수 없어요. 삭제됐을 수 있어요.";
    private static final String NO_READ_PERMISSION = "이 메모를 볼 수 있는 권한이 없어요.";
    private static final String NO_EDIT_PERMISSION = "이 메모를 고칠 수 있는 권한이 없어요.";

    // ─── 공개 메서드 (API) ──────────────────────────────────────────

    /**
     * 메모 공유: 작성자만, 그리고 작성자의 (수락된) 친구에게만 공유할 수 있습니다.
     * canEdit 이 true 면 '수정 가능'으로 공유한다. 이미 공유한 친구는 권한만 이 값으로 바꾼다.
     */
    @Transactional
    public void shareMemo(UUID authorId, UUID memoId, MemoShareRequest request) {
        // 같은 메모에 대한 동시 공유를 줄 세운다 (중복 행 방지).
        Memo memo = findMemoForUpdate(memoId);
        validateAuthor(memo, authorId);

        Set<UUID> friendIds = new LinkedHashSet<>();
        if (request != null && request.getFriendIds() != null) {
            friendIds.addAll(request.getFriendIds());
        }
        friendIds.remove(null);
        friendIds.remove(authorId);
        boolean canEdit = request != null && Boolean.TRUE.equals(request.getCanEdit());
        if (friendIds.isEmpty()) {
            throw new BadRequestException("공유할 친구를 한 명 이상 선택해 주세요.");
        }

        // 먼저 전부 검증한 뒤 저장한다 (일부만 공유되는 일이 없도록).
        // 친구 관계 행을 잠가서, 확인과 저장 사이에 친구 삭제가 끼어들지 못하게 한다.
        List<User> friends = new ArrayList<>();
        for (UUID friendId : friendIds) {
            if (friendRepository.findAcceptedBetweenForUpdate(authorId, friendId).isEmpty()) {
                throw new UnauthorizedAccessException("친구에게만 메모를 공유할 수 있어요.");
            }
            friends.add(findUserById(friendId));
        }

        List<User> newlyShared = new ArrayList<>();
        for (User friend : friends) {
            List<MemoShare> existing = memoShareRepository.findAllByMemo_IdAndSharedTo_Id(memo.getId(), friend.getId());
            if (existing.isEmpty()) {
                memoShareRepository.save(MemoShare.builder().memo(memo).sharedTo(friend).canEdit(canEdit).build());
                newlyShared.add(friend);
            } else {
                existing.forEach(share -> share.changeCanEdit(canEdit));
            }
        }
        ConcurrentUpdates.flush(memoShareRepository::flush);
        audience(memo).publish(eventPublisher);

        String message = memo.getAuthor().getNickname() + "님이 '" + shortTitle(memo.getTitle()) + "' 메모를 공유했어요.";
        for (User friend : newlyShared) {
            eventPublisher.publishEvent(new NotificationEvent(friend.getId(), authorId,
                    NotificationType.MEMO_SHARED, message, "/memo/" + memo.getId()));
        }
    }

    /** 메모 공유 해제: 작성자가 공유 대상을 제거하거나, 공유받은 사용자가 스스로 공유를 해제합니다. */
    @Transactional
    public void unshareMemo(UUID requesterId, UUID memoId, UUID targetUserId) {
        Memo memo = findMemoById(memoId);
        boolean isAuthor = memo.getAuthor().getId().equals(requesterId);
        if (!isAuthor && !requesterId.equals(targetUserId)) {
            throw new UnauthorizedAccessException("메모 작성자만 공유를 해제할 수 있어요.");
        }

        // 공유가 풀린 사람의 화면에서도 사라져야 하므로 지우기 전에 받는 사람을 정한다.
        ChangeEvents audience = audience(memo);
        if (memoShareRepository.deleteByMemoIdAndUserId(memoId, targetUserId) == 0) {
            throw new ItemNotFoundException("이 메모를 공유받은 사람이 아니에요.");
        }
        // 더 이상 읽을 수 없게 된 사람의 즐겨찾기·고정도 지운다 (다시 공유해도 되살아나지 않도록).
        if (!isCurrentGroupMember(memo, targetUserId)
                && !memoGroupShareRepository.existsSharedToMember(memoId, targetUserId)) {
            memoFavoriteRepository.deleteByUserIdAndMemoId(targetUserId, memoId);
            memoPinRepository.deleteByUserIdAndMemoId(targetUserId, memoId);
        }
        audience.publish(eventPublisher);
    }

    /** 개인 메모를 작성자가 속한 모임에 공유한다(그 모임 멤버는 읽기만). 이미 공유했으면 그대로 둔다. */
    @Transactional
    public void shareMemoToGroup(UUID authorId, UUID memoId, UUID groupId) {
        Memo memo = findMemoForUpdate(memoId);
        validateAuthor(memo, authorId);
        if (memo.getGroupId() != null) {
            throw new BadRequestException("모임에서 쓴 메모는 다른 모임에 공유할 수 없어요.");
        }
        requireGroupMember(groupId, authorId);
        if (!memoGroupShareRepository.existsByMemo_IdAndGroupId(memoId, groupId)) {
            memoGroupShareRepository.save(MemoGroupShare.builder().memo(memo).groupId(groupId).build());
            // 동시에 같은 모임에 공유하면 유니크 제약에 걸린다 → 409.
            ConcurrentUpdates.flush(memoGroupShareRepository::flush);
        }
        audience(memo).publish(eventPublisher);
    }

    /** 모임 공유 해제: 작성자만 */
    @Transactional
    public void unshareMemoFromGroup(UUID authorId, UUID memoId, UUID groupId) {
        Memo memo = findMemoById(memoId);
        validateAuthor(memo, authorId);
        // 공유가 풀린 모임 멤버의 화면에서도 사라져야 하므로 지우기 전에 받는 사람을 정한다.
        ChangeEvents audience = audience(memo);
        if (memoGroupShareRepository.deleteByMemoIdAndGroupId(memoId, groupId) == 0) {
            throw new ItemNotFoundException("이 모임에 공유한 메모가 아니에요.");
        }
        // 이 공유로만 읽던 모임 멤버의 고정을 지운다 (모임에 공유되는 것은 개인 메모뿐이다).
        for (GroupMember member : groupMemberRepository.findAllByGroup_IdOrderByJoinedAtAsc(groupId)) {
            UUID memberId = member.getUser().getId();
            if (!memberId.equals(authorId)
                    && !memoShareRepository.existsByMemo_IdAndSharedTo_Id(memoId, memberId)
                    && !memoGroupShareRepository.existsSharedToMember(memoId, memberId)) {
                memoPinRepository.deleteByUserIdAndMemoId(memberId, memoId);
            }
        }
        audience.publish(eventPublisher);
    }

    @Transactional(readOnly = true)
    public List<MemoResponse> getSharedMemos(UUID userId) {
        User user = findUserById(userId);
        Viewer viewer = viewer(user);
        Set<UUID> favoriteIds = favoriteMemoIds(userId);
        Map<UUID, LocalDateTime> pins = pinnedAts(userId);
        return memoShareRepository.findBySharedToOrderBySharedAtDesc(user).stream()
                .map(MemoShare::getMemo)
                .distinct()
                .sorted(pinnedFirst(pins))
                .map(memo -> convertToResponse(memo, viewer, favoriteIds, pins))
                .collect(Collectors.toList());
    }

    @Transactional
    public MemoResponse createMemo(UUID userId, MemoRequest request, MultipartFile image) {
        User author = findUserById(userId);

        // 그룹 메모는 해당 그룹의 멤버만 작성할 수 있다 (없는 그룹 404, 비멤버 403).
        if (request.getGroupId() != null) {
            requireGroupMember(request.getGroupId(), userId);
        }
        Viewer viewer = viewer(author);
        List<Memo> taggedMemos = resolveTaggedMemos(request.getTaggedMemoIds(), viewer, null);
        List<String> tags = normalizeTags(request.getTags());
        List<Schedule> schedules = resolveSchedulesToLink(request.getTaggedScheduleIds(), viewer, request.getGroupId(), null);

        // 검증이 모두 끝난 뒤에 파일을 저장한다.
        String imageUrl = handleImageUpload(image, null);

        Memo memo = Memo.builder()
                .author(author)
                .groupId(request.getGroupId())
                .title(request.getTitle())
                .content(request.getContent())
                .imageUrl(imageUrl)
                .taggedMemos(taggedMemos)
                .build();

        Memo savedMemo = memoRepository.save(memo);
        saveTags(savedMemo, tags);
        Set<UUID> mentioned = new HashSet<>();
        for (Schedule schedule : schedules) {
            linkSchedule(schedule, savedMemo);
            mentioned.add(schedule.getId());
        }
        savedMemo.replaceMentionedSchedules(mentioned);
        audience(savedMemo).publish(eventPublisher);

        return convertToResponse(savedMemo, viewer, null);
    }

    @Transactional(readOnly = true)
    public List<MemoResponse> getMyMemos(UUID userId) {
        return getMyMemos(userId, null);
    }

    /** 내가 쓴 메모. q 가 있으면 제목·본문에 q 가 들어 있는 것만 (대소문자 무시). */
    @Transactional(readOnly = true)
    public List<MemoResponse> getMyMemos(UUID userId, String q) {
        User author = findUserById(userId);
        Viewer viewer = viewer(author);
        Set<UUID> favoriteIds = favoriteMemoIds(userId);
        Map<UUID, LocalDateTime> pins = pinnedAts(userId);
        String keyword = q == null ? "" : q.trim().toLowerCase(Locale.ROOT);
        return memoRepository.findAllByAuthorOrderByCreatedAtDesc(author).stream()
                .filter(memo -> keyword.isEmpty() || contains(memo.getTitle(), keyword) || contains(memo.getContent(), keyword))
                .sorted(pinnedFirst(pins))
                .map(memo -> convertToResponse(memo, viewer, favoriteIds, pins))
                .collect(Collectors.toList());
    }

    /** 내가 즐겨찾기했고 지금 볼 수 있는 모든 메모 (내 메모·공유받은 메모·모임 메모), 최신순. */
    @Transactional(readOnly = true)
    public List<MemoResponse> getFavoriteMemos(UUID userId) {
        User user = findUserById(userId);
        Viewer viewer = viewer(user);
        Set<UUID> favoriteIds = favoriteMemoIds(userId);
        if (favoriteIds.isEmpty()) {
            return new ArrayList<>();
        }
        Map<UUID, LocalDateTime> pins = pinnedAts(userId);
        return memoRepository.findAllById(favoriteIds).stream()
                .filter(memo -> canRead(memo, viewer))
                .sorted(Comparator.comparing(Memo::getCreatedAt, Comparator.nullsLast(Comparator.reverseOrder())))
                .map(memo -> convertToResponse(memo, viewer, favoriteIds, pins))
                .collect(Collectors.toList());
    }

    @Transactional
    public void deleteMemo(UUID userId, UUID memoId) {
        // 동시 삭제는 행 잠금으로 줄 세운다: 먼저 지운 쪽만 성공하고 나머지는 404.
        Memo memo = findMemoForUpdate(memoId);
        if (!canDelete(memo, userId)) {
            throw new UnauthorizedAccessException(memo.getGroupId() != null
                    ? "모임 메모는 작성자나 방장만 삭제할 수 있어요."
                    : "메모 작성자만 할 수 있어요.");
        }
        ChangeEvents audience = audience(memo);
        deleteMemoInternal(memo);
        ConcurrentUpdates.flush(memoRepository::flush);
        audience.publish(eventPublisher);
    }

    @Transactional
    public MemoResponse updateTags(UUID userId, UUID memoId, List<String> newTags) {
        Memo memo = findMemoForUpdate(memoId);
        // 태그는 메모 수정과 같은 권한 (개인 메모는 작성자, 모임 메모는 현재 멤버).
        validateEditAccess(memo, userId);

        User modifier = findUserById(userId);
        List<String> tags = normalizeTags(newTags);
        memoTagRepository.deleteAllByMemo(memo);
        saveTags(memo, tags);
        memo.markModified(modifier);
        ConcurrentUpdates.flush(memoRepository::flush);
        audience(memo).publish(eventPublisher);

        return convertToResponse(memo, viewer(modifier), null);
    }

    @Transactional
    public MemoResponse updateMemo(UUID userId, UUID memoId, MemoRequest request, MultipartFile image) {
        // 같은 메모의 동시 수정은 행 잠금으로 줄 세운다 (태그 삭제·삽입과 메모 UPDATE 사이 데드락 방지).
        Memo memo = findMemoForUpdate(memoId);
        // 수정은 개인 메모는 작성자, 모임 메모는 현재 모임 멤버만 가능. 공유받은 사용자는 읽기 전용.
        validateEditAccess(memo, userId);

        // 오래된 화면에서 저장하면 다른 사람의 수정을 덮어쓰지 않도록 409.
        if (request.getVersion() != null && !Objects.equals(request.getVersion(), memo.getVersion())) {
            throw new ConflictException(ConcurrentUpdates.CONFLICT_MESSAGE);
        }

        // 요청에 groupId 가 있으면 그 그룹의 멤버여야 한다 (없는 그룹 404, 비멤버 403).
        // 메모의 소속 그룹 자체는 수정으로 바뀌지 않는다.
        if (request.getGroupId() != null) {
            requireGroupMember(request.getGroupId(), userId);
        }

        User modifier = findUserById(userId);
        Viewer viewer = viewer(modifier);
        List<Memo> taggedMemos = request.getTaggedMemoIds() == null
                ? new ArrayList<>(memo.getTaggedMemos() != null ? memo.getTaggedMemos() : List.of())
                : resolveTaggedMemos(request.getTaggedMemoIds(), viewer, memo);
        List<String> tags = request.getTags() == null ? null : normalizeTags(request.getTags());
        // taggedScheduleIds 가 null/누락이면 기존 일정 연결을 그대로 둔다.
        List<Schedule> schedulesToLink = request.getTaggedScheduleIds() == null
                ? null
                : resolveSchedulesToLink(request.getTaggedScheduleIds(), viewer, memo.getGroupId(), memo);

        String previousImageUrl = memo.getImageUrl();
        String imageUrl;
        if (image != null && !image.isEmpty()) {
            imageUrl = handleImageUpload(image, previousImageUrl);
        } else if (Boolean.TRUE.equals(request.getRemoveImage())) {
            imageUrl = null;
        } else {
            imageUrl = previousImageUrl;
        }

        memo.update(request.getTitle(), request.getContent(), imageUrl, modifier, taggedMemos);

        if (tags != null) {
            memoTagRepository.deleteAllByMemo(memo);
            saveTags(memo, tags);
        }
        if (schedulesToLink != null) {
            syncMentionedSchedules(memo, request.getTaggedScheduleIds(), schedulesToLink);
        }
        ConcurrentUpdates.flush(memoRepository::flush);

        // 이미지가 교체·삭제되었으면 이전 파일을 지운다.
        if (previousImageUrl != null && !previousImageUrl.equals(imageUrl)) {
            fileService.deleteFileAfterCommit(previousImageUrl);
        }
        audience(memo).publish(eventPublisher);

        return convertToResponse(memo, viewer, null);
    }

    @Transactional(readOnly = true)
    public MemoResponse getMemo(UUID userId, UUID memoId) {
        Memo memo = findMemoById(memoId);
        Viewer viewer = viewer(findUserById(userId));
        if (!canRead(memo, viewer)) {
            throw new UnauthorizedAccessException(NO_READ_PERMISSION);
        }
        return convertToResponse(memo, viewer, null);
    }

    /**
     * 그룹 메모 목록: 그룹 멤버 전용 (없는 그룹 404, 비멤버 403).
     * 모임에서 쓴 메모 다음에 이 모임에 공유된 개인 메모(groupId 가 없다, 읽기 전용)가 온다.
     */
    @Transactional(readOnly = true)
    public List<MemoResponse> getGroupMemos(UUID userId, UUID groupId) {
        requireGroupMember(groupId, userId);
        Viewer viewer = viewer(findUserById(userId));
        Set<UUID> favoriteIds = favoriteMemoIds(userId);
        Map<UUID, LocalDateTime> pins = pinnedAts(userId);
        List<Memo> memos = new ArrayList<>(memoRepository.findByGroupIdOrderByCreatedAtDesc(groupId));
        // 모임 고정(공지)을 맨 위로. 내 고정은 여기 정렬에 쓰지 않는다. 공유된 개인 메모는 모임 고정 대상이 아니라 그 뒤에 그대로 둔다.
        memos.sort(Comparator.comparing(Memo::getPinnedAt, Comparator.nullsLast(Comparator.reverseOrder())));
        memos.addAll(memoGroupShareRepository.findMemosByGroupId(groupId));
        return memos.stream()
                .map(memo -> convertToResponse(memo, viewer, favoriteIds, pins))
                .collect(Collectors.toList());
    }

    /**
     * 메모 AI 분석 (작성자 전용). LLM 을 기다리는 동안 트랜잭션을 잡지 않는다:
     * 짧은 읽기 트랜잭션 → 트랜잭션 밖 LLM 호출 → 짧은 쓰기 트랜잭션. 같은 메모를 분석 중이면 409.
     */
    public MemoInsightResponse analyzeMemo(UUID userId, UUID memoId) {
        return aiAnalysisSupport.run("memo:" + memoId,
                () -> {
                    Memo memo = findMemoById(memoId);
                    validateAuthor(memo, userId);
                    log.info("[AI Analyze] Request for Memo: {}", memoId);
                    return new AiAnalysisSupport.Source(memoId.toString(), memo.getContent(), memo.getImageUrl());
                },
                analyzed -> {
                    // 분석하는 동안 메모가 지워졌으면 404
                    Memo memo = findMemoById(memoId);
                    MemoAiInsight insight = updateOrCreateInfo(memo, analyzed);
                    return convertToInsightResponse(memoAiInsightRepository.save(insight));
                });
    }

    /** 분석 결과가 없으면 null 을 반환합니다 (컨트롤러에서 204 No Content). */
    @Transactional(readOnly = true)
    public MemoInsightResponse getMemoInsight(UUID userId, UUID memoId) {
        Memo memo = findMemoById(memoId);
        validateAccess(memo, userId);
        return memoAiInsightRepository.findById(memoId)
                .map(this::convertToInsightResponse)
                .orElse(null);
    }

    /** 즐겨찾기는 사용자별로 저장됩니다. 메모를 읽을 수 있는 사용자라면 누구나 자신의 즐겨찾기를 토글할 수 있습니다. */
    @Transactional
    public MemoResponse toggleFavorite(UUID userId, UUID memoId) {
        Memo memo = findMemoById(memoId);
        User user = findUserById(userId);
        Viewer viewer = viewer(user);
        if (!canRead(memo, viewer)) {
            throw new UnauthorizedAccessException(NO_READ_PERMISSION);
        }

        boolean favorite;
        if (memoFavoriteRepository.existsByUser_IdAndMemo_Id(userId, memoId)) {
            // 동시에 해제돼 행이 이미 없어도 오류 없이 넘어간다.
            memoFavoriteRepository.deleteByUserIdAndMemoId(userId, memoId);
            favorite = false;
        } else {
            memoFavoriteRepository.save(MemoFavorite.builder().user(user).memo(memo).build());
            // 동시 추가는 유니크 제약에 걸린다 → 409.
            ConcurrentUpdates.flush(memoFavoriteRepository::flush);
            favorite = true;
        }
        // 즐겨찾기는 나에게만 보인다(다른 탭·기기 반영)
        eventPublisher.publishEvent(ChangeEvent.of(Set.of(userId), LiveTopics.MEMOS));

        MemoResponse response = convertToResponse(memo, viewer, Set.of());
        response.setIsFavorite(favorite);
        return response;
    }

    /**
     * 내 고정·해제. 모든 메모(모임 메모 포함), 읽을 수 있는 사람 누구나. 고정한 본인에게만 보인다(memo_pins).
     * 이미 고정된 메모를 다시 고정하면 처음 고정한 시각을 둔다. 수정 시각·version·마지막 수정자는 바꾸지 않는다.
     */
    @Transactional
    public MemoResponse pinMemo(UUID userId, UUID memoId, boolean pin) {
        Memo memo = findMemoForUpdate(memoId);
        User user = findUserById(userId);
        Viewer viewer = viewer(user);
        if (!canRead(memo, viewer)) {
            throw new UnauthorizedAccessException(NO_READ_PERMISSION);
        }
        // 메모 행을 잠갔으므로 확인과 저장 사이에 같은 고정이 끼어들지 않는다.
        boolean pinned = memoPinRepository.existsByUser_IdAndMemo_Id(userId, memoId);
        if (pin && !pinned) {
            memoPinRepository.save(MemoPin.builder().user(user).memo(memo).build());
            ConcurrentUpdates.flush(memoPinRepository::flush);
        } else if (!pin && pinned) {
            memoPinRepository.deleteByUserIdAndMemoId(userId, memoId);
        }
        // 내 고정은 나에게만 보인다(다른 탭·기기 반영)
        eventPublisher.publishEvent(ChangeEvent.of(Set.of(userId), LiveTopics.MEMOS));
        return convertToResponse(memo, viewer, null);
    }

    /**
     * 모임 고정·해제. 모임 메모만(개인 메모면 400), 그 모임의 현재 멤버 중 작성자 또는 방장. 멤버 모두에게 보인다(memos.pinned_at).
     * 이미 고정된 메모를 다시 고정하면 처음 고정한 시각을 둔다. 수정 시각·version·마지막 수정자는 바꾸지 않는다.
     */
    @Transactional
    public MemoResponse groupPinMemo(UUID userId, UUID memoId, boolean pin) {
        Memo memo = findMemoForUpdate(memoId);
        if (memo.getGroupId() == null) {
            throw new BadRequestException("모임에서 쓴 메모만 모임에 고정할 수 있어요.");
        }
        Viewer viewer = viewer(findUserById(userId));
        if (!canGroupPin(memo, viewer)) {
            throw new UnauthorizedAccessException("모임 메모는 방장이나 작성자만 고정할 수 있어요.");
        }
        if (pin != (memo.getPinnedAt() != null)) {
            memoRepository.updatePinnedAt(memoId, pin ? LocalDateTime.now() : null);
            // updatePinnedAt 이 영속성 컨텍스트를 비웠으므로 다시 읽는다.
            memo = findMemoById(memoId);
        }
        audience(memo).publish(eventPublisher);
        return convertToResponse(memo, viewer, null);
    }

    /** 회원 탈퇴: 내 메모(와 그 공유·즐겨찾기·고정·태그·연결), 나에게 온 공유, 내 즐겨찾기·고정을 지운다. */
    @EventListener
    @Transactional
    public void onUserDeleting(UserDeletingEvent event) {
        UUID userId = event.userId();
        memoShareRepository.deleteAllSharedTo(userId);
        memoFavoriteRepository.deleteAllByUserId(userId);
        memoPinRepository.deleteAllByUserId(userId);
        for (Memo memo : memoRepository.findAllByAuthorId(userId)) {
            deleteMemoInternal(memo);
        }
        memoRepository.flush();
        memoRepository.clearLastModifier(userId);
    }

    // ─── 내부 헬퍼 메서드 (분리 및 최적화) ──────────────────────────

    /** 지금 이 메모를 볼 수 있는 사람(작성자·공유받은 사람·모임 멤버). group:{gid} 는 모임 멤버에게만 (LiveAudience). 커밋 뒤에 보낸다 */
    private ChangeEvents audience(Memo memo) {
        return LiveAudience.addMemo(new ChangeEvents(), memo, memoShareRepository, memoGroupShareRepository,
                groupMemberRepository);
    }

    private void deleteMemoInternal(Memo memo) {
        // 메모 삭제 시 연결된 일정에서도 해당 메모를 삭제 (동기화)
        for (Schedule schedule : scheduleRepository.findByTaggedMemosContains(memo)) {
            if (schedule.getTaggedMemos() != null) {
                schedule.getTaggedMemos().remove(memo);
            }
        }

        // 다른 메모가 이 메모를 태그하고 있다면 관계 삭제 (자기 참조 동기화)
        for (Memo refMemo : memoRepository.findByTaggedMemosContains(memo)) {
            if (refMemo.getTaggedMemos() != null) {
                refMemo.getTaggedMemos().remove(memo);
            }
        }

        // 연관된 자식 엔티티 삭제
        memoTagRepository.deleteAllByMemo(memo);
        memoShareRepository.deleteAllByMemo(memo);
        memoGroupShareRepository.deleteAllByMemoId(memo.getId());
        memoFavoriteRepository.deleteAllByMemo(memo);
        memoPinRepository.deleteAllByMemoId(memo.getId());
        if (memoAiInsightRepository.existsById(memo.getId())) {
            memoAiInsightRepository.deleteById(memo.getId());
        }

        String imageUrl = memo.getImageUrl();
        memoRepository.delete(memo);

        // 첨부 이미지 파일은 삭제가 커밋된 뒤에 지운다.
        fileService.deleteFileAfterCommit(imageUrl);
    }

    private User findUserById(UUID userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new ItemNotFoundException("사용자를 찾을 수 없어요."));
    }

    private Memo findMemoById(UUID memoId) {
        return memoRepository.findById(memoId)
                .orElseThrow(() -> new ItemNotFoundException(MEMO_NOT_FOUND));
    }

    private Memo findMemoForUpdate(UUID memoId) {
        return memoRepository.findByIdForUpdate(memoId)
                .orElseThrow(() -> new ItemNotFoundException(MEMO_NOT_FOUND));
    }

    private void validateAuthor(Memo memo, UUID userId) {
        if (!memo.getAuthor().getId().equals(userId)) {
            throw new UnauthorizedAccessException("메모 작성자만 할 수 있어요.");
        }
    }

    /** 요청한 사용자와 그 사용자가 지금 속한 모임 (권한 계산용). */
    private record Viewer(UUID id, Set<UUID> groupIds) {
        boolean isMemberOf(UUID groupId) {
            return groupId != null && groupIds.contains(groupId);
        }
    }

    private Viewer viewer(User user) {
        Set<UUID> groupIds = new HashSet<>();
        List<GroupMember> memberships = groupMemberRepository.findByUser(user);
        if (memberships != null) {
            for (GroupMember membership : memberships) {
                groupIds.add(membership.getGroup().getId());
            }
        }
        return new Viewer(user.getId(), groupIds);
    }

    private boolean isCurrentGroupMember(Memo memo, UUID userId) {
        return memo.getGroupId() != null
                && groupMemberRepository.existsByGroup_IdAndUser_Id(memo.getGroupId(), userId);
    }

    /** 읽기 권한: 작성자, (그룹 메모의 경우) 현재 그룹 멤버, 공유받은 사용자, 공유한 모임의 현재 멤버 */
    private boolean canRead(Memo memo, Viewer viewer) {
        if (memo.getAuthor().getId().equals(viewer.id())) return true;
        if (viewer.isMemberOf(memo.getGroupId())) return true;
        if (memoShareRepository.existsByMemo_IdAndSharedTo_Id(memo.getId(), viewer.id())) return true;
        return memoGroupShareRepository.existsSharedToMember(memo.getId(), viewer.id());
    }

    /** 수정 권한: 모임 메모는 현재 모임 멤버, 개인 메모는 작성자, 그리고 '수정 가능'으로 공유받은 사람. */
    private boolean canEdit(Memo memo, Viewer viewer) {
        boolean byRole = memo.getGroupId() != null
                ? viewer.isMemberOf(memo.getGroupId())
                : memo.getAuthor().getId().equals(viewer.id());
        return byRole || memoShareRepository.existsByMemo_IdAndSharedTo_IdAndCanEditTrue(memo.getId(), viewer.id());
    }

    /** 모임 고정 권한: 모임 메모만, 그 모임의 현재 멤버이면서 작성자 또는 방장. */
    private boolean canGroupPin(Memo memo, Viewer viewer) {
        if (!viewer.isMemberOf(memo.getGroupId())) {
            return false;
        }
        return memo.getAuthor().getId().equals(viewer.id()) || isGroupOwner(memo, viewer.id());
    }

    /** 삭제 권한: 작성자, 또는 모임 메모이면 그 모임의 방장. 모임에 공유된 개인 메모는 작성자만. */
    private boolean canDelete(Memo memo, UUID userId) {
        return memo.getAuthor().getId().equals(userId) || isGroupOwner(memo, userId);
    }

    /** 모임 메모이고 userId 가 그 모임의 방장인가. 방장은 모임을 나갈 수 없으므로 늘 현재 멤버다. */
    private boolean isGroupOwner(Memo memo, UUID userId) {
        return memo.getGroupId() != null && groupRepository.findById(memo.getGroupId())
                .map(group -> group.getCreatedBy().getId().equals(userId))
                .orElse(false);
    }

    /**
     * 보는 사람 본인의 내 고정 시각(memo_pins). 모임 메모도 같다(모임 고정은 groupPinnedAt).
     * @param pins 미리 읽어 둔 보는 사람의 고정(memoId → 시각). null 이면 이 메모에 대해 개별 조회한다.
     */
    private LocalDateTime pinnedAtFor(Memo memo, Viewer viewer, Map<UUID, LocalDateTime> pins) {
        if (pins != null) {
            return pins.get(memo.getId());
        }
        return memoPinRepository.findFirstByUser_IdAndMemo_Id(viewer.id(), memo.getId())
                .map(MemoPin::getPinnedAt)
                .orElse(null);
    }

    /** 보는 사람의 내 고정 전부를 한 번에 읽는다 (목록의 N+1 방지). */
    private Map<UUID, LocalDateTime> pinnedAts(UUID userId) {
        Map<UUID, LocalDateTime> pins = new HashMap<>();
        for (MemoPin pin : memoPinRepository.findAllByUser_Id(userId)) {
            pins.put(pin.getMemo().getId(), pin.getPinnedAt());
        }
        return pins;
    }

    /** 내 고정 먼저(고정 시각 최신순), 나머지는 원래 순서 그대로(정렬이 안정적이다). */
    private static Comparator<Memo> pinnedFirst(Map<UUID, LocalDateTime> pins) {
        return Comparator.comparing((Memo memo) -> pins.get(memo.getId()),
                Comparator.nullsLast(Comparator.reverseOrder()));
    }

    private void validateAccess(Memo memo, UUID userId) {
        boolean readable = memo.getAuthor().getId().equals(userId)
                || memoShareRepository.existsByMemo_IdAndSharedTo_Id(memo.getId(), userId)
                || isCurrentGroupMember(memo, userId)
                || memoGroupShareRepository.existsSharedToMember(memo.getId(), userId);
        if (!readable) {
            throw new UnauthorizedAccessException(NO_READ_PERMISSION);
        }
    }

    /**
     * 수정 권한: 모임 메모는 현재 모임 멤버만 (나간·강퇴된 작성자도 불가), 개인 메모는 작성자만.
     * 어느 쪽이든 '수정 가능'으로 공유받은 사람은 고칠 수 있다.
     */
    private void validateEditAccess(Memo memo, UUID userId) {
        if (memoShareRepository.existsByMemo_IdAndSharedTo_IdAndCanEditTrue(memo.getId(), userId)) return;
        if (memo.getGroupId() != null) {
            if (isCurrentGroupMember(memo, userId)) return;
            if (memo.getAuthor().getId().equals(userId)) {
                throw new UnauthorizedAccessException("모임을 나가서 이 모임 메모는 고칠 수 없어요. 삭제는 할 수 있어요.");
            }
            throw new UnauthorizedAccessException(NO_EDIT_PERMISSION);
        }
        if (!memo.getAuthor().getId().equals(userId)) {
            throw new UnauthorizedAccessException(NO_EDIT_PERMISSION);
        }
    }

    private void requireGroupMember(UUID groupId, UUID userId) {
        if (!groupRepository.existsById(groupId)) {
            throw new ItemNotFoundException("모임을 찾을 수 없어요.");
        }
        if (!groupMemberRepository.existsByGroup_IdAndUser_Id(groupId, userId)) {
            throw new UnauthorizedAccessException("모임 멤버만 할 수 있어요.");
        }
    }

    /**
     * 태그할 메모 목록을 확인합니다. 요청자가 읽을 수 있는 메모만 새로 태그할 수 있고, 존재하지 않는 ID 는 무시합니다.
     * 수정할 때 이미 태그되어 있던 메모 중 요청자가 읽을 수 없는 것(응답에서 숨겨진 것)은 요청에 없어도 그대로 둡니다.
     */
    private List<Memo> resolveTaggedMemos(List<UUID> taggedMemoIds, Viewer viewer, Memo current) {
        List<Memo> result = new ArrayList<>();
        Set<UUID> alreadyTagged = new HashSet<>();
        if (current != null && current.getTaggedMemos() != null) {
            for (Memo existing : current.getTaggedMemos()) {
                alreadyTagged.add(existing.getId());
                if (!canRead(existing, viewer)) {
                    result.add(existing);
                }
            }
        }
        if (taggedMemoIds == null || taggedMemoIds.isEmpty()) {
            return result;
        }
        Set<UUID> ids = new LinkedHashSet<>(taggedMemoIds);
        ids.remove(null);
        for (Memo tagged : memoRepository.findAllById(ids)) {
            if (current != null && tagged.getId().equals(current.getId())) {
                continue; // 자기 자신은 태그하지 않는다
            }
            if (result.contains(tagged)) {
                continue;
            }
            if (!alreadyTagged.contains(tagged.getId()) && !canRead(tagged, viewer)) {
                throw new UnauthorizedAccessException("볼 수 없는 메모는 언급할 수 없어요.");
            }
            result.add(tagged);
        }
        return result;
    }

    /**
     * 메모 쪽에서 연결할 수 있는 일정만 고릅니다.
     * - 내가 만든 일정
     * - 모임 일정: 같은 모임의 메모이고 내가 그 모임의 현재 멤버일 때
     * - 이미 이 메모와 연결된 일정
     * 참여자로만 들어간 남의 일정에는 연결하지 않습니다 (요청에 있어도 건너뜀). 없는 ID 도 무시합니다.
     */
    private List<Schedule> resolveSchedulesToLink(List<UUID> scheduleIds, Viewer viewer, UUID memoGroupId, Memo current) {
        List<Schedule> result = new ArrayList<>();
        if (scheduleIds == null || scheduleIds.isEmpty()) {
            return result;
        }
        Set<UUID> ids = new LinkedHashSet<>(scheduleIds);
        ids.remove(null);
        for (Schedule schedule : scheduleRepository.findAllById(ids)) {
            boolean alreadyLinked = current != null && isLinked(schedule, current);
            if (alreadyLinked || canLinkSchedule(schedule, viewer, memoGroupId)) {
                result.add(schedule);
            }
        }
        return result;
    }

    private boolean canLinkSchedule(Schedule schedule, Viewer viewer, UUID memoGroupId) {
        if (schedule.getAuthor().getId().equals(viewer.id())) return true;
        return schedule.getGroupId() != null
                && schedule.getGroupId().equals(memoGroupId)
                && viewer.isMemberOf(schedule.getGroupId());
    }

    private static boolean isLinked(Schedule schedule, Memo memo) {
        return schedule.getTaggedMemos() != null
                && schedule.getTaggedMemos().stream().anyMatch(m -> m.getId().equals(memo.getId()));
    }

    /** 일정 열람 권한: 작성자, (모임 일정) 현재 모임 멤버, 참여자 */
    private boolean canSeeSchedule(Schedule schedule, Viewer viewer) {
        if (schedule.getAuthor().getId().equals(viewer.id())) return true;
        if (viewer.isMemberOf(schedule.getGroupId())) return true;
        return schedule.getParticipants() != null
                && schedule.getParticipants().stream().anyMatch(p -> p.getId().equals(viewer.id()));
    }

    /**
     * 메모 수정 시 일정 연결을 맞춘다.
     * 메모 쪽에서 만든 연결(mentionedScheduleIds)만 끊고, 일정 쪽에서 만든 연결은 메모를 고쳐도 그대로 둔다.
     */
    private void syncMentionedSchedules(Memo memo, List<UUID> requestedIds, List<Schedule> linkable) {
        Set<UUID> requested = new HashSet<>(requestedIds);
        requested.remove(null);
        Set<UUID> previousMentions = memo.getMentionedScheduleIds() != null
                ? new HashSet<>(memo.getMentionedScheduleIds()) : new HashSet<>();

        // 1) 본문에서 빠진 메모 쪽 연결을 끊는다.
        Set<UUID> toUnlink = new HashSet<>(previousMentions);
        toUnlink.removeAll(requested);
        if (!toUnlink.isEmpty()) {
            for (Schedule schedule : scheduleRepository.findAllById(toUnlink)) {
                if (schedule.getTaggedMemos() != null) {
                    schedule.getTaggedMemos().removeIf(m -> m.getId().equals(memo.getId()));
                }
            }
        }

        // 2) 새로 언급한 일정에 연결한다. 일정 쪽에서 이미 연결해 둔 것은 메모 쪽 연결로 바꾸지 않는다.
        Set<UUID> mentions = new HashSet<>();
        for (Schedule schedule : linkable) {
            if (!isLinked(schedule, memo)) {
                linkSchedule(schedule, memo);
                mentions.add(schedule.getId());
            } else if (previousMentions.contains(schedule.getId())) {
                mentions.add(schedule.getId());
            }
        }
        memo.replaceMentionedSchedules(mentions);
    }

    private void linkSchedule(Schedule schedule, Memo memo) {
        if (schedule.getTaggedMemos() != null && !schedule.getTaggedMemos().contains(memo)) {
            schedule.getTaggedMemos().add(memo);
        }
    }

    private String handleImageUpload(MultipartFile image, String currentImageUrl) {
        if (image == null || image.isEmpty()) {
            return currentImageUrl;
        }
        return "/uploads/" + fileService.storeFile(image);
    }

    /** 태그 정리: 앞뒤 공백 제거, 빈 값/중복 제거. 길이(코드 포인트)·개수 제한을 넘으면 400. */
    private List<String> normalizeTags(List<String> tagNames) {
        List<String> result = new ArrayList<>();
        if (tagNames == null) return result;
        Set<String> seen = new LinkedHashSet<>();
        for (String name : tagNames) {
            if (name == null) continue;
            String trimmed = name.trim();
            if (trimmed.isEmpty()) continue;
            if (CodePointSizeValidator.length(trimmed) > MAX_TAG_LENGTH) {
                throw new BadRequestException("태그는 " + MAX_TAG_LENGTH + "자까지 쓸 수 있어요.");
            }
            seen.add(trimmed);
        }
        if (seen.size() > MAX_TAG_COUNT) {
            throw new BadRequestException("태그는 " + MAX_TAG_COUNT + "개까지 등록할 수 있어요.");
        }
        result.addAll(seen);
        return result;
    }

    private void saveTags(Memo memo, List<String> tagNames) {
        if (tagNames == null || tagNames.isEmpty()) return;

        List<MemoTag> tags = tagNames.stream()
                .map(name -> MemoTag.builder()
                        .memo(memo)
                        .name(name)
                        .build())
                .collect(Collectors.toList());
        memoTagRepository.saveAll(tags);
    }

    private MemoAiInsight updateOrCreateInfo(Memo memo, AiAnalysisSupport.Analyzed analyzed) {
        String ocrText = analyzed.ocrText();
        String summary = analyzed.summary();
        // Gemini 가 keywords 를 null 로 돌려줘도 빈 목록으로 저장한다.
        List<String> keywords = analyzed.keywords();
        String sourceHash = analyzed.sourceHash();

        return memoAiInsightRepository.findById(memo.getId())
                .map(existing -> {
                    existing.update(ocrText, summary, keywords, sourceHash);
                    return existing;
                })
                .orElseGet(() -> MemoAiInsight.builder()
                        .memo(memo)
                        .ocrText(ocrText)
                        .summary(summary)
                        .keywords(keywords)
                        .sourceHash(sourceHash)
                        .build());
    }

    private Set<UUID> favoriteMemoIds(UUID userId) {
        return new HashSet<>(memoFavoriteRepository.findMemoIdsByUserId(userId));
    }

    private static boolean contains(String text, String keyword) {
        return text != null && text.toLowerCase(Locale.ROOT).contains(keyword);
    }

    private static String shortTitle(String title) {
        String trimmed = title == null ? "" : title.trim();
        if (CodePointSizeValidator.length(trimmed) <= NOTIFICATION_TITLE_LENGTH) {
            return trimmed;
        }
        return CodePointSizeValidator.truncate(trimmed, NOTIFICATION_TITLE_LENGTH) + "…";
    }

    /**
     * 작성자: 모두 true(모임을 나간 작성자는 수정·태그·모임 고정 false). 모임 멤버: 수정·태그, 방장은 삭제·모임 고정도.
     * 공유받은 사람: '수정 가능'이면 수정·태그. 공유한 모임의 멤버: 내 고정만. 읽을 수 있으면 누구나 내 고정.
     */
    private MemoResponse.Permissions permissionsOf(Memo memo, Viewer viewer) {
        boolean isAuthor = memo.getAuthor().getId().equals(viewer.id());
        boolean edit = canEdit(memo, viewer);
        return MemoResponse.Permissions.builder()
                .edit(edit)
                .tag(edit)
                .delete(canDelete(memo, viewer.id()))
                .share(isAuthor)
                .analyze(isAuthor)
                .pin(canRead(memo, viewer))
                .groupPin(memo.getGroupId() != null && canGroupPin(memo, viewer))
                .build();
    }

    private MemoResponse convertToResponse(Memo memo, Viewer viewer, Set<UUID> favoriteIds) {
        return convertToResponse(memo, viewer, favoriteIds, null);
    }

    /**
     * @param viewer      응답을 받는 사용자 (isFavorite, pinnedAt, sharedWith, permissions, 연결 목록은 이 사용자 기준)
     * @param favoriteIds 미리 조회해 둔 viewer 의 즐겨찾기 메모 ID 목록. null 이면 이 메모에 대해 개별 조회합니다.
     * @param pins        미리 조회해 둔 viewer 의 내 고정(memoId → 시각). null 이면 이 메모에 대해 개별 조회합니다.
     */
    private MemoResponse convertToResponse(Memo memo, Viewer viewer, Set<UUID> favoriteIds, Map<UUID, LocalDateTime> pins) {
        UUID viewerId = viewer.id();
        List<String> tags = memoTagRepository.findAllByMemo(memo).stream()
                .map(MemoTag::getName)
                .collect(Collectors.toList());

        MemoAiInsight insight = memoAiInsightRepository.findById(memo.getId()).orElse(null);

        // 연결된 메모·일정은 요청한 사용자가 열 수 있는 것만 담는다 (비공개 제목 노출 방지).
        List<MemoResponse.MemoSummaryResponse> memoSummaries = new ArrayList<>();
        if (memo.getTaggedMemos() != null) {
            memoSummaries = memo.getTaggedMemos().stream()
                .filter(m -> canRead(m, viewer))
                .map(m -> MemoResponse.MemoSummaryResponse.builder()
                    .id(m.getId())
                    .title(m.getTitle())
                    .build())
                .collect(Collectors.toList());
        }

        List<MemoResponse.ScheduleSummaryResponse> scheduleSummaries = scheduleRepository.findByTaggedMemosContains(memo).stream()
                .filter(s -> canSeeSchedule(s, viewer))
                .sorted(java.util.Comparator.comparing(Schedule::getStartTime, java.util.Comparator.nullsLast(java.util.Comparator.naturalOrder())))
                .map(s -> MemoResponse.ScheduleSummaryResponse.builder()
                    .id(s.getId())
                    .title(s.getTitle())
                    .startTime(s.getStartTime())
                    .build())
                .collect(Collectors.toList());

        boolean isFavorite = favoriteIds != null
                ? favoriteIds.contains(memo.getId())
                : memoFavoriteRepository.existsByUser_IdAndMemo_Id(viewerId, memo.getId());

        // 공유 대상 목록(친구·모임)은 작성자에게만 보여준다 (공유 관리 UI 와 '공유함' 표시용).
        List<MemoResponse.SharedUserResponse> sharedWith = new ArrayList<>();
        List<UUID> sharedGroupIds = new ArrayList<>();
        if (memo.getAuthor().getId().equals(viewerId)) {
            // (memo, 사용자) 는 유니크라 한 사람당 한 줄이다.
            sharedWith = memoShareRepository.findAllByMemo(memo).stream()
                    .map(share -> MemoResponse.SharedUserResponse.builder()
                            .id(share.getSharedTo().getId())
                            .nickname(share.getSharedTo().getNickname())
                            .profileImage(share.getSharedTo().getProfileImage())
                            .canEdit(share.isCanEdit())
                            .build())
                    .collect(Collectors.toList());
            sharedGroupIds = memoGroupShareRepository.findGroupIdsByMemoId(memo.getId());
        }

        return MemoResponse.builder()
                .id(memo.getId())
                .title(memo.getTitle())
                .content(memo.getContent())
                .imageUrl(memo.getImageUrl())
                .authorId(memo.getAuthor().getId())
                .authorNickname(memo.getAuthor().getNickname())
                .authorProfileImage(memo.getAuthor().getProfileImage())
                .lastModifierId(memo.getLastModifier() != null ? memo.getLastModifier().getId() : null)
                .lastModifierNickname(memo.getLastModifier() != null ? memo.getLastModifier().getNickname() : null)
                .groupId(memo.getGroupId())
                .tags(tags)
                .insight(insight != null ? convertToInsightResponse(insight) : null)
                .isFavorite(isFavorite)
                .sharedWith(sharedWith)
                .sharedGroupIds(sharedGroupIds)
                .taggedMemos(memoSummaries)
                .taggedSchedules(scheduleSummaries)
                .createdAt(memo.getCreatedAt())
                .updatedAt(memo.getUpdatedAt())
                .version(memo.getVersion() != null ? memo.getVersion() : 0L)
                .pinnedAt(pinnedAtFor(memo, viewer, pins))
                .groupPinnedAt(memo.getGroupId() != null ? memo.getPinnedAt() : null)
                .permissions(permissionsOf(memo, viewer))
                .build();
    }

    private MemoInsightResponse convertToInsightResponse(MemoAiInsight insight) {
        return MemoInsightResponse.builder()
                .ocrText(insight.getOcrText())
                .summary(insight.getSummary())
                .keywords(insight.getKeywords() != null ? insight.getKeywords() : List.of())
                .analyzedAt(insight.getAnalyzedAt())
                .stale(insight.getMemo() != null && AiAnalysisSupport.isStale(insight.getSourceHash(), insight.getAnalyzedAt(),
                        insight.getMemo().getContent(), insight.getMemo().getImageUrl(), insight.getMemo().getUpdatedAt()))
                .build();
    }
}
