package com.moyeolog.moyelog_BE.service;

import com.moyeolog.moyelog_BE.dto.GroupTopicRequest;
import com.moyeolog.moyelog_BE.dto.GroupTopicResponse;
import com.moyeolog.moyelog_BE.entity.Group;
import com.moyeolog.moyelog_BE.entity.GroupMember;
import com.moyeolog.moyelog_BE.entity.GroupTopic;
import com.moyeolog.moyelog_BE.entity.User;
import com.moyeolog.moyelog_BE.exception.UnauthorizedAccessException;
import com.moyeolog.moyelog_BE.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class GroupTopicServiceTest {

    @Mock private GroupTopicRepository groupTopicRepository;
    @Mock private GroupTopicCommentRepository groupTopicCommentRepository;
    @Mock private GroupTopicInsightRepository groupTopicInsightRepository;
    @Mock private GroupRepository groupRepository;
    @Mock private GroupMemberRepository groupMemberRepository;
    @Mock private UserRepository userRepository;
    @Mock private GeminiService geminiService;
    @Mock private GroupImageService groupImageService;
    @Mock private org.springframework.context.ApplicationEventPublisher eventPublisher;

    @InjectMocks private GroupTopicService groupTopicService;

    private User user;
    private Group group;
    private UUID userId;
    private UUID groupId;

    @BeforeEach
    void setUp() {
        userId = UUID.randomUUID();
        groupId = UUID.randomUUID();
        user = User.builder().id(userId).nickname("testUser").build();
        // 응답의 canPin 이 방장을 읽으므로 방장(작성자가 아닌 사람)을 둔다.
        User owner = User.builder().id(UUID.randomUUID()).nickname("owner").build();
        group = Group.builder().id(groupId).name("Test Group").createdBy(owner).build();
    }

    @Test
    @DisplayName("고정: 작성자도 방장도 아닌 멤버는 403, 고정하지 않는다")
    void pinTopic_NotAuthorNorOwner_Forbidden() {
        User author = User.builder().id(UUID.randomUUID()).nickname("author").build();
        GroupTopic topic = GroupTopic.builder().id(UUID.randomUUID()).title("t").content("c").author(author).group(group).build();
        given(groupTopicRepository.findByIdForUpdate(topic.getId())).willReturn(Optional.of(topic));
        given(groupMemberRepository.existsByGroup_IdAndUser_Id(groupId, userId)).willReturn(true);

        assertThatThrownBy(() -> groupTopicService.pinTopic(userId, topic.getId(), true))
                .isInstanceOf(UnauthorizedAccessException.class);
        verify(groupTopicRepository, org.mockito.Mockito.never()).updatePinnedAt(any(), any());
    }

    @Test
    @DisplayName("삭제: 방장은 남의 토픽을 지울 수 있고, 작성자도 방장도 아닌 멤버는 403")
    void deleteTopic_OwnerAllowed_OtherForbidden() {
        User author = User.builder().id(UUID.randomUUID()).nickname("author").build();
        GroupTopic topic = GroupTopic.builder().id(UUID.randomUUID()).title("t").content("c").author(author).group(group).build();
        given(groupTopicRepository.findByIdForUpdate(topic.getId())).willReturn(Optional.of(topic));

        // userId 는 작성자도 방장도 아니다
        assertThatThrownBy(() -> groupTopicService.deleteTopic(userId, topic.getId()))
                .isInstanceOf(UnauthorizedAccessException.class);
        verify(groupTopicRepository, org.mockito.Mockito.never()).delete(any(GroupTopic.class));

        groupTopicService.deleteTopic(group.getCreatedBy().getId(), topic.getId());
        verify(groupTopicRepository).delete(topic);
    }

    @Test
    @DisplayName("canDelete: 작성자·방장 true, 그 밖의 멤버 false")
    void topicResponse_CanDelete() {
        User author = User.builder().id(UUID.randomUUID()).nickname("author").build();
        GroupTopic topic = GroupTopic.builder().id(UUID.randomUUID()).title("t").content("c").author(author).group(group).build();
        UUID ownerId = group.getCreatedBy().getId();
        given(groupRepository.findById(groupId)).willReturn(Optional.of(group));
        given(groupMemberRepository.existsByGroup_IdAndUser_Id(org.mockito.ArgumentMatchers.eq(groupId), any())).willReturn(true);
        given(groupTopicRepository.findAllByGroupOrderByCreatedAtDesc(group)).willReturn(java.util.List.of(topic));

        assertThat(groupTopicService.getTopicsByGroup(userId, groupId).get(0).isCanDelete()).isFalse();
        assertThat(groupTopicService.getTopicsByGroup(ownerId, groupId).get(0).isCanDelete()).isTrue();
        assertThat(groupTopicService.getTopicsByGroup(author.getId(), groupId).get(0).isCanDelete()).isTrue();
    }

    @Test
    @DisplayName("토픽 생성 성공")
    void createTopic_Success() {
        // given
        GroupTopicRequest request = GroupTopicRequest.builder()
                .title("New Topic")
                .content("Content")
                .build();
        
        GroupTopic topic = GroupTopic.builder()
                .id(UUID.randomUUID())
                .title(request.getTitle())
                .author(user)
                .group(group)
                .build();

        given(groupRepository.findById(groupId)).willReturn(Optional.of(group));
        given(userRepository.findById(userId)).willReturn(Optional.of(user));
        given(groupMemberRepository.existsByGroup_IdAndUser_Id(groupId, userId)).willReturn(true);
        given(groupTopicRepository.save(any(GroupTopic.class))).willReturn(topic);

        // when
        GroupTopicResponse response = groupTopicService.createTopic(userId, groupId, request);

        // then
        assertThat(response.getTitle()).isEqualTo(request.getTitle());
        verify(groupTopicRepository).save(any(GroupTopic.class));
    }

    @Test
    @DisplayName("그룹 멤버가 아닌 경우 토픽 생성 실패")
    void createTopic_NotMember_Fail() {
        // given
        GroupTopicRequest request = GroupTopicRequest.builder().title("Title").build();
        given(groupRepository.findById(groupId)).willReturn(Optional.of(group));
        given(groupMemberRepository.existsByGroup_IdAndUser_Id(groupId, userId)).willReturn(false);

        // when & then
        assertThatThrownBy(() -> groupTopicService.createTopic(userId, groupId, request))
                .isInstanceOf(UnauthorizedAccessException.class);
    }

    @Test
    @DisplayName("토픽 이미지 URL 은 null, data URL, /uploads/<파일명> 만 허용")
    void validateImageUrl() {
        assertThat(GroupTopicService.validateImageUrl(null)).isNull();
        assertThat(GroupTopicService.validateImageUrl("  ")).isNull();
        assertThat(GroupTopicService.validateImageUrl("/uploads/abc-123.png")).isEqualTo("/uploads/abc-123.png");
        assertThat(GroupTopicService.validateImageUrl("data:image/png;base64,iVBORw0KGgo="))
                .isEqualTo("data:image/png;base64,iVBORw0KGgo=");

        for (String bad : new String[]{
                "/uploads/../../etc/passwd",
                "/uploads/a/b.png",
                "/uploads/",
                "/uploads/.hidden",
                "/etc/passwd",
                "http://example.com/a.png",
                "data:image/svg+xml;base64,AAAA",
                "data:text/html;base64,AAAA",
                "data:image/png;base64,",
                "data:image/png;base64,AAAA<script>",
                "javascript:alert(1)"}) {
            assertThatThrownBy(() -> GroupTopicService.validateImageUrl(bad))
                    .as(bad)
                    .isInstanceOf(com.moyeolog.moyelog_BE.exception.BadRequestException.class);
        }
    }

    @Test
    @DisplayName("빈 내용의 토픽은 생성할 수 없다")
    void createTopic_BlankContent_Fail() {
        GroupTopicRequest request = GroupTopicRequest.builder().title("Title").content("   ").build();
        given(groupRepository.findById(groupId)).willReturn(Optional.of(group));
        given(userRepository.findById(userId)).willReturn(Optional.of(user));
        given(groupMemberRepository.existsByGroup_IdAndUser_Id(groupId, userId)).willReturn(true);

        assertThatThrownBy(() -> groupTopicService.createTopic(userId, groupId, request))
                .isInstanceOf(com.moyeolog.moyelog_BE.exception.BadRequestException.class);
    }

    // ─── 2차 QA: 멤버십·댓글·이미지·동시 삭제 ─────────────────────

    private GroupTopic ownTopic() {
        return GroupTopic.builder().id(UUID.randomUUID()).title("t").content("c").author(user).group(group).build();
    }

    private com.moyeolog.moyelog_BE.entity.GroupTopicComment ownComment(GroupTopic topic) {
        return com.moyeolog.moyelog_BE.entity.GroupTopicComment.builder()
                .id(UUID.randomUUID()).topic(topic).author(user).content("댓글").build();
    }

    @Test
    @DisplayName("나간·내보내진 작성자는 자기 토픽·댓글을 수정할 수 없다(403)")
    void update_NotMember_Forbidden() {
        GroupTopic topic = ownTopic();
        var comment = ownComment(topic);
        given(groupTopicRepository.findById(topic.getId())).willReturn(Optional.of(topic));
        given(groupTopicCommentRepository.findById(comment.getId())).willReturn(Optional.of(comment));
        given(groupMemberRepository.existsByGroup_IdAndUser_Id(groupId, userId)).willReturn(false);

        assertThatThrownBy(() -> groupTopicService.updateTopic(userId, topic.getId(),
                GroupTopicRequest.builder().title("강퇴 후 수정").content("c").build()))
                .isInstanceOf(UnauthorizedAccessException.class);
        assertThatThrownBy(() -> groupTopicService.updateComment(userId, comment.getId(),
                new com.moyeolog.moyelog_BE.dto.GroupTopicCommentRequest("수정")))
                .isInstanceOf(UnauthorizedAccessException.class);
        assertThat(topic.getTitle()).isEqualTo("t");
        assertThat(comment.getContent()).isEqualTo("댓글");
    }

    @Test
    @DisplayName("자기 토픽·댓글 삭제는 모임을 나간 뒤에도 가능, 이미 지워졌으면(동시 삭제) 404")
    void delete_OwnPost_AndConcurrent404() {
        GroupTopic topic = ownTopic();
        var comment = ownComment(topic);
        given(groupTopicRepository.findByIdForUpdate(topic.getId())).willReturn(Optional.of(topic), Optional.empty());
        given(groupTopicCommentRepository.findByIdForUpdate(comment.getId())).willReturn(Optional.of(comment), Optional.empty());

        groupTopicService.deleteComment(userId, comment.getId());
        groupTopicService.deleteTopic(userId, topic.getId());
        verify(groupTopicCommentRepository).delete(comment);
        verify(groupTopicRepository).delete(topic);

        assertThatThrownBy(() -> groupTopicService.deleteTopic(userId, topic.getId()))
                .isInstanceOfSatisfying(com.moyeolog.moyelog_BE.exception.MoyeologException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(org.springframework.http.HttpStatus.NOT_FOUND));
        assertThatThrownBy(() -> groupTopicService.deleteComment(userId, comment.getId()))
                .isInstanceOfSatisfying(com.moyeolog.moyelog_BE.exception.MoyeologException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(org.springframework.http.HttpStatus.NOT_FOUND));
    }

    @Test
    @DisplayName("보이지 않는 문자만 있는 댓글은 400")
    void createComment_Invisible_Fail() {
        GroupTopic topic = ownTopic();
        given(groupTopicRepository.findById(topic.getId())).willReturn(Optional.of(topic));
        given(groupMemberRepository.existsByGroup_IdAndUser_Id(groupId, userId)).willReturn(true);
        given(userRepository.findById(userId)).willReturn(Optional.of(user));

        for (String invisible : new String[]{"\u200b", "\u200b \ufeff", "\u2060", "\u3164"}) {
            assertThatThrownBy(() -> groupTopicService.createComment(userId, topic.getId(),
                    new com.moyeolog.moyelog_BE.dto.GroupTopicCommentRequest(invisible)))
                    .isInstanceOf(com.moyeolog.moyelog_BE.exception.BadRequestException.class);
        }
    }

    @Test
    @DisplayName("삭제: 모임 밖 사람(작성자·방장 아님)은 '멤버가 아니에요', 멤버는 '방장이나 작성자만'")
    void deleteTopic_NonMemberMessage() {
        User author = User.builder().id(UUID.randomUUID()).nickname("author").build();
        GroupTopic topic = GroupTopic.builder().id(UUID.randomUUID()).title("t").content("c").author(author).group(group).build();
        given(groupTopicRepository.findByIdForUpdate(topic.getId())).willReturn(Optional.of(topic));

        assertThatThrownBy(() -> groupTopicService.deleteTopic(userId, topic.getId()))
                .isInstanceOf(UnauthorizedAccessException.class)
                .hasMessage(GroupErrors.NOT_MEMBER);

        given(groupMemberRepository.existsByGroup_IdAndUser_Id(groupId, userId)).willReturn(true);
        assertThatThrownBy(() -> groupTopicService.deleteTopic(userId, topic.getId()))
                .isInstanceOf(UnauthorizedAccessException.class)
                .hasMessage("방장이나 작성자만 지울 수 있어요.");
        verify(groupTopicRepository, org.mockito.Mockito.never()).delete(any(GroupTopic.class));
    }

    @Test
    @DisplayName("댓글 멘션: 멘션한 모임 멤버에게 TOPIC_COMMENT 알림, 멤버 아닌 사람·나 자신에게는 없음")
    void createComment_MentionNotification() {
        GroupTopic mine = ownTopic();
        UUID memberId = UUID.randomUUID();
        UUID outsiderId = UUID.randomUUID();
        given(groupTopicRepository.findById(mine.getId())).willReturn(Optional.of(mine));
        given(groupMemberRepository.existsByGroup_IdAndUser_Id(groupId, userId)).willReturn(true);
        given(groupMemberRepository.existsByGroup_IdAndUser_Id(groupId, memberId)).willReturn(true);
        given(groupMemberRepository.existsByGroup_IdAndUser_Id(groupId, outsiderId)).willReturn(false);
        given(userRepository.findById(userId)).willReturn(Optional.of(user));
        given(groupTopicCommentRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

        String content = "[@멤버](/profile/" + memberId + ") [@밖](/profile/" + outsiderId + ") [@나](/profile/" + userId
                + ") 다시 [@멤버](/profile/" + memberId + ")";
        groupTopicService.createComment(userId, mine.getId(), new com.moyeolog.moyelog_BE.dto.GroupTopicCommentRequest(content));

        var captor = org.mockito.ArgumentCaptor.forClass(com.moyeolog.moyelog_BE.event.NotificationEvent.class);
        verify(eventPublisher, org.mockito.Mockito.times(1)).publishEvent(captor.capture());
        assertThat(captor.getValue().recipientId()).isEqualTo(memberId);
        assertThat(captor.getValue().type()).isEqualTo(com.moyeolog.moyelog_BE.event.NotificationType.TOPIC_COMMENT);
        assertThat(captor.getValue().message()).contains("언급했어요");
    }

    @Test
    @DisplayName("다른 사람 토픽에 댓글을 달면 토픽 작성자에게 TOPIC_COMMENT 알림, 내 토픽이면 알림 없음")
    void createComment_Notification() {
        User topicAuthor = User.builder().id(UUID.randomUUID()).nickname("작성자").build();
        GroupTopic topic = GroupTopic.builder().id(UUID.randomUUID()).title("주제").content("c").author(topicAuthor).group(group).build();
        given(groupTopicRepository.findById(topic.getId())).willReturn(Optional.of(topic));
        given(groupMemberRepository.existsByGroup_IdAndUser_Id(groupId, userId)).willReturn(true);
        given(userRepository.findById(userId)).willReturn(Optional.of(user));
        given(groupTopicCommentRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

        groupTopicService.createComment(userId, topic.getId(), new com.moyeolog.moyelog_BE.dto.GroupTopicCommentRequest("좋아요"));

        var captor = org.mockito.ArgumentCaptor.forClass(com.moyeolog.moyelog_BE.event.NotificationEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().type()).isEqualTo(com.moyeolog.moyelog_BE.event.NotificationType.TOPIC_COMMENT);
        assertThat(captor.getValue().recipientId()).isEqualTo(topicAuthor.getId());
        assertThat(captor.getValue().link()).isEqualTo("/groups/" + groupId + "?topic=" + topic.getId());

        GroupTopic mine = ownTopic();
        given(groupTopicRepository.findById(mine.getId())).willReturn(Optional.of(mine));
        groupTopicService.createComment(userId, mine.getId(), new com.moyeolog.moyelog_BE.dto.GroupTopicCommentRequest("셀프"));
        verify(eventPublisher, org.mockito.Mockito.times(1)).publishEvent(any(com.moyeolog.moyelog_BE.event.NotificationEvent.class));
    }

    @Test
    @DisplayName("댓글 수정 응답의 updatedAt 은 수정 시각, 작성자 customId 포함")
    void updateComment_UpdatedAt() {
        GroupTopic topic = ownTopic();
        var comment = com.moyeolog.moyelog_BE.entity.GroupTopicComment.builder()
                .id(UUID.randomUUID()).topic(topic).author(user).content("old")
                .createdAt(java.time.LocalDateTime.now().minusMinutes(5))
                .updatedAt(java.time.LocalDateTime.now().minusMinutes(5)).build();
        given(groupTopicCommentRepository.findById(comment.getId())).willReturn(Optional.of(comment));
        given(groupMemberRepository.existsByGroup_IdAndUser_Id(groupId, userId)).willReturn(true);

        var response = groupTopicService.updateComment(userId, comment.getId(),
                new com.moyeolog.moyelog_BE.dto.GroupTopicCommentRequest("new"));
        assertThat(response.getUpdatedAt()).isAfter(response.getCreatedAt());
        assertThat(response.getContent()).isEqualTo("new");
    }

    @Test
    @DisplayName("토픽 이미지: data URL 은 파일로 저장해 /uploads 경로, 남의(또는 없는) /uploads 경로는 400")
    void createTopic_Image() {
        given(groupRepository.findById(groupId)).willReturn(Optional.of(group));
        given(userRepository.findById(userId)).willReturn(Optional.of(user));
        given(groupMemberRepository.existsByGroup_IdAndUser_Id(groupId, userId)).willReturn(true);
        given(groupTopicRepository.save(any(GroupTopic.class))).willAnswer(inv -> inv.getArgument(0));
        String dataUrl = "data:image/png;base64,iVBORw0KGgo=";
        given(groupImageService.storeDataUrl(dataUrl)).willReturn("/uploads/new.png");

        GroupTopicResponse response = groupTopicService.createTopic(userId, groupId,
                GroupTopicRequest.builder().title("t").content("c").imageUrl(dataUrl).build());
        assertThat(response.getImageUrl()).isEqualTo("/uploads/new.png");

        assertThatThrownBy(() -> groupTopicService.createTopic(userId, groupId,
                GroupTopicRequest.builder().title("t").content("c").imageUrl("/uploads/someone-else.jpg").build()))
                .isInstanceOf(com.moyeolog.moyelog_BE.exception.BadRequestException.class);
        verify(groupImageService, org.mockito.Mockito.never()).copyUpload(any());
    }

    @Test
    @DisplayName("토픽 이미지: 내가 쓴 토픽에 있던 /uploads 이미지는 새 파일로 복사해서 쓴다")
    void createTopic_OwnUpload_Copied() {
        given(groupRepository.findById(groupId)).willReturn(Optional.of(group));
        given(userRepository.findById(userId)).willReturn(Optional.of(user));
        given(groupMemberRepository.existsByGroup_IdAndUser_Id(groupId, userId)).willReturn(true);
        given(groupTopicRepository.save(any(GroupTopic.class))).willAnswer(inv -> inv.getArgument(0));
        given(groupTopicRepository.existsByAuthor_IdAndImageUrl(userId, "/uploads/mine.png")).willReturn(true);
        given(groupImageService.copyUpload("/uploads/mine.png")).willReturn(Optional.of("/uploads/copy.png"));

        GroupTopicResponse response = groupTopicService.createTopic(userId, groupId,
                GroupTopicRequest.builder().title("t").content("c").imageUrl("/uploads/mine.png").build());
        assertThat(response.getImageUrl()).isEqualTo("/uploads/copy.png");
    }

    @Test
    @DisplayName("목록 응답에는 예전 base64 이미지를 담지 않는다")
    void list_HidesDataUrl() {
        GroupTopic legacy = GroupTopic.builder().id(UUID.randomUUID()).title("t").content("c").author(user).group(group)
                .imageUrl("data:image/png;base64,iVBORw0KGgo=").build();
        given(groupRepository.findById(groupId)).willReturn(Optional.of(group));
        given(groupMemberRepository.existsByGroup_IdAndUser_Id(groupId, userId)).willReturn(true);
        given(groupTopicRepository.findAllByGroupOrderByCreatedAtDesc(group)).willReturn(java.util.List.of(legacy));

        assertThat(groupTopicService.getTopicsByGroup(userId, groupId).get(0).getImageUrl()).isNull();
    }
}
