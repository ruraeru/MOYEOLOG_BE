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
        given(scheduleRepository.findById(scheduleId)).willReturn(Optional.of(schedule));

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
                .isInstanceOf(com.moyeolog.moyelog_BE.exception.ResourceNotFoundException.class);

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
}
