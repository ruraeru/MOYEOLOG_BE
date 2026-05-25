package com.moyeolog.moyelog_BE.service;

import com.moyeolog.moyelog_BE.dto.MemoInsightResponse;
import com.moyeolog.moyelog_BE.dto.MemoRequest;
import com.moyeolog.moyelog_BE.dto.MemoResponse;
import com.moyeolog.moyelog_BE.dto.MemoShareRequest;
import com.moyeolog.moyelog_BE.entity.*;
import com.moyeolog.moyelog_BE.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

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

    @Transactional
    public void shareMemo(UUID authorId, UUID memoId, MemoShareRequest request) {
        Memo memo = memoRepository.findById(memoId)
                .orElseThrow(() -> new RuntimeException("Memo not found"));

        if (!memo.getAuthor().getId().equals(authorId)) {
            throw new RuntimeException("Unauthorized share request");
        }

        for (UUID friendId : request.getFriendIds()) {
            User friend = userRepository.findById(friendId)
                    .orElseThrow(() -> new RuntimeException("Friend not found: " + friendId));

            if (memoShareRepository.findByMemoAndSharedTo(memo, friend).isPresent()) {
                continue;
            }

            MemoShare memoShare = MemoShare.builder()
                    .memo(memo)
                    .sharedTo(friend)
                    .build();
            memoShareRepository.save(memoShare);
        }
    }

    @Transactional(readOnly = true)
    public List<MemoResponse> getSharedMemos(UUID userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("User not found"));

        return memoShareRepository.findBySharedToOrderBySharedAtDesc(user).stream()
                .map(share -> convertToResponse(share.getMemo()))
                .collect(Collectors.toList());
    }

    @Transactional
    public MemoResponse createMemo(UUID userId, MemoRequest request, org.springframework.web.multipart.MultipartFile image) {
        User author = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("User not found"));

        String imageUrl = null;
        if (image != null && !image.isEmpty()) {
            String fileName = fileService.storeFile(image);
            imageUrl = "/uploads/" + fileName;
        }

        Memo memo = Memo.builder()
                .author(author)
                .groupId(request.getGroupId())
                .title(request.getTitle())
                .content(request.getContent())
                .imageUrl(imageUrl)
                .build();

        Memo savedMemo = memoRepository.save(memo);

        if (request.getTags() != null && !request.getTags().isEmpty()) {
            List<MemoTag> tags = request.getTags().stream()
                    .map(tagName -> MemoTag.builder()
                            .memo(savedMemo)
                            .name(tagName)
                            .build())
                    .collect(Collectors.toList());
            memoTagRepository.saveAll(tags);
        }

        return convertToResponse(savedMemo);
    }

    @Transactional(readOnly = true)
    public List<MemoResponse> getMyMemos(UUID userId) {
        User author = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("User not found"));

        return memoRepository.findAllByAuthorOrderByCreatedAtDesc(author).stream()
                .map(this::convertToResponse)
                .collect(Collectors.toList());
    }

    @Transactional
    public void deleteMemo(UUID userId, UUID memoId) {
        Memo memo = memoRepository.findById(memoId)
                .orElseThrow(() -> new RuntimeException("Memo not found"));

        if (!memo.getAuthor().getId().equals(userId)) {
            throw new RuntimeException("Unauthorized delete request");
        }

        memoRepository.delete(memo);
    }

    @Transactional(readOnly = true)
    public MemoResponse getMemo(UUID userId, UUID memoId) {
        Memo memo = memoRepository.findById(memoId)
                .orElseThrow(() -> new RuntimeException("Memo not found"));

        // 작성자거나 공유받은 유저여야 함
        User user = userRepository.findById(userId).orElseThrow();
        boolean isAuthor = memo.getAuthor().getId().equals(userId);
        boolean isShared = memoShareRepository.findByMemoAndSharedTo(memo, user).isPresent();
        
        if (!isAuthor && !isShared) {
            throw new RuntimeException("Unauthorized access request");
        }

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
        Memo memo = memoRepository.findById(memoId)
                .orElseThrow(() -> new RuntimeException("Memo not found: " + memoId));

        log.info("[AI Analyze] Request by User: {}, Memo Author: {}", userId, memo.getAuthor().getId());

        if (!memo.getAuthor().getId().equals(userId)) {
            throw new RuntimeException("Only authors can trigger analysis. Your ID: " + userId + ", Author ID: " + memo.getAuthor().getId());
        }

        Map<String, Object> analysisResult = geminiService.analyzeMemo(memo);
        
        MemoAiInsight insight = memoAiInsightRepository.findById(memoId)
                .map(existing -> {
                    existing.update(
                            (String) analysisResult.get("ocrText"),
                            (String) analysisResult.get("summary"),
                            (String) analysisResult.get("emotion"),
                            (List<String>) analysisResult.get("keywords")
                    );
                    return existing;
                })
                .orElseGet(() -> MemoAiInsight.builder()
                        .memo(memo)
                        .ocrText((String) analysisResult.get("ocrText"))
                        .summary((String) analysisResult.get("summary"))
                        .emotion((String) analysisResult.get("emotion"))
                        .keywords((List<String>) analysisResult.get("keywords"))
                        .build());

        MemoAiInsight saved = memoAiInsightRepository.save(insight);
        return convertToInsightResponse(saved);
    }

    @Transactional(readOnly = true)
    public MemoInsightResponse getMemoInsight(UUID userId, UUID memoId) {
        Memo memo = memoRepository.findById(memoId)
                .orElseThrow(() -> new RuntimeException("Memo not found: " + memoId));
        
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("User not found: " + userId));

        log.info("[AI Insight] Access by User: {}, Memo Author: {}", userId, memo.getAuthor().getId());

        boolean isAuthor = memo.getAuthor().getId().equals(userId);
        boolean isShared = memoShareRepository.findByMemoAndSharedTo(memo, user).isPresent();

        if (!isAuthor && !isShared) {
            throw new RuntimeException("Unauthorized access to insight. Your ID: " + userId + ", Author ID: " + memo.getAuthor().getId());
        }

        return memoAiInsightRepository.findById(memoId)
                .map(this::convertToInsightResponse)
                .orElse(null);
    }

    private MemoResponse convertToResponse(Memo memo) {
        List<String> tagNames = memoTagRepository.findAllByMemo(memo).stream()
                .map(MemoTag::getName)
                .collect(Collectors.toList());

        MemoAiInsight insight = memoAiInsightRepository.findById(memo.getId()).orElse(null);
        MemoInsightResponse insightResponse = insight != null ? convertToInsightResponse(insight) : null;

        return MemoResponse.builder()
                .id(memo.getId())
                .title(memo.getTitle())
                .content(memo.getContent())
                .imageUrl(memo.getImageUrl())
                .groupId(memo.getGroupId())
                .tags(tagNames)
                .insight(insightResponse)
                .createdAt(memo.getCreatedAt())
                .updatedAt(memo.getUpdatedAt())
                .build();
    }

    private MemoInsightResponse convertToInsightResponse(MemoAiInsight insight) {
        return MemoInsightResponse.builder()
                .ocrText(insight.getOcrText())
                .summary(insight.getSummary())
                .emotion(insight.getEmotion())
                .keywords(insight.getKeywords())
                .analyzedAt(insight.getAnalyzedAt())
                .build();
    }
}
