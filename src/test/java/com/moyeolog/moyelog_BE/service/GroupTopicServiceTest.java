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
        group = Group.builder().id(groupId).name("Test Group").build();
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
}
