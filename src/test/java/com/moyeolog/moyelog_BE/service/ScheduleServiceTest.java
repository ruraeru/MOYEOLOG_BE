package com.moyeolog.moyelog_BE.service;

import com.moyeolog.moyelog_BE.dto.ScheduleRequest;
import com.moyeolog.moyelog_BE.dto.ScheduleResponse;
import com.moyeolog.moyelog_BE.entity.Schedule;
import com.moyeolog.moyelog_BE.entity.User;
import com.moyeolog.moyelog_BE.repository.GroupMemberRepository;
import com.moyeolog.moyelog_BE.repository.MemoRepository;
import com.moyeolog.moyelog_BE.repository.ScheduleRepository;
import com.moyeolog.moyelog_BE.repository.UserRepository;
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
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Unauthorized");
    }
}
