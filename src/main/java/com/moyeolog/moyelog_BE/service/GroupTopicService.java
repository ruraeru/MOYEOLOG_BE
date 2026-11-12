package com.moyeolog.moyelog_BE.service;

import com.moyeolog.moyelog_BE.dto.*;
import com.moyeolog.moyelog_BE.entity.*;
import com.moyeolog.moyelog_BE.exception.BadRequestException;
import com.moyeolog.moyelog_BE.exception.ResourceNotFoundException;
import com.moyeolog.moyelog_BE.exception.UnauthorizedAccessException;
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
public class GroupTopicService {

    private final GroupTopicRepository groupTopicRepository;
    private final GroupTopicCommentRepository groupTopicCommentRepository;
    private final GroupTopicInsightRepository groupTopicInsightRepository;
    private final GroupRepository groupRepository;
    private final GroupMemberRepository groupMemberRepository;
    private final UserRepository userRepository;
    private final GeminiService geminiService;

    private static final java.util.regex.Pattern DATA_URL_PREFIX =
            java.util.regex.Pattern.compile("^data:image/(png|jpeg|gif|webp);base64,");

    @Transactional(readOnly = true)
    public List<GroupTopicResponse> getTopicsByGroup(UUID userId, UUID groupId) {
        Group group = findGroupById(groupId);
        validateMember(group, userId);

        return groupTopicRepository.findAllByGroupOrderByCreatedAtDesc(group).stream()
                .map(this::convertToResponse)
                .collect(Collectors.toList());
    }

    @Transactional
    public GroupTopicResponse createTopic(UUID userId, UUID groupId, GroupTopicRequest request) {
        Group group = findGroupById(groupId);
        validateMember(group, userId);
        User author = findUserById(userId);
        validateContent(request.getTitle(), request.getContent());
        String imageUrl = validateImageUrl(request.getImageUrl());

        GroupTopic topic = GroupTopic.builder()
                .group(group)
                .author(author)
                .title(request.getTitle())
                .content(request.getContent())
                .imageUrl(imageUrl)
                .build();

        return convertToResponse(groupTopicRepository.save(topic));
    }

    @Transactional(readOnly = true)
    public GroupTopicDetailResponse getTopicDetail(UUID userId, UUID topicId) {
        GroupTopic topic = findTopicById(topicId);
        validateMember(topic.getGroup(), userId);

        List<GroupTopicCommentResponse> comments = groupTopicCommentRepository.findAllByTopicOrderByCreatedAtAsc(topic).stream()
                .map(this::convertToCommentResponse)
                .collect(Collectors.toList());

        GroupTopicInsight insight = groupTopicInsightRepository.findById(topicId).orElse(null);
        MemoInsightResponse insightResponse = insight != null ? convertToInsightResponse(insight) : null;

        return GroupTopicDetailResponse.builder()
                .topic(convertToResponse(topic))
                .comments(comments)
                .insight(insightResponse)
                .build();
    }

    @Transactional
    public GroupTopicResponse updateTopic(UUID userId, UUID topicId, GroupTopicRequest request) {
        GroupTopic topic = findTopicById(topicId);
        validateAuthor(topic, userId);

        validateContent(request.getTitle(), request.getContent());
        String imageUrl = validateImageUrl(request.getImageUrl());

        topic.update(request.getTitle(), request.getContent(), imageUrl);
        return convertToResponse(topic);
    }

    @Transactional
    public void deleteTopic(UUID userId, UUID topicId) {
        GroupTopic topic = findTopicById(topicId);
        validateAuthor(topic, userId);
        groupTopicRepository.delete(topic);
    }

    @Transactional
    public GroupTopicCommentResponse createComment(UUID userId, UUID topicId, GroupTopicCommentRequest request) {
        GroupTopic topic = findTopicById(topicId);
        validateMember(topic.getGroup(), userId);
        User author = findUserById(userId);
        validateCommentContent(request.getContent());

        GroupTopicComment comment = GroupTopicComment.builder()
                .topic(topic)
                .author(author)
                .content(request.getContent())
                .build();

        return convertToCommentResponse(groupTopicCommentRepository.save(comment));
    }

    @Transactional
    public GroupTopicCommentResponse updateComment(UUID userId, UUID commentId, GroupTopicCommentRequest request) {
        GroupTopicComment comment = findCommentById(commentId);
        validateCommentAuthor(comment, userId);

        validateCommentContent(request.getContent());
        comment.update(request.getContent());
        return convertToCommentResponse(comment);
    }

    @Transactional
    public void deleteComment(UUID userId, UUID commentId) {
        GroupTopicComment comment = findCommentById(commentId);
        validateCommentAuthor(comment, userId);
        groupTopicCommentRepository.delete(comment);
    }

    @Transactional
    public MemoInsightResponse analyzeTopic(UUID userId, UUID topicId) {
        GroupTopic topic = findTopicById(topicId);
        validateMember(topic.getGroup(), userId);

        log.info("[AI Analyze] Triggered for GroupTopic: {}", topicId);
        // Map GroupTopic to a temporary Memo object or refactor GeminiService to handle AiAnalyzable
        // For now, let's just use a map in GeminiService or similar.
        // Actually, refactoring GeminiService is cleaner. I'll do that next turn.
        // For this turn, I'll assume analyzeTopic logic is here or calling a refactored geminiService.
        
        Map<String, Object> result = geminiService.analyzeTopic(topic);
        String ocrText = textOf(result, "ocrText");
        String summary = textOf(result, "summary");
        // Gemini 가 keywords 를 null 로 돌려줘도 빈 목록으로 처리한다.
        List<String> keywords = GeminiService.toKeywords(result != null ? result.get("keywords") : null);

        GroupTopicInsight insight = groupTopicInsightRepository.findById(topicId)
                .map(existing -> {
                    existing.update(ocrText, summary, keywords);
                    return existing;
                })
                .orElseGet(() -> GroupTopicInsight.builder()
                        .topic(topic)
                        .ocrText(ocrText)
                        .summary(summary)
                        .keywords(keywords)
                        .build());

        return convertToInsightResponse(groupTopicInsightRepository.save(insight));
    }

