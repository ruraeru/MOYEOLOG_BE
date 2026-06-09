package com.moyeolog.moyelog_BE.service;

import com.moyeolog.moyelog_BE.dto.MemoInsightResponse;
import com.moyeolog.moyelog_BE.dto.MemoRequest;
import com.moyeolog.moyelog_BE.dto.MemoResponse;
import com.moyeolog.moyelog_BE.dto.MemoShareRequest;
import com.moyeolog.moyelog_BE.entity.*;
import com.moyeolog.moyelog_BE.exception.ResourceNotFoundException;
import com.moyeolog.moyelog_BE.exception.UnauthorizedAccessException;
import com.moyeolog.moyelog_BE.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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

    // ─── 공개 메서드 (API) ──────────────────────────────────────────

    @Transactional
    public void shareMemo(UUID authorId, UUID memoId, MemoShareRequest request) {
        Memo memo = findMemoById(memoId);
        validateAuthor(memo, authorId);

        request.getFriendIds().forEach(friendId -> shareWithFriend(memo, friendId));
    }

    @Transactional(readOnly = true)
    public List<MemoResponse> getSharedMemos(UUID userId) {
        User user = findUserById(userId);

        return memoShareRepository.findBySharedToOrderBySharedAtDesc(user).stream()
                .map(share -> convertToResponse(share.getMemo()))
                .collect(Collectors.toList());
    }

    @Transactional
    public MemoResponse createMemo(UUID userId, MemoRequest request, MultipartFile image) {
        User author = findUserById(userId);
        String imageUrl = handleImageUpload(image, null);

        List<Memo> taggedMemos = new ArrayList<>();
        if (request.getTaggedMemoIds() != null && !request.getTaggedMemoIds().isEmpty()) {
            taggedMemos = memoRepository.findAllById(request.getTaggedMemoIds());
        }

        Memo memo = Memo.builder()
                .author(author)
                .groupId(request.getGroupId())
                .title(request.getTitle())
                .content(request.getContent())
                .imageUrl(imageUrl)
                .taggedMemos(taggedMemos)
                .build();

        Memo savedMemo = memoRepository.save(memo);
        saveTags(savedMemo, request.getTags());

        syncSchedules(savedMemo, request.getTaggedScheduleIds());

        return convertToResponse(savedMemo);
    }

    @Transactional(readOnly = true)
    public List<MemoResponse> getMyMemos(UUID userId) {
        User author = findUserById(userId);

        return memoRepository.findAllByAuthorOrderByCreatedAtDesc(author).stream()
                .map(this::convertToResponse)
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
        if (memoAiInsightRepository.existsById(memo.getId())) {
            memoAiInsightRepository.deleteById(memo.getId());
        }

        memoRepository.delete(memo);
    }

    @Transactional
    public MemoResponse updateTags(UUID userId, UUID memoId, List<String> newTags) {
        Memo memo = findMemoById(memoId);
        validateAuthor(memo, userId);

        memoTagRepository.deleteAllByMemo(memo);
        saveTags(memo, newTags);

        return convertToResponse(memo);
    }

    @Transactional
    public MemoResponse updateMemo(UUID userId, UUID memoId, MemoRequest request, MultipartFile image) {
        Memo memo = findMemoById(memoId);
        validateAccess(memo, userId);
        User modifier = findUserById(userId);

        String imageUrl = handleImageUpload(image, memo.getImageUrl());

        List<Memo> taggedMemos = new ArrayList<>();
        if (request.getTaggedMemoIds() != null && !request.getTaggedMemoIds().isEmpty()) {
            taggedMemos = memoRepository.findAllById(request.getTaggedMemoIds());
        }

        memo.update(request.getTitle(), request.getContent(), imageUrl, modifier, taggedMemos);

        memoTagRepository.deleteAllByMemo(memo);
        saveTags(memo, request.getTags());

        syncSchedules(memo, request.getTaggedScheduleIds());

        return convertToResponse(memo);
    }

    @Transactional(readOnly = true)
    public MemoResponse getMemo(UUID userId, UUID memoId) {
        Memo memo = findMemoById(memoId);
        validateAccess(memo, userId);
        return convertToResponse(memo);
    }

    @Transactional(readOnly = true)
    public List<MemoResponse> getGroupMemos(UUID userId, UUID groupId) {
        return memoRepository.findByGroupIdOrderByCreatedAtDesc(groupId).stream()
                .map(this::convertToResponse)
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

    @Transactional(readOnly = true)
    public MemoInsightResponse getMemoInsight(UUID userId, UUID memoId) {
        Memo memo = findMemoById(memoId);
        validateAccess(memo, userId);

        return memoAiInsightRepository.findById(memoId)
                .map(this::convertToInsightResponse)
                .orElse(null);
    }

    @Transactional
    public MemoResponse toggleFavorite(UUID userId, UUID memoId) {
        Memo memo = findMemoById(memoId);
        validateAccess(memo, userId);

        memo.toggleFavorite();
        return convertToResponse(memo);
    }

    // ─── 내부 헬퍼 메서드 (분리 및 최적화) ──────────────────────────

    private void shareWithFriend(Memo memo, UUID friendId) {
        User friend = findUserById(friendId);

        if (memoShareRepository.findByMemoAndSharedTo(memo, friend).isEmpty()) {
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

    private void validateAccess(Memo memo, UUID userId) {
        User user = findUserById(userId);
        
        boolean isAuthor = memo.getAuthor().getId().equals(userId);
        if (isAuthor) return;

        boolean isShared = memoShareRepository.findByMemoAndSharedTo(memo, user).isPresent();
        if (isShared) return;

        if (memo.getGroupId() != null) {
            Group group = groupRepository.findById(memo.getGroupId()).orElse(null);
            if (group != null) {
                boolean isGroupMember = groupMemberRepository.findByGroupAndUser(group, user).isPresent();
                if (isGroupMember) return;
            }
        }

        throw new UnauthorizedAccessException("해당 메모에 접근할 수 있는 권한이 없습니다.");
    }

    private String handleImageUpload(MultipartFile image, String currentImageUrl) {
        if (image == null || image.isEmpty()) {
            return currentImageUrl;
        }
        return "/uploads/" + fileService.storeFile(image);
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
        return memoAiInsightRepository.findById(memo.getId())
                .map(existing -> {
                    existing.update(
                            (String) result.get("ocrText"),
                            (String) result.get("summary"),
                            (List<String>) result.get("keywords")
                    );
                    return existing;
                })
                .orElseGet(() -> MemoAiInsight.builder()
                        .memo(memo)
                        .ocrText((String) result.get("ocrText"))
                        .summary((String) result.get("summary"))
                        .keywords((List<String>) result.get("keywords"))
                        .build());
    }

    private void syncSchedules(Memo memo, List<UUID> newScheduleIds) {
        List<Schedule> oldSchedules = scheduleRepository.findByTaggedMemosContains(memo);
        List<Schedule> newSchedules = new ArrayList<>();
        if (newScheduleIds != null && !newScheduleIds.isEmpty()) {
            newSchedules = scheduleRepository.findAllById(newScheduleIds);
        }

        for (Schedule old : oldSchedules) {
            if (!newSchedules.contains(old)) {
                old.getTaggedMemos().remove(memo);
                scheduleRepository.save(old);
            }
        }
        for (Schedule newSch : newSchedules) {
            if (!newSch.getTaggedMemos().contains(memo)) {
                newSch.getTaggedMemos().add(memo);
                scheduleRepository.save(newSch);
            }
        }
    }

    private MemoResponse convertToResponse(Memo memo) {
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
                .isFavorite(memo.getIsFavorite() != null && memo.getIsFavorite())
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
                .keywords(insight.getKeywords())
                .analyzedAt(insight.getAnalyzedAt())
                .build();
    }
}
