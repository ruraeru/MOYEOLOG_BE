package com.moyeolog.moyelog_BE.service;

import com.moyeolog.moyelog_BE.dto.GroupRequest;
import com.moyeolog.moyelog_BE.dto.GroupResponse;
import com.moyeolog.moyelog_BE.entity.Group;
import com.moyeolog.moyelog_BE.entity.GroupMember;
import com.moyeolog.moyelog_BE.entity.User;
import com.moyeolog.moyelog_BE.exception.UnauthorizedAccessException;
import com.moyeolog.moyelog_BE.repository.GroupInvitationRepository;
import com.moyeolog.moyelog_BE.repository.GroupMemberRepository;
import com.moyeolog.moyelog_BE.repository.GroupRepository;
import com.moyeolog.moyelog_BE.repository.UserRepository;
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
class GroupServiceTest {

    @Mock private GroupRepository groupRepository;
    @Mock private GroupMemberRepository groupMemberRepository;
    @Mock private UserRepository userRepository;
    @Mock private GroupInvitationRepository groupInvitationRepository;
    @Mock private FileService fileService;

    @InjectMocks private GroupService groupService;

    private User user;
    private UUID userId;

    @BeforeEach
    void setUp() {
        userId = UUID.randomUUID();
        user = User.builder().id(userId).nickname("testUser").build();
    }

    @Test
    @DisplayName("그룹 생성 성공")
    void createGroup_Success() {
        // given
        GroupRequest request = GroupRequest.builder()
                .name("Test Group")
                .description("Desc")
                .build();
        Group group = Group.builder()
                .id(UUID.randomUUID())
                .name(request.getName())
                .createdBy(user)
                .build();

        given(userRepository.findById(userId)).willReturn(Optional.of(user));
        given(groupRepository.save(any(Group.class))).willReturn(group);

        // when
        GroupResponse response = groupService.createGroup(userId, request);

        // then
        assertThat(response.getName()).isEqualTo(request.getName());
        verify(groupRepository).save(any(Group.class));
        verify(groupMemberRepository).save(any(GroupMember.class));
    }

    @Test
    @DisplayName("그룹 상세 조회 실패 - 권한 없음")
    void getGroup_Unauthorized() {
        // given
        UUID groupId = UUID.randomUUID();
        Group group = Group.builder().id(groupId).build();
        given(groupRepository.findById(groupId)).willReturn(Optional.of(group));
        given(userRepository.findById(userId)).willReturn(Optional.of(user));
        // 멤버가 아닌 상황 (empty stream)
        given(groupMemberRepository.findByGroup(group)).willReturn(java.util.List.of());

        // when & then
        assertThatThrownBy(() -> groupService.getGroup(userId, groupId))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Unauthorized");
    }

    @Test
    @DisplayName("멤버 강퇴 성공")
    void kickMember_Success() {
        // given
        UUID groupId = UUID.randomUUID();
        Group group = Group.builder().id(groupId).createdBy(user).build();
        
        UUID targetUserId = UUID.randomUUID();
        User targetUser = User.builder().id(targetUserId).nickname("target").build();
        GroupMember targetMember = GroupMember.builder().id(UUID.randomUUID()).group(group).user(targetUser).build();

        given(groupRepository.findById(groupId)).willReturn(Optional.of(group));
        given(userRepository.findById(targetUserId)).willReturn(Optional.of(targetUser));
        given(groupMemberRepository.findByGroupAndUser(group, targetUser)).willReturn(Optional.of(targetMember));

        // when
        groupService.kickMember(userId, groupId, targetUserId);

        // then
        verify(groupMemberRepository).delete(targetMember);
    }

    @Test
    @DisplayName("멤버 강퇴 실패 - 그룹장 아님")
    void kickMember_NotOwner_Fail() {
        // given
        UUID groupId = UUID.randomUUID();
        User owner = User.builder().id(UUID.randomUUID()).build();
        Group group = Group.builder().id(groupId).createdBy(owner).build(); // Not the current user
        
        UUID targetUserId = UUID.randomUUID();

        given(groupRepository.findById(groupId)).willReturn(Optional.of(group));

        // when & then
        assertThatThrownBy(() -> groupService.kickMember(userId, groupId, targetUserId))
                .isInstanceOf(UnauthorizedAccessException.class);
    }

    @Test
    @DisplayName("멤버 강퇴 실패 - 자기 자신 강퇴 불가")
    void kickMember_Self_Fail() {
        // given
        UUID groupId = UUID.randomUUID();
        Group group = Group.builder().id(groupId).createdBy(user).build();
        
        given(groupRepository.findById(groupId)).willReturn(Optional.of(group));

        // when & then
        assertThatThrownBy(() -> groupService.kickMember(userId, groupId, userId))
                .isInstanceOf(RuntimeException.class);
    }
}
