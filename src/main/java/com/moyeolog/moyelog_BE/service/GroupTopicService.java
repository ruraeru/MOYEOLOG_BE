package com.moyeolog.moyelog_BE.service;

import com.moyeolog.moyelog_BE.dto.*;
import com.moyeolog.moyelog_BE.entity.*;
import com.moyeolog.moyelog_BE.event.ChangeEvent;
import com.moyeolog.moyelog_BE.event.LiveTopics;
import com.moyeolog.moyelog_BE.event.NotificationEvent;
import com.moyeolog.moyelog_BE.event.NotificationType;
import com.moyeolog.moyelog_BE.event.UserDeletingEvent;
import com.moyeolog.moyelog_BE.exception.BadRequestException;
import com.moyeolog.moyelog_BE.exception.UnauthorizedAccessException;
import com.moyeolog.moyelog_BE.repository.*;
import com.moyeolog.moyelog_BE.validation.CodePointSizeValidator;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Comparator;
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
    private final AiAnalysisSupport aiAnalysisSupport;
    private final GroupImageService groupImageService;
    private final ApplicationEventPublisher eventPublisher;

    /** 업로드 파일 소유 확인용 (다른 기능의 리포지토리를 고치지 않고 JPQL 로 읽기만 한다) */
    @PersistenceContext
    private EntityManager entityManager;

    private static final java.util.regex.Pattern DATA_URL_PREFIX =
            java.util.regex.Pattern.compile("^data:image/(png|jpeg|gif|webp);base64,");

    private static final String TOPIC_NOT_FOUND = "토픽을 찾을 수 없어요. 삭제됐을 수 있어요.";
    private static final String COMMENT_NOT_FOUND = "댓글을 찾을 수 없어요. 삭제됐을 수 있어요.";

    @Transactional(readOnly = true)
    public List<GroupTopicResponse> getTopicsByGroup(UUID userId, UUID groupId) {
        Group group = findGroupById(groupId);
        validateMember(group, userId);

        // 고정(공지)한 것 먼저(고정 시각 최신순), 나머지는 최신순 그대로
        return groupTopicRepository.findAllByGroupOrderByCreatedAtDesc(group).stream()
                .sorted(Comparator.comparing(GroupTopic::getPinnedAt, Comparator.nullsLast(Comparator.reverseOrder())))
                .map(topic -> convertToResponse(topic, userId))
                .collect(Collectors.toList());
    }

    @Transactional
    public GroupTopicResponse createTopic(UUID userId, UUID groupId, GroupTopicRequest request) {
        Group group = findGroupById(groupId);
        validateMember(group, userId);
        User author = findUserById(userId);
        validateContent(request.getTitle(), request.getContent());
        String imageUrl = resolveImage(userId, validateImageUrl(request.getImageUrl()), null);

        GroupTopic topic = GroupTopic.builder()
                .group(group)
                .author(author)
                .title(request.getTitle().strip())
                .content(request.getContent())
                .imageUrl(imageUrl)
                .build();

        GroupTopicResponse response = convertToResponse(groupTopicRepository.save(topic), userId);
        publishChange(groupId);
        return response;
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
                .topic(convertToResponse(topic, userId))
                .comments(comments)
                .authorCustomId(topic.getAuthor().getCustomId())
                .insight(insightResponse)
                .build();
    }

    /** 수정은 작성자이면서 지금 모임 멤버일 때만 (나간·내보내진 사람은 403) */
    @Transactional
    public GroupTopicResponse updateTopic(UUID userId, UUID topicId, GroupTopicRequest request) {
        GroupTopic topic = findTopicById(topicId);
        validateAuthor(topic, userId);
        validateMember(topic.getGroup(), userId);

        validateContent(request.getTitle(), request.getContent());
        String previousImage = topic.getImageUrl();
        String imageUrl = resolveImage(userId, validateImageUrl(request.getImageUrl()), previousImage);

        topic.update(request.getTitle().strip(), request.getContent(), imageUrl);
        if (previousImage != null && !previousImage.equals(imageUrl)) {
            groupImageService.deleteAfterCommit(previousImage);
        }
        publishChange(topic.getGroup().getId());
        return convertToResponse(topic, userId);
    }

    /**
     * 고정·해제: 지금 모임 멤버 중 작성자 또는 방장. 고정은 모임 전체에 보인다(공지).
     * 이미 고정된 것을 다시 고정하면 처음 고정한 시각을 둔다. 수정 시각은 바꾸지 않는다. 알림은 보내지 않는다.
     */
    @Transactional
    public GroupTopicResponse pinTopic(UUID userId, UUID topicId, boolean pin) {
        GroupTopic topic = groupTopicRepository.findByIdForUpdate(topicId)
                .orElseThrow(() -> GroupErrors.notFound(TOPIC_NOT_FOUND));
        validateMember(topic.getGroup(), userId);
        if (!canPin(topic, userId)) {
            throw new UnauthorizedAccessException("방장이나 작성자만 고정할 수 있어요.");
        }
        UUID groupId = topic.getGroup().getId();
        if (pin != (topic.getPinnedAt() != null)) {
            groupTopicRepository.updatePinnedAt(topicId, pin ? LocalDateTime.now() : null);
            // updatePinnedAt 이 영속성 컨텍스트를 비웠으므로 다시 읽는다.
            topic = findTopicById(topicId);
        }
        publishChange(groupId);
        return convertToResponse(topic, userId);
    }

    /** 고정·삭제 권한: 작성자 또는 방장 (고정은 모임 멤버인지 따로 확인한다) */
    private static boolean canPin(GroupTopic topic, UUID userId) {
        return topic.getAuthor().getId().equals(userId)
                || topic.getGroup().getCreatedBy().getId().equals(userId);
    }

    /** 삭제는 작성자 또는 방장 (작성자는 모임을 나간 뒤에도 자기 글 정리는 허용). 동시 삭제는 두 번째가 404. */
    @Transactional
    public void deleteTopic(UUID userId, UUID topicId) {
        GroupTopic topic = groupTopicRepository.findByIdForUpdate(topicId)
                .orElseThrow(() -> GroupErrors.notFound(TOPIC_NOT_FOUND));
        if (!canPin(topic, userId)) {
            throw new UnauthorizedAccessException("방장이나 작성자만 지울 수 있어요.");
        }
        String imageUrl = topic.getImageUrl();
        UUID groupId = topic.getGroup().getId();
        groupTopicRepository.delete(topic);
        groupImageService.deleteAfterCommit(imageUrl);
        publishChange(groupId);
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
        GroupTopicComment saved = groupTopicCommentRepository.save(comment);
        publishChange(topic.getGroup().getId());

        UUID topicAuthorId = topic.getAuthor().getId();
        if (!topicAuthorId.equals(userId)) {
            eventPublisher.publishEvent(new NotificationEvent(topicAuthorId, userId, NotificationType.TOPIC_COMMENT,
                    author.getNickname() + "님이 '" + shortTitle(topic.getTitle()) + "' 토픽에 댓글을 남겼어요.",
                    "/groups/" + topic.getGroup().getId() + "?topic=" + topic.getId()));
        }
        return convertToCommentResponse(saved);
    }

    /** 수정은 작성자이면서 지금 모임 멤버일 때만 */
    @Transactional
    public GroupTopicCommentResponse updateComment(UUID userId, UUID commentId, GroupTopicCommentRequest request) {
        GroupTopicComment comment = findCommentById(commentId);
        validateCommentAuthor(comment, userId);
        validateMember(comment.getTopic().getGroup(), userId);

        validateCommentContent(request.getContent());
        comment.update(request.getContent());
        publishChange(comment.getTopic().getGroup().getId());
        return convertToCommentResponse(comment);
    }

    /** 삭제는 작성자 본인만. 동시 삭제는 두 번째가 404. */
    @Transactional
    public void deleteComment(UUID userId, UUID commentId) {
        GroupTopicComment comment = groupTopicCommentRepository.findByIdForUpdate(commentId)
                .orElseThrow(() -> GroupErrors.notFound(COMMENT_NOT_FOUND));
        validateCommentAuthor(comment, userId);
        comment.getTopic().getComments().remove(comment);
        groupTopicCommentRepository.delete(comment);
        publishChange(comment.getTopic().getGroup().getId());
    }

    /** 커밋 뒤 실시간 갱신: 그 모임 멤버의 모임 화면(토픽·댓글) */
    private void publishChange(UUID groupId) {
        eventPublisher.publishEvent(ChangeEvent.of(groupMemberRepository.findUserIdsByGroupId(groupId),
                LiveTopics.group(groupId)));
    }

    /** 회원 탈퇴 직전: 내가 쓴 토픽(다른 사람 댓글·분석 포함)과 내 댓글을 지운다. 모임 정리(GroupService)보다 먼저. */
    @EventListener
    @Order(10)
    @Transactional
    public void onUserDeleting(UserDeletingEvent event) {
        UUID userId = event.userId();
        for (GroupTopic topic : groupTopicRepository.findAllByAuthor_Id(userId)) {
            String imageUrl = topic.getImageUrl();
            groupTopicRepository.delete(topic);
            groupImageService.deleteAfterCommit(imageUrl);
        }
        groupTopicRepository.flush();
        groupTopicCommentRepository.deleteAllByAuthorId(userId);
    }

    /**
     * 토픽 AI 분석 (모임 멤버). 토픽 이미지("/uploads/..." 또는 data URL)도 모델에 보낸다.
     * LLM 을 기다리는 동안 트랜잭션을 잡지 않고, 같은 토픽을 분석 중이면 409.
     */
    public MemoInsightResponse analyzeTopic(UUID userId, UUID topicId) {
        return aiAnalysisSupport.run("topic:" + topicId,
                () -> {
                    GroupTopic topic = findTopicById(topicId);
                    validateMember(topic.getGroup(), userId);
                    log.info("[AI Analyze] Triggered for GroupTopic: {}", topicId);
                    return new AiAnalysisSupport.Source(topicId.toString(), topic.getContent(), topic.getImageUrl());
                },
                analyzed -> {
                    // 분석하는 동안 토픽이 지워졌으면 404
                    GroupTopic topic = findTopicById(topicId);
                    String sourceHash = analyzed.sourceHash();
                    GroupTopicInsight insight = groupTopicInsightRepository.findById(topicId)
                            .map(existing -> {
                                existing.update(analyzed.ocrText(), analyzed.summary(), analyzed.keywords(), sourceHash);
                                return existing;
                            })
                            .orElseGet(() -> GroupTopicInsight.builder()
                                    .topic(topic)
                                    .ocrText(analyzed.ocrText())
                                    .summary(analyzed.summary())
                                    .keywords(analyzed.keywords())
                                    .sourceHash(sourceHash)
                                    .build());
                    return convertToInsightResponse(groupTopicInsightRepository.save(insight));
                });
    }

    // ─── Internal Helpers ──────────────────────────────────────────

    private Group findGroupById(UUID groupId) {
        return groupRepository.findById(groupId)
                .orElseThrow(() -> GroupErrors.notFound(GroupErrors.GROUP_NOT_FOUND));
    }

    private User findUserById(UUID userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> GroupErrors.notFound("사용자를 찾을 수 없어요."));
    }

    private GroupTopic findTopicById(UUID topicId) {
        return groupTopicRepository.findById(topicId)
                .orElseThrow(() -> GroupErrors.notFound(TOPIC_NOT_FOUND));
    }

    private GroupTopicComment findCommentById(UUID commentId) {
        return groupTopicCommentRepository.findById(commentId)
                .orElseThrow(() -> GroupErrors.notFound(COMMENT_NOT_FOUND));
    }

    private void validateMember(Group group, UUID userId) {
        if (!groupMemberRepository.existsByGroup_IdAndUser_Id(group.getId(), userId)) {
            throw new UnauthorizedAccessException(GroupErrors.NOT_MEMBER);
        }
    }

    private void validateContent(String title, String content) {
        if (GroupErrors.isVisuallyBlank(title)) {
            throw new BadRequestException("토픽 제목을 입력해 주세요.");
        }
        if (GroupErrors.isVisuallyBlank(content)) {
            throw new BadRequestException("토픽 내용을 입력해 주세요.");
        }
    }

    /** 공백·보이지 않는 문자(U+200B 등)만 있는 댓글은 400 */
    private void validateCommentContent(String content) {
        if (GroupErrors.isVisuallyBlank(content)) {
            throw new BadRequestException("댓글 내용을 입력해 주세요.");
        }
    }

    /**
     * 토픽 이미지 형식 검사. 다음 세 가지만 허용합니다 (그 외는 400).
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
        throw new BadRequestException("허용되지 않는 이미지 형식이에요.");
    }

    /**
     * 형식 검사를 통과한 이미지 값을 저장할 경로로 바꾼다.
     * - data URL: 매직 바이트 검사 후 /uploads 파일로 저장 (DB 에 base64 를 두지 않음)
     * - 지금 토픽의 이미지와 같으면 그대로
     * - 다른 /uploads 경로: 요청한 사람이 올린 파일이고 실제로 있을 때만, 새 파일로 복사
     *   (원본 메모가 지워져도 토픽 이미지가 깨지지 않게)
     */
    private String resolveImage(UUID userId, String imageUrl, String currentImage) {
        if (imageUrl == null) {
            return null;
        }
        if (imageUrl.equals(currentImage)) {
            return currentImage;
        }
        if (GroupImageService.isDataUrl(imageUrl)) {
            return groupImageService.storeDataUrl(imageUrl);
        }
        if (!isOwnUpload(userId, imageUrl)) {
            throw new BadRequestException("직접 올린 이미지만 쓸 수 있어요.");
        }
        return groupImageService.copyUpload(imageUrl)
                .orElseThrow(() -> new BadRequestException("이미지를 찾을 수 없어요. 다시 올려 주세요."));
    }

    /** 이 사용자의 프로필·메모·토픽·방장 모임 이미지로 쓰이고 있는 업로드 파일인지 */
    private boolean isOwnUpload(UUID userId, String imageUrl) {
        if (groupTopicRepository.existsByAuthor_IdAndImageUrl(userId, imageUrl)) {
            return true;
        }
        if (entityManager == null) {
            return false;
        }
        String[] queries = {
                "select count(u) from User u where u.id = :userId and u.profileImage = :url",
                "select count(m) from Memo m where m.author.id = :userId and m.imageUrl = :url",
                "select count(g) from Group g where g.createdBy.id = :userId and (g.profileImage = :url or g.backgroundImage = :url)"
        };
        for (String jpql : queries) {
            Long count = entityManager.createQuery(jpql, Long.class)
                    .setParameter("userId", userId)
                    .setParameter("url", imageUrl)
                    .getSingleResult();
            if (count != null && count > 0) {
                return true;
            }
        }
        return false;
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

    private void validateAuthor(GroupTopic topic, UUID userId) {
        if (!topic.getAuthor().getId().equals(userId)) {
            throw new UnauthorizedAccessException("작성자만 고치거나 지울 수 있어요.");
        }
    }

    private void validateCommentAuthor(GroupTopicComment comment, UUID userId) {
        if (!comment.getAuthor().getId().equals(userId)) {
            throw new UnauthorizedAccessException("댓글 작성자만 고치거나 지울 수 있어요.");
        }
    }

    /** 목록·상세 응답. 예전 data URL 이미지는 변환 전까지 내보내지 않는다(응답 크기). canPin·canDelete 는 viewerId 기준. */
    private GroupTopicResponse convertToResponse(GroupTopic topic, UUID viewerId) {
        long commentCount = groupTopicCommentRepository.countByTopic(topic);
        String imageUrl = topic.getImageUrl();
        return GroupTopicResponse.builder()
                .id(topic.getId())
                .title(topic.getTitle())
                .content(topic.getContent())
                .imageUrl(FileService.isUploadUrl(imageUrl) ? imageUrl : null)
                .authorId(topic.getAuthor().getId())
                .authorNickname(topic.getAuthor().getNickname())
                .authorCustomId(topic.getAuthor().getCustomId())
                .authorProfileImage(topic.getAuthor().getProfileImage())
                .groupId(topic.getGroup().getId())
                .commentCount((int) commentCount)
                .createdAt(topic.getCreatedAt())
                .updatedAt(topic.getUpdatedAt())
                .pinnedAt(topic.getPinnedAt())
                .canPin(canPin(topic, viewerId))
                .canDelete(canPin(topic, viewerId))
                .build();
    }

    private GroupTopicCommentResponse convertToCommentResponse(GroupTopicComment comment) {
        return GroupTopicCommentResponse.builder()
                .id(comment.getId())
                .content(comment.getContent())
                .authorId(comment.getAuthor().getId())
                .authorNickname(comment.getAuthor().getNickname())
                .authorCustomId(comment.getAuthor().getCustomId())
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
                .stale(insight.getTopic() != null && AiAnalysisSupport.isStale(insight.getSourceHash(), insight.getAnalyzedAt(),
                        insight.getTopic().getContent(), insight.getTopic().getImageUrl(), insight.getTopic().getUpdatedAt()))
                .build();
    }

    /** 알림 문구용 제목: 30자가 넘으면 자르고 말줄임표를 붙인다 */
    static String shortTitle(String title) {
        String t = title == null ? "" : title.strip();
        return CodePointSizeValidator.length(t) > 30 ? CodePointSizeValidator.truncate(t, 30).strip() + "…" : t;
    }
}