    // ─── Internal Helpers ──────────────────────────────────────────

    private Group findGroupById(UUID groupId) {
        return groupRepository.findById(groupId)
                .orElseThrow(() -> new ResourceNotFoundException("그룹", groupId));
    }

    private User findUserById(UUID userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("사용자", userId));
    }

    private GroupTopic findTopicById(UUID topicId) {
        return groupTopicRepository.findById(topicId)
                .orElseThrow(() -> new ResourceNotFoundException("토픽", topicId));
    }

    private GroupTopicComment findCommentById(UUID commentId) {
        return groupTopicCommentRepository.findById(commentId)
                .orElseThrow(() -> new ResourceNotFoundException("댓글", commentId));
    }

    private void validateMember(Group group, UUID userId) {
        if (!groupMemberRepository.existsByGroup_IdAndUser_Id(group.getId(), userId)) {
            throw new UnauthorizedAccessException("해당 그룹의 멤버가 아닙니다.");
        }
    }

    private void validateContent(String title, String content) {
        if (title == null || title.isBlank()) {
            throw new BadRequestException("토픽 제목은 필수입니다.");
        }
        if (content == null || content.isBlank()) {
            throw new BadRequestException("토픽 내용은 필수입니다.");
        }
    }

    private void validateCommentContent(String content) {
        if (content == null || content.isBlank()) {
            throw new BadRequestException("댓글 내용은 필수입니다.");
        }
    }

    /**
     * 토픽 이미지는 다음 세 가지만 허용합니다 (그 외는 400).
     * - null (빈 문자열도 null 로 취급)
     * - data:image/(png|jpeg|gif|webp);base64,<Base64>
     * - /uploads/<평범한 파일 이름>
     */
    static String validateImageUrl(String imageUrl) {
        if (imageUrl == null || imageUrl.isBlank()) {
            return null;
        }
        if (FileService.isUploadUrl(imageUrl)) {
            return imageUrl;
        }
        java.util.regex.Matcher matcher = DATA_URL_PREFIX.matcher(imageUrl);
        if (matcher.lookingAt() && isBase64(imageUrl, matcher.end())) {
            return imageUrl;
        }
        throw new BadRequestException("허용되지 않는 이미지 형식입니다.");
    }

    private static boolean isBase64(String value, int from) {
        if (from >= value.length()) {
            return false;
        }
        for (int i = from; i < value.length(); i++) {
            char c = value.charAt(i);
            boolean ok = (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                    || c == '+' || c == '/' || c == '=';
            if (!ok) {
                return false;
            }
        }
        return true;
    }

    private static String textOf(Map<String, Object> result, String key) {
        Object value = result != null ? result.get(key) : null;
        return value != null ? value.toString() : "";
    }

    private void validateAuthor(GroupTopic topic, UUID userId) {
        if (!topic.getAuthor().getId().equals(userId)) {
            throw new UnauthorizedAccessException("작성자만 수정/삭제할 수 있습니다.");
        }
    }

    private void validateCommentAuthor(GroupTopicComment comment, UUID userId) {
        if (!comment.getAuthor().getId().equals(userId)) {
            throw new UnauthorizedAccessException("댓글 작성자만 수정/삭제할 수 있습니다.");
        }
    }

    private GroupTopicResponse convertToResponse(GroupTopic topic) {
        int commentCount = groupTopicCommentRepository.findAllByTopicOrderByCreatedAtAsc(topic).size();
        return GroupTopicResponse.builder()
                .id(topic.getId())
                .title(topic.getTitle())
                .content(topic.getContent())
                .imageUrl(topic.getImageUrl())
                .authorId(topic.getAuthor().getId())
                .authorNickname(topic.getAuthor().getNickname())
                .authorProfileImage(topic.getAuthor().getProfileImage())
                .groupId(topic.getGroup().getId())
                .commentCount(commentCount)
                .createdAt(topic.getCreatedAt())
                .updatedAt(topic.getUpdatedAt())
                .build();
    }

    private GroupTopicCommentResponse convertToCommentResponse(GroupTopicComment comment) {
        return GroupTopicCommentResponse.builder()
                .id(comment.getId())
                .content(comment.getContent())
                .authorId(comment.getAuthor().getId())
                .authorNickname(comment.getAuthor().getNickname())
                .authorProfileImage(comment.getAuthor().getProfileImage())
                .createdAt(comment.getCreatedAt())
                .updatedAt(comment.getUpdatedAt())
                .build();
    }

    private MemoInsightResponse convertToInsightResponse(GroupTopicInsight insight) {
        return MemoInsightResponse.builder()
                .ocrText(insight.getOcrText())
                .summary(insight.getSummary())
                .keywords(insight.getKeywords() != null ? insight.getKeywords() : List.of())
                .analyzedAt(insight.getAnalyzedAt())
                .build();
    }
}
