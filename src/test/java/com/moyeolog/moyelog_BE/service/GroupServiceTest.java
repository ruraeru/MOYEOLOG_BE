package com.moyeolog.moyelog_BE.service;

import com.moyeolog.moyelog_BE.dto.GroupInviteRequest;
import com.moyeolog.moyelog_BE.dto.GroupRequest;
import com.moyeolog.moyelog_BE.dto.GroupResponse;
import com.moyeolog.moyelog_BE.entity.Group;
import com.moyeolog.moyelog_BE.entity.GroupBlock;
import com.moyeolog.moyelog_BE.entity.GroupInvitation;
import com.moyeolog.moyelog_BE.entity.GroupMember;
import com.moyeolog.moyelog_BE.entity.User;
import com.moyeolog.moyelog_BE.enums.InvitationStatus;
import com.moyeolog.moyelog_BE.event.NotificationEvent;
import com.moyeolog.moyelog_BE.event.NotificationType;
import com.moyeolog.moyelog_BE.event.UserDeletingEvent;
import com.moyeolog.moyelog_BE.exception.BadRequestException;
import com.moyeolog.moyelog_BE.exception.ConflictException;
import com.moyeolog.moyelog_BE.exception.MoyeologException;
import com.moyeolog.moyelog_BE.exception.UnauthorizedAccessException;
import com.moyeolog.moyelog_BE.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import com.moyeolog.moyelog_BE.datepoll.DatePollRepository;
import com.moyeolog.moyelog_BE.datepoll.DatePollVoteRepository;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
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
    @Mock private GroupBlockRepository groupBlockRepository;
    @Mock private FriendRepository friendRepository;
    @Mock private GroupImageService groupImageService;
    @Mock private DatePollRepository datePollRepository;
    @Mock private DatePollVoteRepository datePollVoteRepository;
    @Mock private MemoGroupShareRepository memoGroupShareRepository;
    @Mock private MemoPinRepository memoPinRepository;
    @Mock private ApplicationEventPublisher eventPublisher;

    @InjectMocks private GroupService groupService;

    private User user;
    private UUID userId;

    @BeforeEach
    void setUp() {
        userId = UUID.randomUUID();
        user = User.builder().id(userId).nickname("testUser").customId("abcd1234").build();
    }

    private Group ownedGroup(UUID groupId) {
        return Group.builder().id(groupId).name("모임").createdBy(user).build();
    }

    private static HttpStatus statusOf(Throwable t) {
        return ((MoyeologException) t).getStatus();
    }

    // ─── 만들기 ───────────────────────────────────────────────────

    @Test
    @DisplayName("모임 생성 성공 - 이름 trim, 색 기본값 indigo, myRole OWNER, 멤버 customId")
    void createGroup_Success() {
        given(userRepository.findById(userId)).willReturn(Optional.of(user));
        given(groupRepository.save(any(Group.class))).willAnswer(inv -> {
            Group g = inv.getArgument(0);
            g.setId(UUID.randomUUID());
            return g;
        });
        given(groupMemberRepository.findByGroup(any(Group.class))).willAnswer(inv ->
                List.of(GroupMember.builder().group(inv.getArgument(0)).user(user).build()));

        GroupResponse response = groupService.createGroup(userId, GroupRequest.builder().name("  Test Group  ").build());

        assertThat(response.getName()).isEqualTo("Test Group");
        assertThat(response.getColorTheme()).isEqualTo("indigo");
        assertThat(response.getMyRole()).isEqualTo("OWNER");
        assertThat(response.getMembers().get(0).getCustomId()).isEqualTo("abcd1234");
        verify(groupMemberRepository).save(any(GroupMember.class));
    }

    @Test
    @DisplayName("모임 이름은 코드 포인트로 50자까지 (이모지 30개 허용), 보이지 않는 문자만이면 400")
    void normalizeName() {
        assertThat(GroupService.normalizeName("📚".repeat(30))).isEqualTo("📚".repeat(30));
        assertThat(GroupService.normalizeName("가".repeat(50))).hasSize(50);
        assertThatThrownBy(() -> GroupService.normalizeName("가".repeat(51))).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> GroupService.normalizeName("​​")).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> GroupService.normalizeName("   ")).isInstanceOf(BadRequestException.class);
    }

    @Test
    @DisplayName("colorTheme 은 정해진 키만 허용")
    void normalizeColorTheme() {
        assertThat(GroupService.normalizeColorTheme(null, "indigo")).isEqualTo("indigo");
        assertThat(GroupService.normalizeColorTheme("rose", "indigo")).isEqualTo("rose");
        assertThatThrownBy(() -> GroupService.normalizeColorTheme("url(javascript:alert(1))", "indigo"))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> GroupService.normalizeColorTheme("a".repeat(101), "indigo"))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    @DisplayName("colorTheme 대소문자 무시, 응답에는 옛 값(null·hex·모르는 키)을 기본 색으로")
    void colorThemeCaseAndDisplay() {
        assertThat(GroupService.normalizeColorTheme(" Rose ", null)).isEqualTo("rose");
        assertThat(GroupService.normalizeColorTheme("", null)).isNull();
        assertThat(GroupService.displayColorTheme(null)).isEqualTo("indigo");
        assertThat(GroupService.displayColorTheme("#ff0000")).isEqualTo("indigo");
        assertThat(GroupService.displayColorTheme("EMERALD")).isEqualTo("emerald");
        assertThat(GroupService.displayColorTheme("amber")).isEqualTo("amber");
    }

    @Test
    @DisplayName("모임 상세 조회 실패 - 비멤버는 403")
    void getGroup_Unauthorized() {
        UUID groupId = UUID.randomUUID();
        given(groupRepository.findById(groupId)).willReturn(Optional.of(Group.builder().id(groupId).build()));
        given(groupMemberRepository.existsByGroup_IdAndUser_Id(groupId, userId)).willReturn(false);

        assertThatThrownBy(() -> groupService.getGroup(userId, groupId))
                .isInstanceOf(UnauthorizedAccessException.class);
    }

    // ─── 초대 코드 ────────────────────────────────────────────────

    @Test
    @DisplayName("초대 코드 가입 - groupId 반환, 그 모임 대기 초대 정리")
    void joinByCode_Success() {
        UUID groupId = UUID.randomUUID();
        Group group = Group.builder().id(groupId).createdBy(User.builder().id(UUID.randomUUID()).build()).build();
        given(groupRepository.findByInviteCode("ABC123")).willReturn(Optional.of(group));
        given(groupRepository.findByIdForUpdate(groupId)).willReturn(Optional.of(group));
        given(userRepository.findById(userId)).willReturn(Optional.of(user));

        assertThat(groupService.joinGroupByCode(userId, " abc123 ").getGroupId()).isEqualTo(groupId);
        verify(groupMemberRepository).save(any(GroupMember.class));
        verify(groupInvitationRepository).deletePendingForInvitee(groupId, userId);
    }

    @Test
    @DisplayName("초대 코드 가입 - 이미 멤버 409(groupId 포함), 차단된 사람 403, 없는 코드 404")
    void joinByCode_Failures() {
        UUID groupId = UUID.randomUUID();
        Group group = Group.builder().id(groupId).createdBy(User.builder().id(UUID.randomUUID()).build()).build();
        given(groupRepository.findByInviteCode("ABC123")).willReturn(Optional.of(group));
        given(groupRepository.findByIdForUpdate(groupId)).willReturn(Optional.of(group));
        given(userRepository.findById(userId)).willReturn(Optional.of(user));

        given(groupMemberRepository.existsByGroup_IdAndUser_Id(groupId, userId)).willReturn(true);
        assertThatThrownBy(() -> groupService.joinGroupByCode(userId, "ABC123"))
                .isInstanceOfSatisfying(GroupErrors.AlreadyMemberException.class, e -> {
                    assertThat(e.getGroupId()).isEqualTo(groupId);
                    assertThat(e.getMessage()).isEqualTo("이미 참여한 모임이에요.");
                });

        given(groupMemberRepository.existsByGroup_IdAndUser_Id(groupId, userId)).willReturn(false);
        given(groupBlockRepository.existsByGroup_IdAndUser_Id(groupId, userId)).willReturn(true);
        assertThatThrownBy(() -> groupService.joinGroupByCode(userId, "ABC123"))
                .isInstanceOf(UnauthorizedAccessException.class)
                .hasMessage("이 모임에 다시 들어갈 수 없어요.");

        assertThatThrownBy(() -> groupService.joinGroupByCode(userId, "ZZZZZZ"))
                .satisfies(t -> assertThat(statusOf(t)).isEqualTo(HttpStatus.NOT_FOUND))
                .hasMessage("초대 코드가 맞지 않거나 만료됐어요.");
        verify(groupMemberRepository, never()).save(any(GroupMember.class));
    }

    @Test
    @DisplayName("초대 코드 미리 보기 - 모임 정보와 alreadyMember")
    void preview() {
        UUID groupId = UUID.randomUUID();
        User owner = User.builder().id(UUID.randomUUID()).nickname("방장").build();
        Group group = Group.builder().id(groupId).name("캡스톤").colorTheme("rose")
                .profileImage("/uploads/p.png").backgroundImage("/uploads/b.png").createdBy(owner).build();
        given(groupRepository.findByInviteCode("ABC123")).willReturn(Optional.of(group));
        given(groupMemberRepository.findByGroup(group)).willReturn(List.of(GroupMember.builder().group(group).user(owner).build()));
        given(groupMemberRepository.existsByGroup_IdAndUser_Id(groupId, userId)).willReturn(false);

        var preview = groupService.previewByCode(userId, "abc123");
        assertThat(preview.getName()).isEqualTo("캡스톤");
        assertThat(preview.getOwnerNickname()).isEqualTo("방장");
        assertThat(preview.getMemberCount()).isEqualTo(1);
        assertThat(preview.isAlreadyMember()).isFalse();
        assertThat(preview.isBlocked()).isFalse();
        assertThat(preview.getColorTheme()).isEqualTo("rose");
        assertThat(preview.getProfileImage()).isEqualTo("/uploads/p.png");
        assertThat(preview.getBackgroundImage()).isEqualTo("/uploads/b.png");

        given(groupBlockRepository.existsByGroup_IdAndUser_Id(groupId, userId)).willReturn(true);
        assertThat(groupService.previewByCode(userId, "abc123").isBlocked()).isTrue();

        assertThatThrownBy(() -> groupService.previewByCode(userId, null))
                .satisfies(t -> assertThat(statusOf(t)).isEqualTo(HttpStatus.NOT_FOUND));
    }

    // ─── 내보내기·나가기·삭제 ─────────────────────────────────────

    @Test
    @DisplayName("멤버 내보내기 - 차단 등록, 받은·보낸 대기 초대 정리, 코드 재발급, 알림")
    void kickMember_Success() {
        UUID groupId = UUID.randomUUID();
        Group group = ownedGroup(groupId);
        group.setInviteCode("OLDCOD");
        UUID targetUserId = UUID.randomUUID();

        given(groupRepository.findByIdForUpdate(groupId)).willReturn(Optional.of(group));
        given(groupMemberRepository.deleteMembership(groupId, targetUserId)).willReturn(1);

        groupService.kickMember(userId, groupId, targetUserId);

        verify(groupBlockRepository).save(any(GroupBlock.class));
        verify(groupInvitationRepository).deletePendingForInvitee(groupId, targetUserId);
        verify(groupInvitationRepository).deletePendingFromInviter(groupId, targetUserId);
        // 이 모임으로 공유받아 고정한 개인 메모의 고정도 지운다
        verify(memoPinRepository).deleteByUserIdAndGroupSharedMemos(targetUserId, groupId);
        // 그 모임의 모임 메모에 한 내 고정도 지운다
        verify(memoPinRepository).deleteByUserIdAndGroupMemos(targetUserId, groupId);
        assertThat(group.getInviteCode()).isNotNull().isNotEqualTo("OLDCOD").hasSize(6);
        ArgumentCaptor<NotificationEvent> event = ArgumentCaptor.forClass(NotificationEvent.class);
        verify(eventPublisher).publishEvent(event.capture());
        assertThat(event.getValue().type()).isEqualTo(NotificationType.GROUP_KICKED);
        assertThat(event.getValue().recipientId()).isEqualTo(targetUserId);
        assertThat(event.getValue().link()).isEqualTo("/groups");
    }

    @Test
    @DisplayName("멤버 내보내기 - 이미 나간 사람(동시 요청)은 500 이 아니라 404")
    void kickMember_AlreadyGone_NotFound() {
        UUID groupId = UUID.randomUUID();
        UUID targetUserId = UUID.randomUUID();
        given(groupRepository.findByIdForUpdate(groupId)).willReturn(Optional.of(ownedGroup(groupId)));
        given(groupMemberRepository.deleteMembership(groupId, targetUserId)).willReturn(0);

        assertThatThrownBy(() -> groupService.kickMember(userId, groupId, targetUserId))
                .satisfies(t -> assertThat(statusOf(t)).isEqualTo(HttpStatus.NOT_FOUND));
        verify(groupBlockRepository, never()).save(any());
        verify(memoPinRepository, never()).deleteByUserIdAndGroupSharedMemos(any(), any());
        verify(memoPinRepository, never()).deleteByUserIdAndGroupMemos(any(), any());
    }

    @Test
    @DisplayName("멤버 내보내기 실패 - 방장 아님 403, 자기 자신 400")
    void kickMember_Failures() {
        UUID groupId = UUID.randomUUID();
        User owner = User.builder().id(UUID.randomUUID()).build();
        Group group = Group.builder().id(groupId).createdBy(owner).build();
        given(groupRepository.findByIdForUpdate(groupId)).willReturn(Optional.of(group));
        assertThatThrownBy(() -> groupService.kickMember(userId, groupId, UUID.randomUUID()))
                .isInstanceOf(UnauthorizedAccessException.class);

        UUID ownGroupId = UUID.randomUUID();
        given(groupRepository.findByIdForUpdate(ownGroupId)).willReturn(Optional.of(ownedGroup(ownGroupId)));
        assertThatThrownBy(() -> groupService.kickMember(userId, ownGroupId, userId))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    @DisplayName("나가기 - 방장 400, 이미 나간 경우(동시 요청) 403, 성공 시 보낸 대기 초대 정리")
    void leaveGroup() {
        UUID groupId = UUID.randomUUID();
        given(groupRepository.findByIdForUpdate(groupId)).willReturn(Optional.of(ownedGroup(groupId)));
        assertThatThrownBy(() -> groupService.leaveGroup(userId, groupId)).isInstanceOf(BadRequestException.class);

        UUID otherGroupId = UUID.randomUUID();
        Group other = Group.builder().id(otherGroupId).createdBy(User.builder().id(UUID.randomUUID()).build()).build();
        given(groupRepository.findByIdForUpdate(otherGroupId)).willReturn(Optional.of(other));
        given(groupMemberRepository.deleteMembership(otherGroupId, userId)).willReturn(1, 0);

        groupService.leaveGroup(userId, otherGroupId);
        verify(groupInvitationRepository).deletePendingFromInviter(otherGroupId, userId);
        verify(memoPinRepository).deleteByUserIdAndGroupSharedMemos(userId, otherGroupId);
        verify(memoPinRepository).deleteByUserIdAndGroupMemos(userId, otherGroupId);
        assertThatThrownBy(() -> groupService.leaveGroup(userId, otherGroupId))
                .isInstanceOf(UnauthorizedAccessException.class);
        // 이미 나간 경우(두 번째 호출)는 고정을 다시 지우지 않는다
        verify(memoPinRepository, times(1)).deleteByUserIdAndGroupSharedMemos(userId, otherGroupId);
        verify(memoPinRepository, times(1)).deleteByUserIdAndGroupMemos(userId, otherGroupId);
    }

    @Test
    @DisplayName("모임 삭제 - 방장만, 이미 삭제된 모임(동시 요청)은 404")
    void deleteGroup() {
        UUID groupId = UUID.randomUUID();
        User owner = User.builder().id(UUID.randomUUID()).build();
        given(groupRepository.findByIdForUpdate(groupId)).willReturn(Optional.of(Group.builder().id(groupId).createdBy(owner).build()));
        assertThatThrownBy(() -> groupService.deleteGroup(userId, groupId))
                .isInstanceOf(UnauthorizedAccessException.class);
        verify(groupRepository, never()).delete(any(Group.class));

        UUID goneId = UUID.randomUUID();
        given(groupRepository.findByIdForUpdate(goneId)).willReturn(Optional.empty());
        assertThatThrownBy(() -> groupService.deleteGroup(userId, goneId))
                .satisfies(t -> assertThat(statusOf(t)).isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    @DisplayName("모임 삭제 - 모임 메모가 개인 메모로 돌아가기 전에 작성자 아닌 사람의 내 고정을 지우고 모임 고정을 푼다")
    void deleteGroup_ClearsPins() {
        UUID groupId = UUID.randomUUID();
        Group group = ownedGroup(groupId);
        given(groupRepository.findByIdForUpdate(groupId)).willReturn(Optional.of(group));

        groupService.deleteGroup(userId, groupId);

        verify(memoPinRepository).deleteNonAuthorPinsByGroupId(groupId);
        verify(memoRepository).clearPinnedByGroupId(groupId);
        verify(groupRepository).delete(group);
    }

    @Test
    @DisplayName("모임 수정 - 방장만, 두 이미지 중 하나라도 이미지가 아니면 아무 파일도 저장하지 않는다")
    void updateGroup() {
        UUID groupId = UUID.randomUUID();
        User owner = User.builder().id(UUID.randomUUID()).build();
        given(groupRepository.findById(groupId)).willReturn(Optional.of(Group.builder().id(groupId).createdBy(owner).build()));
        assertThatThrownBy(() -> groupService.updateGroup(userId, groupId,
                GroupRequest.builder().name("new").build(), null, null))
                .isInstanceOf(UnauthorizedAccessException.class);

        UUID ownId = UUID.randomUUID();
        given(groupRepository.findById(ownId)).willReturn(Optional.of(ownedGroup(ownId)));
        var good = new org.springframework.mock.web.MockMultipartFile("image", new byte[]{1});
        var bad = new org.springframework.mock.web.MockMultipartFile("bgImage", new byte[]{2});
        org.mockito.Mockito.lenient().doThrow(new BadRequestException("x")).when(groupImageService).requireImage(bad);
        assertThatThrownBy(() -> groupService.updateGroup(userId, ownId, GroupRequest.builder().name("n").build(), good, bad))
                .isInstanceOf(BadRequestException.class);
        verify(groupImageService, never()).store(any());
    }

    private Group groupWithImages(UUID groupId) {
        Group group = ownedGroup(groupId);
        group.setColorTheme("rose");
        group.setProfileImage("/uploads/old-p.png");
        group.setBackgroundImage("/uploads/old-b.png");
        given(groupRepository.findById(groupId)).willReturn(Optional.of(group));
        return group;
    }

    @Test
    @DisplayName("모임 수정 - 새 사진으로 바꾸면 이전 파일은 커밋 뒤 삭제, 응답에 새 경로")
    void updateGroup_ReplaceImages() {
        UUID groupId = UUID.randomUUID();
        groupWithImages(groupId);
        var image = new org.springframework.mock.web.MockMultipartFile("image", new byte[]{1});
        var bg = new org.springframework.mock.web.MockMultipartFile("bgImage", new byte[]{2});
        given(groupImageService.store(image)).willReturn("/uploads/new-p.png");
        given(groupImageService.store(bg)).willReturn("/uploads/new-b.png");

        GroupResponse response = groupService.updateGroup(userId, groupId,
                GroupRequest.builder().name("모임").colorTheme("emerald").build(), image, bg);

        assertThat(response.getProfileImage()).isEqualTo("/uploads/new-p.png");
        assertThat(response.getBackgroundImage()).isEqualTo("/uploads/new-b.png");
        assertThat(response.getColorTheme()).isEqualTo("emerald");
        verify(fileService).deleteFileAfterCommit("/uploads/old-p.png");
        verify(fileService).deleteFileAfterCommit("/uploads/old-b.png");
    }

    @Test
    @DisplayName("모임 수정 - remove 플래그로 사진을 지우고 이전 파일은 커밋 뒤 삭제, 플래그 없으면 그대로")
    void updateGroup_RemoveImages() {
        UUID groupId = UUID.randomUUID();
        groupWithImages(groupId);

        GroupResponse kept = groupService.updateGroup(userId, groupId,
                GroupRequest.builder().name("모임").removeProfileImage(false).build(), null, null);
        assertThat(kept.getProfileImage()).isEqualTo("/uploads/old-p.png");
        assertThat(kept.getBackgroundImage()).isEqualTo("/uploads/old-b.png");
        assertThat(kept.getColorTheme()).isEqualTo("rose");
        verify(fileService, never()).deleteFileAfterCommit(any());

        GroupResponse onlyProfile = groupService.updateGroup(userId, groupId,
                GroupRequest.builder().name("모임").removeProfileImage(true).build(), null, null);
        assertThat(onlyProfile.getProfileImage()).isNull();
        assertThat(onlyProfile.getBackgroundImage()).isEqualTo("/uploads/old-b.png");
        verify(fileService).deleteFileAfterCommit("/uploads/old-p.png");

        GroupResponse both = groupService.updateGroup(userId, groupId,
                GroupRequest.builder().name("모임").removeBackgroundImage(true).build(), null, null);
        assertThat(both.getBackgroundImage()).isNull();
        verify(fileService).deleteFileAfterCommit("/uploads/old-b.png");
    }

    @Test
    @DisplayName("모임 수정 - 같은 요청에 remove 와 새 파일이 같이 오면 새 파일이 이긴다")
    void updateGroup_NewFileWinsOverRemove() {
        UUID groupId = UUID.randomUUID();
        groupWithImages(groupId);
        var bg = new org.springframework.mock.web.MockMultipartFile("bgImage", new byte[]{2});
        given(groupImageService.store(bg)).willReturn("/uploads/new-b.png");

        GroupResponse response = groupService.updateGroup(userId, groupId, GroupRequest.builder().name("모임")
                .removeProfileImage(true).removeBackgroundImage(true).build(), null, bg);

        assertThat(response.getProfileImage()).isNull();
        assertThat(response.getBackgroundImage()).isEqualTo("/uploads/new-b.png");
        verify(fileService).deleteFileAfterCommit("/uploads/old-p.png");
        verify(fileService).deleteFileAfterCommit("/uploads/old-b.png");
    }

    @Test
    @DisplayName("모임 수정 - 방장이 아니면 remove 플래그도 403, 잘못된 색이면 400 이고 아무 것도 바뀌지 않는다")
    void updateGroup_RejectsWithoutChanges() {
        UUID groupId = UUID.randomUUID();
        Group group = ownedGroup(groupId);
        group.setProfileImage("/uploads/old-p.png");
        group.setColorTheme("rose");
        group.setCreatedBy(User.builder().id(UUID.randomUUID()).build());
        given(groupRepository.findById(groupId)).willReturn(Optional.of(group));
        assertThatThrownBy(() -> groupService.updateGroup(userId, groupId,
                GroupRequest.builder().name("x").removeProfileImage(true).build(), null, null))
                .isInstanceOf(UnauthorizedAccessException.class);
        assertThat(group.getProfileImage()).isEqualTo("/uploads/old-p.png");

        UUID ownId = UUID.randomUUID();
        Group own = groupWithImages(ownId);
        assertThatThrownBy(() -> groupService.updateGroup(userId, ownId, GroupRequest.builder().name("새 이름")
                .colorTheme("#123456").removeProfileImage(true).build(), null, null))
                .isInstanceOf(BadRequestException.class);
        assertThat(own.getName()).isEqualTo("모임");
        assertThat(own.getColorTheme()).isEqualTo("rose");
        assertThat(own.getProfileImage()).isEqualTo("/uploads/old-p.png");
        verify(fileService, never()).deleteFileAfterCommit(any());
    }

    @Test
    @DisplayName("내 모임 목록·상세 응답에 사진·색이 빠짐없이 온다")
    void myGroupsAndDetailIncludeIdentity() {
        UUID groupId = UUID.randomUUID();
        Group group = groupWithImages(groupId);
        given(userRepository.findById(userId)).willReturn(Optional.of(user));
        GroupMember membership = GroupMember.builder().group(group).user(user).build();
        given(groupMemberRepository.findByUser(user)).willReturn(List.of(membership));
        given(groupMemberRepository.findByGroup(group)).willReturn(List.of(membership));
        given(groupMemberRepository.existsByGroup_IdAndUser_Id(groupId, userId)).willReturn(true);

        for (GroupResponse r : List.of(groupService.getMyGroups(userId).get(0), groupService.getGroup(userId, groupId))) {
            assertThat(r.getProfileImage()).isEqualTo("/uploads/old-p.png");
            assertThat(r.getBackgroundImage()).isEqualTo("/uploads/old-b.png");
            assertThat(r.getColorTheme()).isEqualTo("rose");
        }
    }

    // ─── 초대 ─────────────────────────────────────────────────────

    private GroupInvitation invitation(Group group, InvitationStatus status) {
        return GroupInvitation.builder()
                .id(UUID.randomUUID())
                .group(group)
                .inviter(User.builder().id(UUID.randomUUID()).build())
                .invitee(user)
                .status(status)
                .build();
    }

    private void stubInvitationLock(Group group, GroupInvitation invitation) {
        given(groupInvitationRepository.findGroupIdById(invitation.getId())).willReturn(Optional.of(group.getId()));
        given(groupRepository.findByIdForUpdate(group.getId())).willReturn(Optional.of(group));
        given(groupInvitationRepository.findByIdForUpdate(invitation.getId())).willReturn(Optional.of(invitation));
    }

    @Test
    @DisplayName("초대 수락 - 멤버십이 한 번만 생성되고 같은 모임 대기 초대 정리")
    void acceptInvitation_Success() {
        Group group = Group.builder().id(UUID.randomUUID()).build();
        GroupInvitation invitation = invitation(group, InvitationStatus.PENDING);
        stubInvitationLock(group, invitation);

        groupService.acceptInvitation(userId, invitation.getId());

        assertThat(invitation.getStatus()).isEqualTo(InvitationStatus.ACCEPTED);
        assertThat(invitation.getPendingFlag()).isNull();
        verify(groupMemberRepository).save(any(GroupMember.class));
        verify(groupInvitationRepository).deletePendingForInvitee(group.getId(), userId);
    }

    @Test
    @DisplayName("초대 이중 처리 - 수락 후 수락·거절은 409, 멤버십은 한 번")
    void acceptInvitation_Twice_Fail() {
        Group group = Group.builder().id(UUID.randomUUID()).build();
        GroupInvitation invitation = invitation(group, InvitationStatus.PENDING);
        stubInvitationLock(group, invitation);

        groupService.acceptInvitation(userId, invitation.getId());

        assertThatThrownBy(() -> groupService.acceptInvitation(userId, invitation.getId()))
                .isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> groupService.rejectInvitation(userId, invitation.getId()))
                .isInstanceOf(ConflictException.class);
        assertThat(invitation.getStatus()).isEqualTo(InvitationStatus.ACCEPTED);
        verify(groupMemberRepository, times(1)).save(any(GroupMember.class));
    }

    @Test
    @DisplayName("초대 수락 - 이미 멤버이면 멤버십을 추가하지 않는다")
    void acceptInvitation_AlreadyMember_NoSecondMembership() {
        Group group = Group.builder().id(UUID.randomUUID()).build();
        GroupInvitation invitation = invitation(group, InvitationStatus.PENDING);
        stubInvitationLock(group, invitation);
        given(groupMemberRepository.existsByGroup_IdAndUser_Id(group.getId(), userId)).willReturn(true);

        groupService.acceptInvitation(userId, invitation.getId());

        assertThat(invitation.getStatus()).isEqualTo(InvitationStatus.ACCEPTED);
        verify(groupMemberRepository, never()).save(any(GroupMember.class));
    }

    @Test
    @DisplayName("거절한 초대는 수락할 수 없고, 차단된 사람은 초대를 수락할 수 없다, 지워진 초대는 404")
    void acceptInvitation_Failures() {
        Group group = Group.builder().id(UUID.randomUUID()).build();
        GroupInvitation rejected = invitation(group, InvitationStatus.REJECTED);
        stubInvitationLock(group, rejected);
        assertThatThrownBy(() -> groupService.acceptInvitation(userId, rejected.getId()))
                .isInstanceOf(ConflictException.class);

        Group blockedGroup = Group.builder().id(UUID.randomUUID()).build();
        GroupInvitation pending = invitation(blockedGroup, InvitationStatus.PENDING);
        stubInvitationLock(blockedGroup, pending);
        given(groupBlockRepository.existsByGroup_IdAndUser_Id(blockedGroup.getId(), userId)).willReturn(true);
        assertThatThrownBy(() -> groupService.acceptInvitation(userId, pending.getId()))
                .isInstanceOf(UnauthorizedAccessException.class);
        assertThat(pending.getStatus()).isEqualTo(InvitationStatus.PENDING);

        UUID gone = UUID.randomUUID();
        given(groupInvitationRepository.findGroupIdById(gone)).willReturn(Optional.empty());
        assertThatThrownBy(() -> groupService.acceptInvitation(userId, gone))
                .satisfies(t -> assertThat(statusOf(t)).isEqualTo(HttpStatus.NOT_FOUND));
        verify(groupMemberRepository, never()).save(any(GroupMember.class));
    }

    @Test
    @DisplayName("초대 - emails 를 보내면 400, userIds 가 비면 400 (이메일로 사용자를 찾지 않는다)")
    void inviteMembers_Validation() {
        UUID groupId = UUID.randomUUID();
        assertThatThrownBy(() -> groupService.inviteMembers(userId, groupId, new GroupInviteRequest()))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> groupService.inviteMembers(userId, groupId,
                GroupInviteRequest.builder().emails(List.of("someone@example.com")).build()))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> groupService.inviteMembers(userId, groupId,
                GroupInviteRequest.builder().emails(List.of()).userIds(List.of(UUID.randomUUID())).build()))
                .isInstanceOf(BadRequestException.class);
        verify(userRepository, never()).findAllByEmail(any());
        verify(groupInvitationRepository, never()).save(any());
    }

    private Group stubInvitableGroup(UUID groupId, User owner) {
        Group group = Group.builder().id(groupId).name("모임").createdBy(owner).build();
        given(groupRepository.findById(groupId)).willReturn(Optional.of(group));
        given(groupRepository.findByIdForUpdate(groupId)).willReturn(Optional.of(group));
        given(groupMemberRepository.existsByGroup_IdAndUser_Id(groupId, userId)).willReturn(true);
        given(userRepository.findById(userId)).willReturn(Optional.of(user));
        return group;
    }

    @Test
    @DisplayName("초대 - 친구에게 PENDING 초대 생성 + GROUP_INVITE 알림")
    void inviteMembers_ByUserIds() {
        UUID groupId = UUID.randomUUID();
        Group group = stubInvitableGroup(groupId, user);
        User invitee = User.builder().id(UUID.randomUUID()).nickname("invitee").build();
        given(userRepository.findById(invitee.getId())).willReturn(Optional.of(invitee));
        given(friendRepository.areFriends(userId, invitee.getId())).willReturn(true);

        groupService.inviteMembers(userId, groupId,
                GroupInviteRequest.builder().userIds(List.of(invitee.getId(), invitee.getId())).build());

        verify(groupInvitationRepository, times(1)).save(any(GroupInvitation.class));
        ArgumentCaptor<NotificationEvent> event = ArgumentCaptor.forClass(NotificationEvent.class);
        verify(eventPublisher).publishEvent(event.capture());
        assertThat(event.getValue().type()).isEqualTo(NotificationType.GROUP_INVITE);
        assertThat(event.getValue().link()).isEqualTo("/notifications?tab=invites");
        assertThat(event.getValue().message()).contains("testUser").contains("모임");
    }

    @Test
    @DisplayName("초대 - 친구가 아니거나 없는 사용자는 같은 400")
    void inviteMembers_NotFriend() {
        UUID groupId = UUID.randomUUID();
        stubInvitableGroup(groupId, user);
        UUID stranger = UUID.randomUUID();
        given(friendRepository.areFriends(userId, stranger)).willReturn(false);

        assertThatThrownBy(() -> groupService.inviteMembers(userId, groupId,
                GroupInviteRequest.builder().userIds(List.of(stranger)).build()))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("친구에게만 초대를 보낼 수 있어요.");
        verify(groupInvitationRepository, never()).save(any());
    }

    @Test
    @DisplayName("초대 - 대기 초대가 있으면 건너뛴다(중복 1건)")
    void inviteMembers_PendingExists_Skip() {
        UUID groupId = UUID.randomUUID();
        Group group = stubInvitableGroup(groupId, user);
        User invitee = User.builder().id(UUID.randomUUID()).build();
        given(userRepository.findById(invitee.getId())).willReturn(Optional.of(invitee));
        given(friendRepository.areFriends(userId, invitee.getId())).willReturn(true);
        given(groupInvitationRepository.existsByGroupAndInviteeAndStatus(group, invitee, InvitationStatus.PENDING)).willReturn(true);

        groupService.inviteMembers(userId, groupId, GroupInviteRequest.builder().userIds(List.of(invitee.getId())).build());
        verify(groupInvitationRepository, never()).save(any());
    }

    @Test
    @DisplayName("초대 - 내보내진 사람: 일반 멤버는 403, 방장은 초대하며 차단 해제")
    void inviteMembers_Blocked() {
        User owner = User.builder().id(UUID.randomUUID()).build();
        UUID groupId = UUID.randomUUID();
        stubInvitableGroup(groupId, owner); // user 는 일반 멤버
        User kicked = User.builder().id(UUID.randomUUID()).build();
        given(userRepository.findById(kicked.getId())).willReturn(Optional.of(kicked));
        given(friendRepository.areFriends(userId, kicked.getId())).willReturn(true);
        given(groupBlockRepository.existsByGroup_IdAndUser_Id(groupId, kicked.getId())).willReturn(true);

        assertThatThrownBy(() -> groupService.inviteMembers(userId, groupId,
                GroupInviteRequest.builder().userIds(List.of(kicked.getId())).build()))
                .isInstanceOf(UnauthorizedAccessException.class)
                .hasMessage("이 모임에 다시 들어갈 수 없어요.");
        verify(groupInvitationRepository, never()).save(any());

        UUID ownGroupId = UUID.randomUUID();
        stubInvitableGroup(ownGroupId, user); // user 가 방장
        given(groupBlockRepository.existsByGroup_IdAndUser_Id(ownGroupId, kicked.getId())).willReturn(true);
        groupService.inviteMembers(userId, ownGroupId, GroupInviteRequest.builder().userIds(List.of(kicked.getId())).build());
        verify(groupBlockRepository).deleteBlock(ownGroupId, kicked.getId());
        verify(groupInvitationRepository).save(any(GroupInvitation.class));
    }

    @Test
    @DisplayName("초대 - 최근에 거절한 친구는 일반 멤버가 다시 초대할 수 없다(409)")
    void inviteMembers_RecentlyRejected() {
        User owner = User.builder().id(UUID.randomUUID()).build();
        UUID groupId = UUID.randomUUID();
        stubInvitableGroup(groupId, owner);
        User friend = User.builder().id(UUID.randomUUID()).build();
        given(userRepository.findById(friend.getId())).willReturn(Optional.of(friend));
        given(friendRepository.areFriends(userId, friend.getId())).willReturn(true);
        given(groupInvitationRepository.existsByGroup_IdAndInvitee_IdAndStatusAndRespondedAtAfter(
                eq(groupId), eq(friend.getId()), eq(InvitationStatus.REJECTED), any())).willReturn(true);

        assertThatThrownBy(() -> groupService.inviteMembers(userId, groupId,
                GroupInviteRequest.builder().userIds(List.of(friend.getId())).build()))
                .isInstanceOf(ConflictException.class);
        verify(groupInvitationRepository, never()).save(any());
    }

    // ─── 회원 탈퇴 ────────────────────────────────────────────────

    @Test
    @DisplayName("회원 탈퇴 - 방장인 모임은 가장 오래된 다른 멤버에게 넘기고, 초대·차단·멤버십을 지운다")
    void onUserDeleting_TransfersOwnership() {
        UUID groupId = UUID.randomUUID();
        Group group = ownedGroup(groupId);
        User next = User.builder().id(UUID.randomUUID()).build();
        given(groupRepository.findAllByCreatedBy_Id(userId)).willReturn(List.of(group));
        given(groupRepository.findByIdForUpdate(groupId)).willReturn(Optional.of(group));
        given(groupMemberRepository.findAllByGroup_IdOrderByJoinedAtAsc(groupId)).willReturn(List.of(
                GroupMember.builder().group(group).user(user).build(),
                GroupMember.builder().group(group).user(next).build()));

        groupService.onUserDeleting(new UserDeletingEvent(userId));

        assertThat(group.getCreatedBy()).isEqualTo(next);
        verify(groupRepository, never()).delete(any(Group.class));
        verify(groupInvitationRepository).deleteAllInvolvingUser(userId);
        verify(groupBlockRepository).deleteAllInvolvingUser(userId);
        verify(groupMemberRepository).deleteAllByUserId(userId);
    }

    @Test
    @DisplayName("회원 탈퇴 - 혼자인 모임은 삭제")
    void onUserDeleting_DeletesSoloGroup() {
        UUID groupId = UUID.randomUUID();
        Group group = ownedGroup(groupId);
        given(groupRepository.findAllByCreatedBy_Id(userId)).willReturn(List.of(group));
        given(groupRepository.findByIdForUpdate(groupId)).willReturn(Optional.of(group));
        given(groupMemberRepository.findAllByGroup_IdOrderByJoinedAtAsc(groupId)).willReturn(List.of(
                GroupMember.builder().group(group).user(user).build()));

        groupService.onUserDeleting(new UserDeletingEvent(userId));

        verify(groupRepository).delete(group);
    }
}
