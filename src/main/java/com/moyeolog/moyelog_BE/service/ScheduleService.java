package com.moyeolog.moyelog_BE.service;

import com.moyeolog.moyelog_BE.dto.MemoResponse;
import com.moyeolog.moyelog_BE.dto.ScheduleRequest;
import com.moyeolog.moyelog_BE.dto.ScheduleResponse;
import com.moyeolog.moyelog_BE.entity.Memo;
import com.moyeolog.moyelog_BE.entity.Schedule;
import com.moyeolog.moyelog_BE.entity.User;
import com.moyeolog.moyelog_BE.exception.BadRequestException;
import com.moyeolog.moyelog_BE.exception.ResourceNotFoundException;
import com.moyeolog.moyelog_BE.exception.UnauthorizedAccessException;
import com.moyeolog.moyelog_BE.repository.FriendRepository;
import com.moyeolog.moyelog_BE.repository.GroupMemberRepository;
import com.moyeolog.moyelog_BE.repository.GroupRepository;
import com.moyeolog.moyelog_BE.repository.MemoRepository;
import com.moyeolog.moyelog_BE.repository.MemoShareRepository;
import com.moyeolog.moyelog_BE.repository.ScheduleRepository;
import com.moyeolog.moyelog_BE.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ScheduleService {

    private final ScheduleRepository scheduleRepository;
    private final UserRepository userRepository;
    private final GroupMemberRepository groupMemberRepository;
    private final MemoRepository memoRepository;
    private final GroupRepository groupRepository;
    private final FriendRepository friendRepository;
    private final MemoShareRepository memoShareRepository;

    @Transactional
    public ScheduleResponse createSchedule(UUID userId, ScheduleRequest request) {
        User author = findUserById(userId);

        validateTimes(request);
        if (request.getGroupId() != null) {
            requireGroupMember(request.getGroupId(), userId);
        }
        List<Memo> taggedMemos = resolveTaggedMemos(request.getTaggedMemoIds(), userId, Set.of());
        List<User> participants = resolveParticipants(request.getParticipantIds(), userId, request.getGroupId(), Set.of());

        Schedule schedule = Schedule.builder()
                .title(request.getTitle())
                .description(request.getDescription())
                .startTime(request.getStartTime())
                .endTime(request.getEndTime())
                .location(request.getLocation())
                .author(author)
                .groupId(request.getGroupId())
                .taggedMemos(taggedMemos)
                .participants(participants)
                .build();

        Schedule savedSchedule = scheduleRepository.save(schedule);
        return convertToResponse(savedSchedule);
    }

    @Transactional(readOnly = true)
    public List<ScheduleResponse> getSchedules(UUID userId) {
        User user = findUserById(userId);

        List<UUID> groupIds = groupMemberRepository.findByUser(user).stream()
                .map(m -> m.getGroup().getId())
                .distinct()
                .collect(Collectors.toList());

        List<Schedule> schedules;
        if (groupIds.isEmpty()) {
            schedules = scheduleRepository.findAllByUser(user);
        } else {
            schedules = scheduleRepository.findByUserOrGroupIds(user, groupIds);
        }

        return schedules.stream()
                .map(this::convertToResponse)
                .collect(Collectors.toList());
    }

    /** 그룹 일정 목록: 그룹 멤버 전용 (없는 그룹 404, 비멤버 403) */
    @Transactional(readOnly = true)
    public List<ScheduleResponse> getGroupSchedules(UUID userId, UUID groupId) {
        requireGroupMember(groupId, userId);
        return scheduleRepository.findAllByGroupIdOrderByStartTimeAsc(groupId).stream()
                .map(this::convertToResponse)
                .collect(Collectors.toList());
    }

    @Transactional
    public void deleteSchedule(UUID userId, UUID scheduleId) {
        Schedule schedule = findScheduleById(scheduleId);

        if (!schedule.getAuthor().getId().equals(userId)) {
            throw new UnauthorizedAccessException("일정 작성자만 삭제할 수 있습니다.");
        }

        scheduleRepository.delete(schedule);
    }

    @Transactional
    public ScheduleResponse updateSchedule(UUID userId, UUID scheduleId, ScheduleRequest request) {
        Schedule schedule = findScheduleById(scheduleId);

        if (!schedule.getAuthor().getId().equals(userId)) {
            throw new UnauthorizedAccessException("일정 작성자만 수정할 수 있습니다.");
        }

        validateTimes(request);

        // groupId 를 그대로 반영한다 (null 이면 개인 일정). 그룹 일정으로 두거나 옮기려면 그 그룹의 멤버여야 한다.
        UUID newGroupId = request.getGroupId();
        boolean groupChanged = !Objects.equals(schedule.getGroupId(), newGroupId);
        if (newGroupId != null) {
            requireGroupMember(newGroupId, userId);
        }

        Set<UUID> existingMemoIds = new HashSet<>();
        if (schedule.getTaggedMemos() != null) {
            schedule.getTaggedMemos().forEach(m -> existingMemoIds.add(m.getId()));
        }
        // 그룹이 바뀌지 않았다면 기존 참여자는 그대로 둘 수 있다 (예: 이후에 친구 관계가 끊어진 경우).
        Set<UUID> existingParticipantIds = new HashSet<>();
        if (!groupChanged && schedule.getParticipants() != null) {
            schedule.getParticipants().forEach(p -> existingParticipantIds.add(p.getId()));
        }

        List<Memo> taggedMemos = resolveTaggedMemos(request.getTaggedMemoIds(), userId, existingMemoIds);
        List<User> participants = resolveParticipants(request.getParticipantIds(), userId, newGroupId, existingParticipantIds);

        schedule.update(
                request.getTitle(),
                request.getDescription(),
                request.getStartTime(),
                request.getEndTime(),
                request.getLocation(),
                newGroupId,
                taggedMemos,
                participants
        );

        return convertToResponse(schedule);
    }

    // ─── Internal Helpers ──────────────────────────────────────────

    private User findUserById(UUID userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("사용자", userId));
    }

    private Schedule findScheduleById(UUID scheduleId) {
        return scheduleRepository.findById(scheduleId)
                .orElseThrow(() -> new ResourceNotFoundException("일정", scheduleId));
    }

    private void validateTimes(ScheduleRequest request) {
        if (request.getStartTime() == null || request.getEndTime() == null) {
            throw new BadRequestException("시작 시간과 종료 시간은 필수입니다.");
        }
        if (request.getEndTime().isBefore(request.getStartTime())) {
            throw new BadRequestException("종료 시간은 시작 시간보다 빠를 수 없습니다.");
        }
    }

    private void requireGroupMember(UUID groupId, UUID userId) {
        if (!groupRepository.existsById(groupId)) {
            throw new ResourceNotFoundException("그룹", groupId);
        }
        if (!groupMemberRepository.existsByGroup_IdAndUser_Id(groupId, userId)) {
            throw new UnauthorizedAccessException("해당 그룹의 멤버가 아닙니다.");
        }
    }

    /**
     * 참여자 검증.
     * - 그룹 일정: 참여자는 모두 그 그룹의 멤버여야 합니다.
     * - 개인 일정: 참여자는 작성자 본인이거나 작성자의 (수락된) 친구여야 합니다.
     * allowedExisting 에 들어 있는 사용자는 검증 없이 유지할 수 있습니다.
     */
    private List<User> resolveParticipants(List<UUID> participantIds, UUID authorId, UUID groupId, Set<UUID> allowedExisting) {
        List<User> participants = new ArrayList<>();
        if (participantIds == null || participantIds.isEmpty()) {
            return participants;
        }
        Set<UUID> ids = new LinkedHashSet<>(participantIds);
        ids.remove(null);

        for (UUID participantId : ids) {
            if (allowedExisting.contains(participantId)) continue;
            boolean allowed;
            if (groupId != null) {
                allowed = groupMemberRepository.existsByGroup_IdAndUser_Id(groupId, participantId);
            } else {
                allowed = participantId.equals(authorId) || friendRepository.areFriends(authorId, participantId);
            }
            if (!allowed) {
                throw new BadRequestException(groupId != null
                        ? "그룹 일정의 참여자는 그룹 멤버여야 합니다."
                        : "개인 일정의 참여자는 친구여야 합니다.");
            }
        }

        participants.addAll(userRepository.findAllById(ids));
        return participants;
    }

    /** 요청자가 읽을 수 있는 메모만 일정에 태그할 수 있습니다. 이미 태그되어 있던 메모는 유지할 수 있고, 없는 ID 는 무시합니다. */
    private List<Memo> resolveTaggedMemos(List<UUID> taggedMemoIds, UUID userId, Set<UUID> alreadyTagged) {
        List<Memo> result = new ArrayList<>();
        if (taggedMemoIds == null || taggedMemoIds.isEmpty()) {
            return result;
        }
        Set<UUID> ids = new LinkedHashSet<>(taggedMemoIds);
        ids.remove(null);
        for (Memo memo : memoRepository.findAllById(ids)) {
            if (!alreadyTagged.contains(memo.getId()) && !canReadMemo(memo, userId)) {
                throw new UnauthorizedAccessException("접근 권한이 없는 메모는 태그할 수 없습니다.");
            }
            result.add(memo);
        }
        return result;
    }

    private boolean canReadMemo(Memo memo, UUID userId) {
        if (memo.getAuthor().getId().equals(userId)) return true;
        if (memoShareRepository.existsByMemo_IdAndSharedTo_Id(memo.getId(), userId)) return true;
        return memo.getGroupId() != null
                && groupMemberRepository.existsByGroup_IdAndUser_Id(memo.getGroupId(), userId);
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

        List<ScheduleResponse.ParticipantResponse> participantResponses = new ArrayList<>();
        if (schedule.getParticipants() != null) {
            participantResponses = schedule.getParticipants().stream()
                .map(p -> ScheduleResponse.ParticipantResponse.builder()
                    .id(p.getId())
                    .nickname(p.getNickname())
                    .profileImage(p.getProfileImage())
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
                .participants(participantResponses)
                .createdAt(schedule.getCreatedAt())
                .build();
    }
}
