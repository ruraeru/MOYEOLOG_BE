package com.moyeolog.moyelog_BE.service;

import com.moyeolog.moyelog_BE.dto.ScheduleRequest;
import com.moyeolog.moyelog_BE.dto.ScheduleResponse;
import com.moyeolog.moyelog_BE.entity.Schedule;
import com.moyeolog.moyelog_BE.entity.User;
import com.moyeolog.moyelog_BE.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class ScheduleServiceTest {

    @Mock private ScheduleRepository scheduleRepository;
    @Mock private UserRepository userRepository;
    @Mock private GroupMemberRepository groupMemberRepository;
    @Mock private MemoRepository memoRepository;
    @Mock private GroupRepository groupRepository;
    @Mock private FriendRepository friendRepository;
    @Mock private MemoShareRepository memoShareRepository;
    @Mock private org.springframework.context.ApplicationEventPublisher eventPublisher;

    @InjectMocks private ScheduleService scheduleService;

    private User user;
    private UUID userId;

    @BeforeEach
    void setUp() {
        userId = UUID.randomUUID();
        user = User.builder().id(userId).nickname("testUser").build();
    }

    @Test
    @DisplayName("일정 생성 성공")
    void createSchedule_Success() {
        // given
        ScheduleRequest request = ScheduleRequest.builder()
                .title("Meeting")
                .startTime(LocalDateTime.now())
                .endTime(LocalDateTime.now().plusHours(1))
                .build();
        Schedule schedule = Schedule.builder()
                .id(UUID.randomUUID())
                .title(request.getTitle())
                .author(user)
                .build();

        given(userRepository.findById(userId)).willReturn(Optional.of(user));
        given(scheduleRepository.save(any(Schedule.class))).willReturn(schedule);

        // when
        ScheduleResponse response = scheduleService.createSchedule(userId, request);

        // then
        assertThat(response.getTitle()).isEqualTo(request.getTitle());
        verify(scheduleRepository).save(any(Schedule.class));
    }

    @Test
    @DisplayName("일정 삭제 실패 - 권한 없음")
    void deleteSchedule_Unauthorized() {
        // given
        UUID scheduleId = UUID.randomUUID();
        User author = User.builder().id(UUID.randomUUID()).build();
        Schedule schedule = Schedule.builder().id(scheduleId).author(author).build();
        given(scheduleRepository.findByIdForUpdate(scheduleId)).willReturn(Optional.of(schedule));

        // when & then
        assertThatThrownBy(() -> scheduleService.deleteSchedule(userId, scheduleId))
                .isInstanceOf(com.moyeolog.moyelog_BE.exception.UnauthorizedAccessException.class);
    }

    private ScheduleRequest.ScheduleRequestBuilder baseRequest() {
        LocalDateTime start = LocalDateTime.of(2026, 1, 1, 10, 0);
        return ScheduleRequest.builder().title("Meeting").startTime(start).endTime(start.plusHours(1));
    }

    @Test
    @DisplayName("종료 시간이 시작 시간보다 빠르면 400")
    void createSchedule_EndBeforeStart_Fail() {
        LocalDateTime start = LocalDateTime.of(2026, 1, 1, 10, 0);
        ScheduleRequest request = ScheduleRequest.builder().title("Meeting").startTime(start).endTime(start.minusMinutes(1)).build();
        given(userRepository.findById(userId)).willReturn(Optional.of(user));

        assertThatThrownBy(() -> scheduleService.createSchedule(userId, request))
                .isInstanceOf(com.moyeolog.moyelog_BE.exception.BadRequestException.class);
    }

    @Test
    @DisplayName("그룹 멤버가 아니면 그룹 일정을 만들 수 없다 (403), 없는 그룹은 404")
    void createSchedule_GroupChecks() {
        UUID groupId = UUID.randomUUID();
        ScheduleRequest request = baseRequest().groupId(groupId).build();
        given(userRepository.findById(userId)).willReturn(Optional.of(user));

        given(groupRepository.existsById(groupId)).willReturn(false);
        assertThatThrownBy(() -> scheduleService.createSchedule(userId, request))
                .isInstanceOf(ItemNotFoundException.class);

        given(groupRepository.existsById(groupId)).willReturn(true);
        given(groupMemberRepository.existsByGroup_IdAndUser_Id(groupId, userId)).willReturn(false);
        assertThatThrownBy(() -> scheduleService.createSchedule(userId, request))
                .isInstanceOf(com.moyeolog.moyelog_BE.exception.UnauthorizedAccessException.class);
    }

    @Test
    @DisplayName("개인 일정의 참여자는 친구여야 한다")
    void createSchedule_ParticipantMustBeFriend() {
        UUID stranger = UUID.randomUUID();
        ScheduleRequest request = baseRequest().participantIds(java.util.List.of(stranger)).build();
        given(userRepository.findById(userId)).willReturn(Optional.of(user));
        given(friendRepository.areFriends(userId, stranger)).willReturn(false);

        assertThatThrownBy(() -> scheduleService.createSchedule(userId, request))
                .isInstanceOf(com.moyeolog.moyelog_BE.exception.BadRequestException.class);
    }

    @Test
    @DisplayName("그룹 일정의 참여자는 그룹 멤버여야 한다")
    void createSchedule_ParticipantMustBeGroupMember() {
        UUID groupId = UUID.randomUUID();
        UUID outsider = UUID.randomUUID();
        ScheduleRequest request = baseRequest().groupId(groupId).participantIds(java.util.List.of(outsider)).build();
        given(userRepository.findById(userId)).willReturn(Optional.of(user));
        given(groupRepository.existsById(groupId)).willReturn(true);
        given(groupMemberRepository.existsByGroup_IdAndUser_Id(groupId, userId)).willReturn(true);
        given(groupMemberRepository.existsByGroup_IdAndUser_Id(groupId, outsider)).willReturn(false);

        assertThatThrownBy(() -> scheduleService.createSchedule(userId, request))
                .isInstanceOf(com.moyeolog.moyelog_BE.exception.BadRequestException.class);
    }

    @Test
    @DisplayName("읽을 수 없는 메모는 일정에 태그할 수 없다")
    void createSchedule_TaggedMemoMustBeReadable() {
        User other = User.builder().id(UUID.randomUUID()).build();
        com.moyeolog.moyelog_BE.entity.Memo foreign = com.moyeolog.moyelog_BE.entity.Memo.builder()
                .id(UUID.randomUUID()).author(other).title("secret").build();
        ScheduleRequest request = baseRequest().taggedMemoIds(java.util.List.of(foreign.getId())).build();
        given(userRepository.findById(userId)).willReturn(Optional.of(user));
        given(memoRepository.findAllById(org.mockito.ArgumentMatchers.<Iterable<UUID>>any())).willReturn(java.util.List.of(foreign));
        given(memoShareRepository.existsByMemo_IdAndSharedTo_Id(foreign.getId(), userId)).willReturn(false);

        assertThatThrownBy(() -> scheduleService.createSchedule(userId, request))
                .isInstanceOf(com.moyeolog.moyelog_BE.exception.UnauthorizedAccessException.class);
    }

    @Test
    @DisplayName("그룹 일정 목록은 멤버만 조회할 수 있다")
    void getGroupSchedules_NotMember_Fail() {
        UUID groupId = UUID.randomUUID();
        given(groupRepository.existsById(groupId)).willReturn(true);
        given(groupMemberRepository.existsByGroup_IdAndUser_Id(groupId, userId)).willReturn(false);

        assertThatThrownBy(() -> scheduleService.getGroupSchedules(userId, groupId))
                .isInstanceOf(com.moyeolog.moyelog_BE.exception.UnauthorizedAccessException.class);
    }

    @Test
    @DisplayName("이미 지워진 일정을 다시 지우면 404 (동시 삭제)")
    void deleteSchedule_AlreadyDeleted_404() {
        UUID scheduleId = UUID.randomUUID();
        given(scheduleRepository.findByIdForUpdate(scheduleId)).willReturn(Optional.empty());

        assertThatThrownBy(() -> scheduleService.deleteSchedule(userId, scheduleId))
                .isInstanceOf(ItemNotFoundException.class);
    }

    @Test
    @DisplayName("모임을 나간 작성자는 모임 일정을 고칠 수 없고(403), 삭제는 할 수 있다")
    void groupSchedule_FormerMemberAuthor() {
        UUID groupId = UUID.randomUUID();
        Schedule schedule = Schedule.builder().id(UUID.randomUUID()).title("t").author(user).groupId(groupId)
                .participants(new java.util.ArrayList<>()).taggedMemos(new java.util.ArrayList<>()).build();
        given(scheduleRepository.findByIdForUpdate(schedule.getId())).willReturn(Optional.of(schedule));
        given(groupMemberRepository.existsByGroup_IdAndUser_Id(groupId, userId)).willReturn(false);

        assertThatThrownBy(() -> scheduleService.updateSchedule(userId, schedule.getId(), baseRequest().groupId(groupId).build()))
                .isInstanceOf(com.moyeolog.moyelog_BE.exception.UnauthorizedAccessException.class);
        assertThatThrownBy(() -> scheduleService.updateSchedule(userId, schedule.getId(), baseRequest().build()))
                .isInstanceOf(com.moyeolog.moyelog_BE.exception.UnauthorizedAccessException.class);
        assertThat(schedule.getGroupId()).isEqualTo(groupId);

        scheduleService.deleteSchedule(userId, schedule.getId());
        verify(scheduleRepository).delete(schedule);
    }

    @Test
    @DisplayName("친구를 끊은 기존 참여자는 수정 때 조용히 빠지고, 새로 넣는 친구 아닌 사람은 400")
    void updateSchedule_DropsStaleParticipants() {
        User exFriend = User.builder().id(UUID.randomUUID()).nickname("ex").build();
        UUID stranger = UUID.randomUUID();
        Schedule schedule = Schedule.builder().id(UUID.randomUUID()).title("t").author(user)
                .participants(new java.util.ArrayList<>(java.util.List.of(exFriend)))
                .taggedMemos(new java.util.ArrayList<>()).build();
        given(scheduleRepository.findByIdForUpdate(schedule.getId())).willReturn(Optional.of(schedule));
        given(friendRepository.areFriends(userId, exFriend.getId())).willReturn(false);

        ScheduleResponse response = scheduleService.updateSchedule(userId, schedule.getId(),
                baseRequest().participantIds(java.util.List.of(exFriend.getId())).build());
        assertThat(response.getParticipants()).isEmpty();

        given(friendRepository.areFriends(userId, stranger)).willReturn(false);
        assertThatThrownBy(() -> scheduleService.updateSchedule(userId, schedule.getId(),
                baseRequest().participantIds(java.util.List.of(stranger)).build()))
                .isInstanceOf(com.moyeolog.moyelog_BE.exception.BadRequestException.class);
    }

    @Test
    @DisplayName("일정 수정: 새로 추가된 참여자에게만 알림, 응답의 연결 메모는 볼 수 있는 것만")
    void updateSchedule_NotifiesNewParticipantsOnly_AndFiltersMemos() {
        User oldFriend = User.builder().id(UUID.randomUUID()).nickname("old").build();
        User newFriend = User.builder().id(UUID.randomUUID()).nickname("new").build();
        User other = User.builder().id(UUID.randomUUID()).build();
        com.moyeolog.moyelog_BE.entity.Memo hidden = com.moyeolog.moyelog_BE.entity.Memo.builder()
                .id(UUID.randomUUID()).author(other).title("비밀").build();
        Schedule schedule = Schedule.builder().id(UUID.randomUUID()).title("t").author(user)
                .participants(new java.util.ArrayList<>(java.util.List.of(oldFriend)))
                .taggedMemos(new java.util.ArrayList<>(java.util.List.of(hidden))).build();
        given(scheduleRepository.findByIdForUpdate(schedule.getId())).willReturn(Optional.of(schedule));
        given(friendRepository.areFriends(userId, oldFriend.getId())).willReturn(true);
        given(friendRepository.areFriends(userId, newFriend.getId())).willReturn(true);
        given(userRepository.findAllById(any())).willReturn(java.util.List.of(oldFriend, newFriend));
        given(memoRepository.findAllById(org.mockito.ArgumentMatchers.<Iterable<UUID>>any())).willReturn(java.util.List.of(hidden));

        ScheduleResponse response = scheduleService.updateSchedule(userId, schedule.getId(), baseRequest()
                .participantIds(java.util.List.of(oldFriend.getId(), newFriend.getId()))
                .taggedMemoIds(java.util.List.of(hidden.getId())).build());

        org.mockito.ArgumentCaptor<com.moyeolog.moyelog_BE.event.NotificationEvent> captor =
                org.mockito.ArgumentCaptor.forClass(com.moyeolog.moyelog_BE.event.NotificationEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().recipientId()).isEqualTo(newFriend.getId());
        assertThat(captor.getValue().link()).isEqualTo("/schedule/" + schedule.getId());
        assertThat(response.getTaggedMemos()).isEmpty();
    }
}
