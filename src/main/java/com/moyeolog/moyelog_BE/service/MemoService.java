package com.moyeolog.moyelog_BE.service;

import com.moyeolog.moyelog_BE.dto.MemoInsightResponse;
import com.moyeolog.moyelog_BE.dto.MemoRequest;
import com.moyeolog.moyelog_BE.dto.MemoResponse;
import com.moyeolog.moyelog_BE.dto.MemoShareRequest;
import com.moyeolog.moyelog_BE.entity.*;
import com.moyeolog.moyelog_BE.exception.BadRequestException;
import com.moyeolog.moyelog_BE.exception.ResourceNotFoundException;
import com.moyeolog.moyelog_BE.exception.UnauthorizedAccessException;
import com.moyeolog.moyelog_BE.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 메모 비즈니스 로직을 처리하는 서비스 클래스입니다.
 * 다중 태그 기능 (메모 및 일정)이 추가되었습니다.
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
    private final MemoAiInsightRepository memoAiInsightRepository;
    private final GeminiService geminiService;
    private final GroupRepository groupRepository;
    private final GroupMemberRepository groupMemberRepository;
    private final ScheduleRepository scheduleRepository;
    private final FriendRepository friendRepository;
    private final MemoFavoriteRepository memoFavoriteRepository;

    private static final int MAX_TAG_LENGTH = 50;
    private static final int MAX_TAG_COUNT = 50;

    // ─── 공개 메서드 (API) ──────────────────────────────────────────

    /** 메모 공유: 작성자만, 그리고 작성자의 (수락된) 친구에게만 공유할 수 있습니다. */
    @Transactional
    public void shareMemo(UUID authorId, UUID memoId, MemoShareRequest request) {
        Memo memo = findMemoById(memoId);
        validateAuthor(memo, authorId);

        if (request == null || request.getFriendIds() == null || request.getFriendIds().isEmpty()) {
            throw new BadRequestException("공유할 친구를 한 명 이상 선택해주세요.");
        }

        Set<UUID> friendIds = new LinkedHashSet<>(request.getFriendIds());
        friendIds.remove(null);
        friendIds.remove(authorId);

        // 먼저 전부 검증한 뒤 저장한다 (일부만 공유되는 일이 없도록).
        for (UUID friendId : friendIds) {
            if (!friendRepository.areFriends(authorId, friendId)) {
                throw new UnauthorizedAccessException("친구에게만 메모를 공유할 수 있습니다.");
            }
        }
        friendIds.forEach(friendId -> shareWithFriend(memo, friendId));
    }

    /** 메모 공유 해제: 작성자가 공유 대상을 제거하거나, 공유받은 사용자가 스스로 공유를 해제합니다. */
    @Transactional
    public void unshareMemo(UUID requesterId, UUID memoId, UUID targetUserId) {
        Memo memo = findMemoById(memoId);
        boolean isAuthor = memo.getAuthor().getId().equals(requesterId);
        if (!isAuthor && !requesterId.equals(targetUserId)) {
            throw new UnauthorizedAccessException("메모 작성자만 공유를 해제할 수 있습니다.");
        }

        List<MemoShare> shares = memoShareRepository.findAllByMemo_IdAndSharedTo_Id(memoId, targetUserId);
        if (shares.isEmpty()) {
            throw new ResourceNotFoundException("메모 공유", targetUserId);
        }
        memoShareRepository.deleteAll(shares);
    }

    @Transactional(readOnly = true)
    public List<MemoResponse> getSharedMemos(UUID userId) {
        User user = findUserById(userId);
        Set<UUID> favoriteIds = favoriteMemoIds(userId);
        return memoShareRepository.findBySharedToOrderBySharedAtDesc(user).stream()
                .map(MemoShare::getMemo)
                .distinct()
                .map(memo -> convertToResponse(memo, userId, favoriteIds))
                .collect(Collectors.toList());
    }

    @Transactional
    public MemoResponse createMemo(UUID userId, MemoRequest request, MultipartFile image) {
        User author = findUserById(userId);

        // 그룹 메모는 해당 그룹의 멤버만 작성할 수 있다 (없는 그룹 404, 비멤버 403).
        if (request.getGroupId() != null) {
            requireGroupMember(request.getGroupId(), userId);
        }
        List<Memo> taggedMemos = resolveTaggedMemos(request.getTaggedMemoIds(), userId, null);
        List<String> tags = normalizeTags(request.getTags());
        List<Schedule> schedules = resolveSchedulesToTag(request.getTaggedScheduleIds(), userId, null);

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
        syncSchedules(savedMemo, schedules);

        return convertToResponse(savedMemo, userId, null);
    }

    @Transactional(readOnly = true)
    public List<MemoResponse> getMyMemos(UUID userId) {
        User author = findUserById(userId);
        Set<UUID> favoriteIds = favoriteMemoIds(userId);
        return memoRepository.findAllByAuthorOrderByCreatedAtDesc(author).stream()
                .map(memo -> convertToResponse(memo, userId, favoriteIds))
                .collect(Collectors.toList());
    }

    @Transactional
    public void deleteMemo(UUID userId, UUID memoId) {
        Memo memo = findMemoById(memoId);
        validateAuthor(memo, userId);

        // 메모 삭제 시 연결된 일정에서도 해당 메모를 삭제 (동기화)
        syncSchedules(memo, new ArrayList<>());

        // 다른 메모가 이 메모를 태그하고 있다면 관계 삭제 (자기 참조 동기화)
        List<Memo> referencingMemos = memoRepository.findByTaggedMemosContains(memo);
        for (Memo refMemo : referencingMemos) {
            refMemo.getTaggedMemos().remove(memo);
            memoRepository.save(refMemo);
        }

        // 연관된 자식 엔티티 삭제
        memoTagRepository.deleteAllByMemo(memo);
        memoShareRepository.deleteAllByMemo(memo);
        memoFavoriteRepository.deleteAllByMemo(memo);
        if (memoAiInsightRepository.existsById(memo.getId())) {
            memoAiInsightRepository.deleteById(memo.getId());
        }

        String imageUrl = memo.getImageUrl();
        memoRepository.delete(memo);

        // 첨부 이미지 파일은 삭제가 커밋된 뒤에 지운다.
        fileService.deleteFileAfterCommit(imageUrl);
    }

    @Transactional
    public MemoResponse updateTags(UUID userId, UUID memoId, List<String> newTags) {
        Memo memo = findMemoById(memoId);
        validateAuthor(memo, userId);

        List<String> tags = normalizeTags(newTags);
        memoTagRepository.deleteAllByMemo(memo);
        saveTags(memo, tags);

        return convertToResponse(memo, userId, null);
    }

    @Transactional
    public MemoResponse updateMemo(UUID userId, UUID memoId, MemoRequest request, MultipartFile image) {
        Memo memo = findMemoById(memoId);
        // 수정은 작성자 또는 (그룹 메모의 경우) 그룹 멤버만 가능. 공유받은 사용자는 읽기 전용.
        validateEditAccess(memo, userId);

        // 요청에 groupId 가 있으면 그 그룹의 멤버여야 한다 (없는 그룹 404, 비멤버 403).
        // 메모의 소속 그룹 자체는 수정으로 바뀌지 않는다.
        if (request.getGroupId() != null) {
            requireGroupMember(request.getGroupId(), userId);
        }

        User modifier = findUserById(userId);
        List<Memo> taggedMemos = resolveTaggedMemos(request.getTaggedMemoIds(), userId, memo);
        List<String> tags = normalizeTags(request.getTags());
        List<Schedule> schedules = resolveSchedulesToTag(request.getTaggedScheduleIds(), userId, memo);

        String previousImageUrl = memo.getImageUrl();
        String imageUrl = handleImageUpload(image, previousImageUrl);

        memo.update(request.getTitle(), request.getContent(), imageUrl, modifier, taggedMemos);

        memoTagRepository.deleteAllByMemo(memo);
        saveTags(memo, tags);
        syncSchedules(memo, schedules);

        // 이미지가 교체되었으면 이전 파일을 지운다.
        if (previousImageUrl != null && !previousImageUrl.equals(imageUrl)) {
            fileService.deleteFileAfterCommit(previousImageUrl);
        }

        return convertToResponse(memo, userId, null);
    }

    @Transactional(readOnly = true)
    public MemoResponse getMemo(UUID userId, UUID memoId) {
        Memo memo = findMemoById(memoId);
        validateAccess(memo, userId);
        return convertToResponse(memo, userId, null);
    }

    /** 그룹 메모 목록: 그룹 멤버 전용 (없는 그룹 404, 비멤버 403) */
    @Transactional(readOnly = true)
    public List<MemoResponse> getGroupMemos(UUID userId, UUID groupId) {
        requireGroupMember(groupId, userId);
        Set<UUID> favoriteIds = favoriteMemoIds(userId);
        return memoRepository.findByGroupIdOrderByCreatedAtDesc(groupId).stream()
                .map(memo -> convertToResponse(memo, userId, favoriteIds))
                .collect(Collectors.toList());
    }

    @Transactional
    public MemoInsightResponse analyzeMemo(UUID userId, UUID memoId) {
        Memo memo = findMemoById(memoId);
        validateAuthor(memo, userId);

        log.info("[AI Analyze] Request for Memo: {}", memoId);
        Map<String, Object> analysisResult = geminiService.analyzeMemo(memo);
        
        MemoAiInsight insight = updateOrCreateInfo(memo, analysisResult);
        return convertToInsightResponse(memoAiInsightRepository.save(insight));
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
        validateAccess(memo, userId);

        List<MemoFavorite> existing = memoFavoriteRepository.findAllByUser_IdAndMemo_Id(userId, memoId);
        boolean favorite;
        if (existing.isEmpty()) {
            memoFavoriteRepository.save(MemoFavorite.builder()
                    .user(findUserById(userId))
                    .memo(memo)
                    .build());
            favorite = true;
        } else {
            memoFavoriteRepository.deleteAll(existing);
            favorite = false;
        }

        MemoResponse response = convertToResponse(memo, userId, Set.of());
        response.setIsFavorite(favorite);
        return response;
    }

    // ─── 내부 헬퍼 메서드 (분리 및 최적화) ──────────────────────────

    private void shareWithFriend(Memo memo, UUID friendId) {
        User friend = findUserById(friendId);
        if (!memoShareRepository.existsByMemo_IdAndSharedTo_Id(memo.getId(), friendId)) {
            MemoShare memoShare = MemoShare.builder()
                    .memo(memo)
                    .sharedTo(friend)
                    .build();
            memoShareRepository.save(memoShare);
        }
    }

    private User findUserById(UUID userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("사용자", userId));
    }

    private Memo findMemoById(UUID memoId) {
        return memoRepository.findById(memoId)
                .orElseThrow(() -> new ResourceNotFoundException("메모", memoId));
    }

    private void validateAuthor(Memo memo, UUID userId) {
        if (!memo.getAuthor().getId().equals(userId)) {
            throw new UnauthorizedAccessException("해당 메모에 대한 권한이 없습니다. (작성자 전용)");
        }
    }

    /** 읽기 권한: 작성자, 공유받은 사용자, (그룹 메모의 경우) 그룹 멤버 */
    private boolean canRead(Memo memo, UUID userId) {
        if (memo.getAuthor().getId().equals(userId)) return true;
        if (memoShareRepository.existsByMemo_IdAndSharedTo_Id(memo.getId(), userId)) return true;
        return memo.getGroupId() != null
                && groupMemberRepository.existsByGroup_IdAndUser_Id(memo.getGroupId(), userId);
    }

    private void validateAccess(Memo memo, UUID userId) {
        if (!canRead(memo, userId)) {
            throw new UnauthorizedAccessException("해당 메모에 접근할 수 있는 권한이 없습니다.");
        }
    }

    /** 수정 권한: 작성자, 또는 그룹 메모의 그룹 멤버. 공유받은 사용자는 읽기 전용입니다. */
    private void validateEditAccess(Memo memo, UUID userId) {
        if (memo.getAuthor().getId().equals(userId)) return;
        if (memo.getGroupId() != null
                && groupMemberRepository.existsByGroup_IdAndUser_Id(memo.getGroupId(), userId)) {
            return;
        }
        throw new UnauthorizedAccessException("해당 메모를 수정할 수 있는 권한이 없습니다.");
    }

    private void requireGroupMember(UUID groupId, UUID userId) {
        if (!groupRepository.existsById(groupId)) {
            throw new ResourceNotFoundException("그룹", groupId);
        }
        if (!groupMemberRepository.existsByGroup_IdAndUser_Id(groupId, userId)) {
            throw new UnauthorizedAccessException("해당 그룹의 멤버가 아닙니다.");
        }
    }

    /**
     * 태그할 메모 목록을 확인합니다. 요청자가 읽을 수 있는 메모만 태그할 수 있습니다.
     * 이미 태그되어 있던 메모는(권한이 나중에 사라졌더라도) 그대로 유지할 수 있고, 존재하지 않는 ID 는 무시합니다.
     */
    private List<Memo> resolveTaggedMemos(List<UUID> taggedMemoIds, UUID userId, Memo current) {
        List<Memo> result = new ArrayList<>();
        if (taggedMemoIds == null || taggedMemoIds.isEmpty()) {
            return result;
        }
        Set<UUID> alreadyTagged = new HashSet<>();
        if (current != null && current.getTaggedMemos() != null) {
            current.getTaggedMemos().forEach(m -> alreadyTagged.add(m.getId()));
        }
        Set<UUID> ids = new LinkedHashSet<>(taggedMemoIds);
        ids.remove(null);
        for (Memo tagged : memoRepository.findAllById(ids)) {
            if (current != null && tagged.getId().equals(current.getId())) {
                continue; // 자기 자신은 태그하지 않는다
            }
            if (!alreadyTagged.contains(tagged.getId()) && !canRead(tagged, userId)) {
                throw new UnauthorizedAccessException("접근 권한이 없는 메모는 태그할 수 없습니다.");
            }
            result.add(tagged);
        }
        return result;
    }

    /**
     * 메모를 연결할 일정 목록을 확인합니다. 요청자가 볼 수 있는 일정(작성자/참여자/그룹 멤버)에만 새로 연결할 수 있습니다.
     */
    private List<Schedule> resolveSchedulesToTag(List<UUID> scheduleIds, UUID userId, Memo current) {
        List<Schedule> result = new ArrayList<>();
        if (scheduleIds == null || scheduleIds.isEmpty()) {
            return result;
        }
        Set<UUID> ids = new LinkedHashSet<>(scheduleIds);
        ids.remove(null);
        for (Schedule schedule : scheduleRepository.findAllById(ids)) {
            boolean alreadyLinked = current != null && schedule.getTaggedMemos() != null
                    && schedule.getTaggedMemos().stream().anyMatch(m -> m.getId().equals(current.getId()));
            if (!alreadyLinked && !canAccessSchedule(schedule, userId)) {
                throw new UnauthorizedAccessException("접근 권한이 없는 일정에는 메모를 연결할 수 없습니다.");
            }
            result.add(schedule);
        }
        return result;
    }

    private boolean canAccessSchedule(Schedule schedule, UUID userId) {
        if (schedule.getAuthor().getId().equals(userId)) return true;
        if (schedule.getParticipants() != null
                && schedule.getParticipants().stream().anyMatch(p -> p.getId().equals(userId))) {
            return true;
        }
        return schedule.getGroupId() != null
                && groupMemberRepository.existsByGroup_IdAndUser_Id(schedule.getGroupId(), userId);
    }

    private String handleImageUpload(MultipartFile image, String currentImageUrl) {
        if (image == null || image.isEmpty()) {
            return currentImageUrl;
        }
        return "/uploads/" + fileService.storeFile(image);
    }

    /** 태그 정리: 앞뒤 공백 제거, 빈 값/중복 제거. 길이·개수 제한을 넘으면 400. */
    private List<String> normalizeTags(List<String> tagNames) {
        List<String> result = new ArrayList<>();
        if (tagNames == null) return result;
        Set<String> seen = new LinkedHashSet<>();
        for (String name : tagNames) {
            if (name == null) continue;
            String trimmed = name.trim();
            if (trimmed.isEmpty()) continue;
            if (trimmed.length() > MAX_TAG_LENGTH) {
                throw new BadRequestException("태그는 " + MAX_TAG_LENGTH + "자를 초과할 수 없습니다.");
            }
            seen.add(trimmed);
        }
        if (seen.size() > MAX_TAG_COUNT) {
            throw new BadRequestException("태그는 " + MAX_TAG_COUNT + "개까지 등록할 수 있습니다.");
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

    private MemoAiInsight updateOrCreateInfo(Memo memo, Map<String, Object> result) {
        String ocrText = textOf(result, "ocrText");
        String summary = textOf(result, "summary");
        // Gemini 가 keywords 를 null 로 돌려줘도 빈 목록으로 저장한다.
        List<String> keywords = GeminiService.toKeywords(result != null ? result.get("keywords") : null);

        return memoAiInsightRepository.findById(memo.getId())
                .map(existing -> {
                    existing.update(ocrText, summary, keywords);
                    return existing;
                })
                .orElseGet(() -> MemoAiInsight.builder()
                        .memo(memo)
                        .ocrText(ocrText)
                        .summary(summary)
                        .keywords(keywords)
                        .build());
    }

    private static String textOf(Map<String, Object> result, String key) {
        Object value = result != null ? result.get(key) : null;
        return value != null ? value.toString() : "";
    }

    private void syncSchedules(Memo memo, List<Schedule> newSchedules) {
        List<Schedule> oldSchedules = scheduleRepository.findByTaggedMemosContains(memo);

        for (Schedule old : oldSchedules) {
            if (!newSchedules.contains(old)) {
                old.getTaggedMemos().remove(memo);
                scheduleRepository.save(old);
            }
        }

        for (Schedule newSch : newSchedules) {
            if (newSch.getTaggedMemos() != null && !newSch.getTaggedMemos().contains(memo)) {
                newSch.getTaggedMemos().add(memo);
                scheduleRepository.save(newSch);
            }
        }
    }

    private Set<UUID> favoriteMemoIds(UUID userId) {
        return new HashSet<>(memoFavoriteRepository.findMemoIdsByUserId(userId));
    }

    /**
     * @param viewerId    응답을 받는 사용자 (isFavorite, sharedWith 는 이 사용자 기준)
     * @param favoriteIds 미리 조회해 둔 viewer 의 즐겨찾기 메모 ID 목록. null 이면 이 메모에 대해 개별 조회합니다.
     */
    private MemoResponse convertToResponse(Memo memo, UUID viewerId, Set<UUID> favoriteIds) {
        List<String> tags = memoTagRepository.findAllByMemo(memo).stream()
                .map(MemoTag::getName)
                .collect(Collectors.toList());

        MemoAiInsight insight = memoAiInsightRepository.findById(memo.getId()).orElse(null);

        List<MemoResponse.MemoSummaryResponse> memoSummaries = new ArrayList<>();
        if (memo.getTaggedMemos() != null) {
            memoSummaries = memo.getTaggedMemos().stream()
                .map(m -> MemoResponse.MemoSummaryResponse.builder()
                    .id(m.getId())
                    .title(m.getTitle())
                    .build())
                .collect(Collectors.toList());
        }

        List<Schedule> schedules = scheduleRepository.findByTaggedMemosContains(memo);
        List<MemoResponse.ScheduleSummaryResponse> scheduleSummaries = schedules.stream()
                .map(s -> MemoResponse.ScheduleSummaryResponse.builder()
                    .id(s.getId())
                    .title(s.getTitle())
                    .build())
                .collect(Collectors.toList());

        boolean isFavorite = favoriteIds != null
                ? favoriteIds.contains(memo.getId())
                : memoFavoriteRepository.existsByUser_IdAndMemo_Id(viewerId, memo.getId());

        // 공유 대상 목록은 작성자에게만 보여준다 (공유 해제 UI 용).
        List<MemoResponse.SharedUserResponse> sharedWith = new ArrayList<>();
        if (memo.getAuthor().getId().equals(viewerId)) {
            sharedWith = memoShareRepository.findAllByMemo(memo).stream()
                    .map(MemoShare::getSharedTo)
                    .distinct()
                    .map(u -> MemoResponse.SharedUserResponse.builder()
                            .id(u.getId())
                            .nickname(u.getNickname())
                            .profileImage(u.getProfileImage())
                            .build())
                    .collect(Collectors.toList());
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
                .taggedMemos(memoSummaries)
                .taggedSchedules(scheduleSummaries)
                .createdAt(memo.getCreatedAt())
                .updatedAt(memo.getUpdatedAt())
                .build();
    }

    private MemoInsightResponse convertToInsightResponse(MemoAiInsight insight) {
        return MemoInsightResponse.builder()
                .ocrText(insight.getOcrText())
                .summary(insight.getSummary())
                .keywords(insight.getKeywords() != null ? insight.getKeywords() : List.of())
                .analyzedAt(insight.getAnalyzedAt())
                .build();
    }
}
