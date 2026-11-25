package com.moyeolog.moyelog_BE.service;

import com.moyeolog.moyelog_BE.dto.MemoResponse;
import com.moyeolog.moyelog_BE.dto.ScheduleRequest;
import com.moyeolog.moyelog_BE.dto.ScheduleResponse;
import com.moyeolog.moyelog_BE.entity.Memo;
import com.moyeolog.moyelog_BE.entity.Schedule;
import com.moyeolog.moyelog_BE.entity.User;
import com.moyeolog.moyelog_BE.event.NotificationEvent;
import com.moyeolog.moyelog_BE.event.NotificationType;
import com.moyeolog.moyelog_BE.event.UserDeletingEvent;
import com.moyeolog.moyelog_BE.exception.BadRequestException;
import com.moyeolog.moyelog_BE.exception.UnauthorizedAccessException;
import com.moyeolog.moyelog_BE.midpoint.ScheduleDepartureRepository;
import com.moyeolog.moyelog_BE.repository.FriendRepository;
import com.moyeolog.moyelog_BE.repository.GroupMemberRepository;
import com.moyeolog.moyelog_BE.repository.GroupRepository;
import com.moyeolog.moyelog_BE.repository.MemoRepository;
import com.moyeolog.moyelog_BE.repository.MemoShareRepository;
import com.moyeolog.moyelog_BE.repository.ScheduleRepository;
import com.moyeolog.moyelog_BE.repository.UserRepository;
import com.moyeolog.moyelog_BE.validation.CodePointSizeValidator;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
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
    private final ScheduleDepartureRepository scheduleDepartureRepository;
    private final ApplicationEventPublisher eventPublisher;

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
                .latitude(latitudeOf(request))
                .longitude(longitudeOf(request))
                .author(author)
                .groupId(request.getGroupId())
                .taggedMemos(taggedMemos)
                .participants(participants)
                .build();

        Schedule savedSchedule = scheduleRepository.save(schedule);
        notifyNewParticipants(savedSchedule, author, Set.of());
        return convertToResponse(savedSchedule, userId);
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

        DepartureLookup lookup = new DepartureLookup(userId);
        return schedules.stream()
                .map(schedule -> convertToResponse(schedule, userId, lookup))
                .collect(Collectors.toList());
    }

    /** 그룹 일정 목록: 그룹 멤버 전용 (없는 그룹 404, 비멤버 403) */
    @Transactional(readOnly = true)
    public List<ScheduleResponse> getGroupSchedules(UUID userId, UUID groupId) {
        requireGroupMember(groupId, userId);
        DepartureLookup lookup = new DepartureLookup(userId);
        return scheduleRepository.findAllByGroupIdOrderByStartTimeAsc(groupId).stream()
                .map(schedule -> convertToResponse(schedule, userId, lookup))
                .collect(Collectors.toList());
    }

    @Transactional
    public void deleteSchedule(UUID userId, UUID scheduleId) {
        // 동시 삭제는 행 잠금으로 줄 세운다: 먼저 지운 쪽만 204, 나머지는 404.
        Schedule schedule = findScheduleForUpdate(scheduleId);

        if (!schedule.getAuthor().getId().equals(userId)) {
            throw new UnauthorizedAccessException("일정을 만든 사람만 삭제할 수 있어요.");
        }

        scheduleRepository.delete(schedule);
        ConcurrentUpdates.flush(scheduleRepository::flush);
    }

    @Transactional
    public ScheduleResponse updateSchedule(UUID userId, UUID scheduleId, ScheduleRequest request) {
        Schedule schedule = findScheduleForUpdate(scheduleId);

        if (!schedule.getAuthor().getId().equals(userId)) {
            throw new UnauthorizedAccessException("일정을 만든 사람만 수정할 수 있어요.");
        }

        validateTimes(request);

        // groupId 를 그대로 반영한다 (null 이면 개인 일정). 그룹 일정으로 두거나 옮기려면 그 그룹의 멤버여야 한다.
        UUID newGroupId = request.getGroupId();
        UUID oldGroupId = schedule.getGroupId();
        if (oldGroupId != null && !groupMemberRepository.existsByGroup_IdAndUser_Id(oldGroupId, userId)) {
            // 모임 일정은 현재 모임 멤버만 고칠 수 있다 (나간·강퇴된 작성자도 불가). 삭제는 작성자에게 허용.
            throw new UnauthorizedAccessException("모임을 나가서 이 모임 일정은 고칠 수 없어요. 삭제는 할 수 있어요.");
        }
        if (newGroupId != null) {
            requireGroupMember(newGroupId, userId);
        }

        Set<UUID> existingMemoIds = new HashSet<>();
        if (schedule.getTaggedMemos() != null) {
            schedule.getTaggedMemos().forEach(m -> existingMemoIds.add(m.getId()));
        }
        Set<UUID> existingParticipantIds = new HashSet<>();
        if (schedule.getParticipants() != null) {
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
                latitudeOf(request),
                longitudeOf(request),
                newGroupId,
                taggedMemos,
                participants
        );
        ConcurrentUpdates.flush(scheduleRepository::flush);

        notifyNewParticipants(schedule, schedule.getAuthor(), existingParticipantIds);
        return convertToResponse(schedule, userId);
    }

    /** 회원 탈퇴: 내가 만든 일정을 지우고, 다른 사람 일정의 참여자에서 뺀다. */
    @EventListener
    @Transactional
    public void onUserDeleting(UserDeletingEvent event) {
        UUID userId = event.userId();
        for (Schedule schedule : scheduleRepository.findAllByParticipantId(userId)) {
            schedule.getParticipants().removeIf(p -> p.getId().equals(userId));
        }
        scheduleRepository.deleteAll(scheduleRepository.findAllByAuthorId(userId));
        scheduleRepository.flush();
    }

    // ─── Internal Helpers ──────────────────────────────────────────

    private User findUserById(UUID userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new ItemNotFoundException("사용자를 찾을 수 없어요."));
    }

    private Schedule findScheduleForUpdate(UUID scheduleId) {
        return scheduleRepository.findByIdForUpdate(scheduleId)
                .orElseThrow(() -> new ItemNotFoundException("일정을 찾을 수 없어요. 삭제됐을 수 있어요."));
    }

    private void notifyNewParticipants(Schedule schedule, User author, Set<UUID> previousParticipantIds) {
        if (schedule.getParticipants() == null) return;
        String title = schedule.getTitle() == null ? "" : schedule.getTitle().trim();
        if (CodePointSizeValidator.length(title) > 30) {
            title = CodePointSizeValidator.truncate(title, 30) + "…";
        }
        String message = author.getNickname() + "님이 '" + title + "' 일정에 참여자로 추가했어요.";
        // 장소가 아직 없으면 중간 지점을 찾을 수 있게 출발지를 적어 달라고 덧붙인다.
        if (isBlank(schedule.getLocation())) {
            message += " 출발지를 입력해 주세요!";
        }
        for (User participant : schedule.getParticipants()) {
            if (participant.getId().equals(author.getId()) || previousParticipantIds.contains(participant.getId())) {
                continue;
            }
            eventPublisher.publishEvent(new NotificationEvent(participant.getId(), author.getId(),
                    NotificationType.SCHEDULE_PARTICIPANT, message, "/schedule/" + schedule.getId()));
        }
    }

    /** 좌표는 장소 이름과 위도·경도가 모두 있을 때만 남긴다(장소를 지우거나 한쪽만 오면 버림). */
    private static Double latitudeOf(ScheduleRequest request) {
        return hasCoordinates(request) ? request.getLatitude() : null;
    }

    private static Double longitudeOf(ScheduleRequest request) {
        return hasCoordinates(request) ? request.getLongitude() : null;
    }

    private static boolean hasCoordinates(ScheduleRequest request) {
        return request.getLocation() != null && !request.getLocation().isBlank()
                && request.getLatitude() != null && request.getLongitude() != null;
    }

    private void validateTimes(ScheduleRequest request) {
        if (request.getStartTime() == null || request.getEndTime() == null) {
            throw new BadRequestException("시작 시각과 종료 시각을 입력해 주세요.");
        }
        if (request.getEndTime().isBefore(request.getStartTime())) {
            throw new BadRequestException("종료 시각은 시작 시각보다 빠를 수 없어요.");
        }
    }

    private void requireGroupMember(UUID groupId, UUID userId) {
        if (!groupRepository.existsById(groupId)) {
            throw new ItemNotFoundException("모임을 찾을 수 없어요.");
        }
        if (!groupMemberRepository.existsByGroup_IdAndUser_Id(groupId, userId)) {
            throw new UnauthorizedAccessException("모임 멤버만 할 수 있어요.");
        }
    }

    /**
     * 참여자 검증.
     * - 그룹 일정: 참여자는 모두 그 그룹의 멤버여야 합니다.
     * - 개인 일정: 참여자는 작성자 본인이거나 작성자의 (수락된) 친구여야 합니다.
     * 이미 참여자였던 사람이 지금 규칙에 맞지 않으면(친구를 끊음, 모임을 나감) 조용히 빼고,
     * 새로 추가하려는 사람이 맞지 않으면 400 입니다.
     */
    private List<User> resolveParticipants(List<UUID> participantIds, UUID authorId, UUID groupId, Set<UUID> allowedExisting) {
        List<User> participants = new ArrayList<>();
        if (participantIds == null || participantIds.isEmpty()) {
            return participants;
        }
        Set<UUID> ids = new LinkedHashSet<>(participantIds);
        ids.remove(null);

        Set<UUID> accepted = new LinkedHashSet<>();
        for (UUID participantId : ids) {
            boolean allowed;
            if (groupId != null) {
                allowed = groupMemberRepository.existsByGroup_IdAndUser_Id(groupId, participantId);
            } else {
                allowed = participantId.equals(authorId) || friendRepository.areFriends(authorId, participantId);
            }
            if (allowed) {
                accepted.add(participantId);
            } else if (!allowedExisting.contains(participantId)) {
                throw new BadRequestException(groupId != null
                        ? "모임 일정의 참여자는 모임 멤버여야 해요."
                        : "개인 일정의 참여자는 친구여야 해요.");
            }
        }

        if (!accepted.isEmpty()) {
            participants.addAll(userRepository.findAllById(accepted));
        }
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
                throw new UnauthorizedAccessException("볼 수 없는 메모는 연결할 수 없어요.");
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

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /** 한 번의 목록 응답 안에서 '출발지 미입력' 판단에 쓰는 조회를 모아 둔다 (일정마다 다시 묻지 않게). */
    private final class DepartureLookup {
        private final UUID viewerId;
        private Set<UUID> entered;
        private final Map<UUID, Long> groupSizes = new HashMap<>();
        private final Map<UUID, Boolean> memberships = new HashMap<>();
        private final LocalDateTime now = LocalDateTime.now();

        private DepartureLookup(UUID viewerId) {
            this.viewerId = viewerId;
        }

        /** 여럿이 만나고, 장소가 비었고, 아직 끝나지 않았고, 내가 대상인데 출발지를 안 적은 일정 */
        private boolean needed(Schedule schedule) {
            if (!isBlank(schedule.getLocation())) return false;
            if (schedule.getEndTime() != null && schedule.getEndTime().isBefore(now)) return false;

            UUID groupId = schedule.getGroupId();
            if (groupId != null) {
                if (!memberships.computeIfAbsent(groupId,
                        id -> groupMemberRepository.existsByGroup_IdAndUser_Id(id, viewerId))) return false;
                if (groupSizes.computeIfAbsent(groupId, groupMemberRepository::countByGroup_Id) < 2) return false;
            } else {
                Set<UUID> people = new HashSet<>();
                people.add(schedule.getAuthor().getId());
                if (schedule.getParticipants() != null) {
                    schedule.getParticipants().forEach(p -> people.add(p.getId()));
                }
                if (people.size() < 2 || !people.contains(viewerId)) return false;
            }

            if (entered == null) {
                entered = scheduleDepartureRepository.findScheduleIdsByUserId(viewerId);
            }
            return !entered.contains(schedule.getId());
        }
    }

    private ScheduleResponse convertToResponse(Schedule schedule, UUID viewerId) {
        return convertToResponse(schedule, viewerId, new DepartureLookup(viewerId));
    }

    /** 연결된 메모는 요청한 사용자가 열 수 있는 것만 담는다 (비공개 메모 제목 노출 방지). */
    private ScheduleResponse convertToResponse(Schedule schedule, UUID viewerId, DepartureLookup lookup) {
        List<MemoResponse> memoResponses = new ArrayList<>();
        if (schedule.getTaggedMemos() != null) {
            memoResponses = schedule.getTaggedMemos().stream()
                .filter(m -> canReadMemo(m, viewerId))
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
                .latitude(schedule.getLatitude())
                .longitude(schedule.getLongitude())
                .authorId(schedule.getAuthor().getId())
                .authorNickname(schedule.getAuthor().getNickname())
                .groupId(schedule.getGroupId())
                .taggedMemos(memoResponses)
                .participants(participantResponses)
                .createdAt(schedule.getCreatedAt())
                .departureNeeded(lookup.needed(schedule))
                .build();
    }
}
