package com.moyeolog.moyelog_BE.service;

import com.moyeolog.moyelog_BE.dto.GroupRequest;
import com.moyeolog.moyelog_BE.dto.GroupResponse;
import com.moyeolog.moyelog_BE.entity.Group;
import com.moyeolog.moyelog_BE.entity.GroupMember;
import com.moyeolog.moyelog_BE.entity.User;
import com.moyeolog.moyelog_BE.exception.UnauthorizedAccessException;
import com.moyeolog.moyelog_BE.dto.GroupInviteRequest;
import com.moyeolog.moyelog_BE.entity.GroupInvitation;
import com.moyeolog.moyelog_BE.enums.InvitationStatus;
import com.moyeolog.moyelog_BE.exception.BadRequestException;
import com.moyeolog.moyelog_BE.exception.ConflictException;
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
class GroupServiceTest {

    @Mock private GroupRepository groupRepository;
    @Mock private GroupMemberRepository groupMemberRepository;
    @Mock private UserRepository userRepository;
    @Mock private GroupInvitationRepository groupInvitationRepository;
    @Mock private MemoRepository memoRepository;
    @Mock private GroupTopicRepository groupTopicRepository;
    @Mock private ScheduleRepository scheduleRepository;
    @Mock private GroupTopicCommentRepository groupTopicCommentRepository;
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
        given(groupMemberRepository.existsByGroup_IdAndUser_Id(groupId, userId)).willReturn(false);

        // when & then (비멤버는 403)
        assertThatThrownBy(() -> groupService.getGroup(userId, groupId))
                .isInstanceOf(UnauthorizedAccessException.class);
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
        group.setInviteCode("OLDCOD");
        given(groupMemberRepository.findAllByGroup_IdAndUser_Id(groupId, targetUserId)).willReturn(java.util.List.of(targetMember));

        // when
        groupService.kickMember(userId, groupId, targetUserId);

