package com.moyeolog.moyelog_BE.service;

import com.moyeolog.moyelog_BE.dto.MemoResponse;
import com.moyeolog.moyelog_BE.dto.ScheduleRequest;
import com.moyeolog.moyelog_BE.dto.ScheduleResponse;
import com.moyeolog.moyelog_BE.entity.Memo;
import com.moyeolog.moyelog_BE.entity.Schedule;
import com.moyeolog.moyelog_BE.entity.User;
import com.moyeolog.moyelog_BE.repository.GroupMemberRepository;
import com.moyeolog.moyelog_BE.repository.MemoRepository;
import com.moyeolog.moyelog_BE.repository.ScheduleRepository;
import com.moyeolog.moyelog_BE.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ScheduleService {

    private final ScheduleRepository scheduleRepository;
    private final UserRepository userRepository;
    private final GroupMemberRepository groupMemberRepository;
    private final MemoRepository memoRepository;

    @Transactional
    public ScheduleResponse createSchedule(UUID userId, ScheduleRequest request) {
        User author = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("User not found"));

        List<Memo> taggedMemos = new ArrayList<>();
        if (request.getTaggedMemoIds() != null && !request.getTaggedMemoIds().isEmpty()) {
            taggedMemos = memoRepository.findAllById(request.getTaggedMemoIds());
        }

        Schedule schedule = Schedule.builder()
                .title(request.getTitle())
                .description(request.getDescription())
                .startTime(request.getStartTime())
                .endTime(request.getEndTime())
                .location(request.getLocation())
                .author(author)
                .groupId(request.getGroupId())
                .taggedMemos(taggedMemos)
                .build();

        Schedule savedSchedule = scheduleRepository.save(schedule);
        return convertToResponse(savedSchedule);
    }

    @Transactional(readOnly = true)
    public List<ScheduleResponse> getSchedules(UUID userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("User not found"));

        List<UUID> groupIds = groupMemberRepository.findByUser(user).stream()
                .map(m -> m.getGroup().getId())
                .collect(Collectors.toList());

        List<Schedule> schedules;
        if (groupIds.isEmpty()) {
            schedules = scheduleRepository.findAllByAuthor(user);
        } else {
            schedules = scheduleRepository.findByAuthorOrGroupIds(user, groupIds);
        }

        return schedules.stream()
                .map(this::convertToResponse)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<ScheduleResponse> getGroupSchedules(UUID groupId) {
        return scheduleRepository.findAllByGroupIdOrderByStartTimeAsc(groupId).stream()
                .map(this::convertToResponse)
                .collect(Collectors.toList());
    }

    @Transactional
    public void deleteSchedule(UUID userId, UUID scheduleId) {
        Schedule schedule = scheduleRepository.findById(scheduleId)
                .orElseThrow(() -> new RuntimeException("Schedule not found"));

        if (!schedule.getAuthor().getId().equals(userId)) {
            throw new RuntimeException("Unauthorized delete request");
        }

        scheduleRepository.delete(schedule);
    }

    private ScheduleResponse convertToResponse(Schedule schedule) {
        List<MemoResponse> memoResponses = new ArrayList<>();
        if (schedule.getTaggedMemos() != null) {
            memoResponses = schedule.getTaggedMemos().stream()
                .map(m -> MemoResponse.builder()
                    .id(m.getId())
                    .title(m.getTitle())
                    .build())
                .collect(Collectors.toList());
        }

        return ScheduleResponse.builder()
                .id(schedule.getId())
                .title(schedule.getTitle())
                .description(schedule.getDescription())
                .startTime(schedule.getStartTime())
                .endTime(schedule.getEndTime())
                .location(schedule.getLocation())
                .authorId(schedule.getAuthor().getId())
                .authorNickname(schedule.getAuthor().getNickname())
                .groupId(schedule.getGroupId())
                .taggedMemos(memoResponses)
                .createdAt(schedule.getCreatedAt())
                .build();
    }
}
