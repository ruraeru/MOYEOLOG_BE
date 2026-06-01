package com.moyeolog.moyelog_BE.service;

import com.moyeolog.moyelog_BE.dto.*;
import com.moyeolog.moyelog_BE.entity.*;
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

        GroupTopic topic = GroupTopic.builder()
                .group(group)
                .author(author)
                .title(request.getTitle())
                .content(request.getContent())
                .imageUrl(request.getImageUrl())
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

        topic.update(request.getTitle(), request.getContent(), request.getImageUrl());
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
        
        GroupTopicInsight insight = groupTopicInsightRepository.findById(topicId)
                .map(existing -> {
                    existing.update((String) result.get("ocrText"), (String) result.get("summary"));
                    return existing;
                })
                .orElseGet(() -> GroupTopicInsight.builder()
                        .topic(topic)
                        .ocrText((String) result.get("ocrText"))
                        .summary((String) result.get("summary"))
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
        User user = findUserById(userId);
        if (groupMemberRepository.findByGroupAndUser(group, user).isEmpty()) {
            throw new UnauthorizedAccessException("해당 그룹의 멤버가 아닙니다.");
        }
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
                .analyzedAt(insight.getAnalyzedAt())
                .build();
    }
}