        // then
        verify(groupMemberRepository).deleteAll(java.util.List.of(targetMember));
        // 강퇴 후에는 초대 코드가 새로 발급된다
        assertThat(group.getInviteCode()).isNotNull().isNotEqualTo("OLDCOD").hasSize(6);
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
                .isInstanceOf(BadRequestException.class);
    }

    private GroupInvitation invitation(Group group, InvitationStatus status) {
        return GroupInvitation.builder()
                .id(UUID.randomUUID())
                .group(group)
                .inviter(User.builder().id(UUID.randomUUID()).build())
                .invitee(user)
                .status(status)
                .build();
    }

    @Test
    @DisplayName("초대 수락 - 멤버십이 한 번만 생성된다")
    void acceptInvitation_Success() {
        Group group = Group.builder().id(UUID.randomUUID()).build();
        GroupInvitation invitation = invitation(group, InvitationStatus.PENDING);
        given(groupInvitationRepository.findById(invitation.getId())).willReturn(Optional.of(invitation));
        given(groupMemberRepository.existsByGroup_IdAndUser_Id(group.getId(), userId)).willReturn(false);

        groupService.acceptInvitation(userId, invitation.getId());

        assertThat(invitation.getStatus()).isEqualTo(InvitationStatus.ACCEPTED);
        verify(groupMemberRepository).save(any(GroupMember.class));
    }

    @Test
    @DisplayName("초대 이중 수락 - 두 번째 수락은 409 이고 멤버십을 또 만들지 않는다")
    void acceptInvitation_Twice_Fail() {
        Group group = Group.builder().id(UUID.randomUUID()).build();
        GroupInvitation invitation = invitation(group, InvitationStatus.PENDING);
        given(groupInvitationRepository.findById(invitation.getId())).willReturn(Optional.of(invitation));
        given(groupMemberRepository.existsByGroup_IdAndUser_Id(group.getId(), userId)).willReturn(false);

        groupService.acceptInvitation(userId, invitation.getId());

        assertThatThrownBy(() -> groupService.acceptInvitation(userId, invitation.getId()))
                .isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> groupService.rejectInvitation(userId, invitation.getId()))
                .isInstanceOf(ConflictException.class);
        verify(groupMemberRepository, org.mockito.Mockito.times(1)).save(any(GroupMember.class));
    }

    @Test
    @DisplayName("초대 수락 - 이미 멤버이면 멤버십을 추가하지 않는다")
    void acceptInvitation_AlreadyMember_NoSecondMembership() {
        Group group = Group.builder().id(UUID.randomUUID()).build();
        GroupInvitation invitation = invitation(group, InvitationStatus.PENDING);
        given(groupInvitationRepository.findById(invitation.getId())).willReturn(Optional.of(invitation));
        given(groupMemberRepository.existsByGroup_IdAndUser_Id(group.getId(), userId)).willReturn(true);

        groupService.acceptInvitation(userId, invitation.getId());

        assertThat(invitation.getStatus()).isEqualTo(InvitationStatus.ACCEPTED);
        verify(groupMemberRepository, org.mockito.Mockito.never()).save(any(GroupMember.class));
    }

    @Test
    @DisplayName("거절한 초대는 수락할 수 없다")
    void acceptInvitation_Rejected_Fail() {
        Group group = Group.builder().id(UUID.randomUUID()).build();
        GroupInvitation invitation = invitation(group, InvitationStatus.REJECTED);
        given(groupInvitationRepository.findById(invitation.getId())).willReturn(Optional.of(invitation));

        assertThatThrownBy(() -> groupService.acceptInvitation(userId, invitation.getId()))
                .isInstanceOf(ConflictException.class);
        verify(groupMemberRepository, org.mockito.Mockito.never()).save(any(GroupMember.class));
    }

    @Test
    @DisplayName("초대 - emails 와 userIds 가 모두 비어 있으면 400, null/빈 이메일은 건너뛴다")
    void inviteMembers_Validation() {
        UUID groupId = UUID.randomUUID();
        Group group = Group.builder().id(groupId).createdBy(user).build();
        given(groupRepository.findById(groupId)).willReturn(Optional.of(group));
        given(userRepository.findById(userId)).willReturn(Optional.of(user));
        given(groupMemberRepository.existsByGroup_IdAndUser_Id(groupId, userId)).willReturn(true);

        assertThatThrownBy(() -> groupService.inviteMembers(userId, groupId, new GroupInviteRequest()))
                .isInstanceOf(BadRequestException.class);

        java.util.List<String> onlyBlank = new java.util.ArrayList<>();
        onlyBlank.add(null);
        onlyBlank.add("  ");
        assertThatThrownBy(() -> groupService.inviteMembers(userId, groupId,
                GroupInviteRequest.builder().emails(onlyBlank).build()))
                .isInstanceOf(BadRequestException.class);
        verify(userRepository, org.mockito.Mockito.never()).findAllByEmail(any());
    }

    @Test
    @DisplayName("초대 - userIds 로 초대하면 PENDING 초대가 생성된다")
    void inviteMembers_ByUserIds() {
        UUID groupId = UUID.randomUUID();
        Group group = Group.builder().id(groupId).createdBy(user).build();
        User invitee = User.builder().id(UUID.randomUUID()).nickname("invitee").build();
        given(groupRepository.findById(groupId)).willReturn(Optional.of(group));
        given(userRepository.findById(userId)).willReturn(Optional.of(user));
        given(userRepository.findById(invitee.getId())).willReturn(Optional.of(invitee));
        given(groupMemberRepository.existsByGroup_IdAndUser_Id(groupId, userId)).willReturn(true);
        given(groupMemberRepository.existsByGroup_IdAndUser_Id(groupId, invitee.getId())).willReturn(false);
        given(groupInvitationRepository.existsByGroupAndInviteeAndStatus(group, invitee, InvitationStatus.PENDING)).willReturn(false);

        groupService.inviteMembers(userId, groupId,
                GroupInviteRequest.builder().userIds(java.util.List.of(invitee.getId())).build());

        verify(groupInvitationRepository).save(any(GroupInvitation.class));
    }

    @Test
    @DisplayName("그룹 수정은 그룹장만 가능하다")
    void updateGroup_NotOwner_Fail() {
        UUID groupId = UUID.randomUUID();
        User owner = User.builder().id(UUID.randomUUID()).build();
        Group group = Group.builder().id(groupId).createdBy(owner).build();
        given(groupRepository.findById(groupId)).willReturn(Optional.of(group));

        assertThatThrownBy(() -> groupService.updateGroup(userId, groupId,
                GroupRequest.builder().name("new").build(), null, null))
                .isInstanceOf(UnauthorizedAccessException.class);
    }

    @Test
    @DisplayName("그룹장은 그룹을 나갈 수 없다")
    void leaveGroup_Owner_Fail() {
        UUID groupId = UUID.randomUUID();
        Group group = Group.builder().id(groupId).createdBy(user).build();
        given(groupRepository.findById(groupId)).willReturn(Optional.of(group));

        assertThatThrownBy(() -> groupService.leaveGroup(userId, groupId))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    @DisplayName("그룹 삭제는 그룹장만 가능하다")
    void deleteGroup_NotOwner_Fail() {
        UUID groupId = UUID.randomUUID();
        User owner = User.builder().id(UUID.randomUUID()).build();
        Group group = Group.builder().id(groupId).createdBy(owner).build();
        given(groupRepository.findById(groupId)).willReturn(Optional.of(group));

        assertThatThrownBy(() -> groupService.deleteGroup(userId, groupId))
                .isInstanceOf(UnauthorizedAccessException.class);
        verify(groupRepository, org.mockito.Mockito.never()).delete(any(Group.class));
    }
}
