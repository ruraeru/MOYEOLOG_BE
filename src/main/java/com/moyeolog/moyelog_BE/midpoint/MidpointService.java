package com.moyeolog.moyelog_BE.midpoint;

import com.moyeolog.moyelog_BE.entity.Schedule;
import com.moyeolog.moyelog_BE.entity.User;
import com.moyeolog.moyelog_BE.event.ChangeEvents;
import com.moyeolog.moyelog_BE.live.LiveAudience;
import com.moyeolog.moyelog_BE.event.NotificationEvent;
import com.moyeolog.moyelog_BE.event.NotificationType;
import com.moyeolog.moyelog_BE.event.UserDeletingEvent;
import com.moyeolog.moyelog_BE.exception.UnauthorizedAccessException;
import com.moyeolog.moyelog_BE.midpoint.MidpointDtos.*;
import com.moyeolog.moyelog_BE.repository.GroupMemberRepository;
import com.moyeolog.moyelog_BE.repository.ScheduleRepository;
import com.moyeolog.moyelog_BE.repository.UserRepository;
import com.moyeolog.moyelog_BE.service.ItemNotFoundException;
import com.moyeolog.moyelog_BE.validation.CodePointSizeValidator;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 일정의 중간 지점 찾기.
 * - 출발지를 적을 수 있는 사람: 모임 일정은 모임 멤버, 개인 일정은 만든 사람과 참여자.
 * - 가운데 좌표는 적힌 출발지 좌표의 평균이다 (도시 안 거리에서는 평균으로 충분하다).
 * - 다른 사람의 출발지는 이름만 내려준다.
 */
@Service
@RequiredArgsConstructor
public class MidpointService {

    private final ScheduleDepartureRepository departureRepository;
    private final ScheduleRepository scheduleRepository;
    private final GroupMemberRepository groupMemberRepository;
    private final UserRepository userRepository;
    private final ApplicationEventPublisher eventPublisher;

    @Transactional(readOnly = true)
    public MidpointResponse get(UUID userId, UUID scheduleId) {
        Schedule schedule = findSchedule(scheduleId);
        return respond(schedule, requireEligible(schedule, userId), userId);
    }

    @Transactional
    public MidpointResponse saveDeparture(UUID userId, UUID scheduleId, DepartureRequest request) {
        // 같은 사람이 동시에 두 번 저장해도 한 줄만 남도록 일정 행을 잠근다.
        Schedule schedule = findScheduleForUpdate(scheduleId);
        Set<UUID> eligible = requireEligible(schedule, userId);
        String name = request.name().trim();

        ScheduleDeparture departure = departureRepository.findBySchedule_IdAndUser_Id(scheduleId, userId).orElse(null);
        if (departure == null) {
            User user = userRepository.findById(userId)
                    .orElseThrow(() -> new ItemNotFoundException("사용자를 찾을 수 없어요."));
            departureRepository.save(ScheduleDeparture.builder()
                    .schedule(schedule).user(user)
                    .name(name).latitude(request.latitude()).longitude(request.longitude())
                    .build());
        } else {
            departure.change(name, request.latitude(), request.longitude());
        }
        departureRepository.flush();
        publishChange(schedule);
        return respond(schedule, eligible, userId);
    }

    @Transactional
    public MidpointResponse removeDeparture(UUID userId, UUID scheduleId) {
        Schedule schedule = findScheduleForUpdate(scheduleId);
        Set<UUID> eligible = requireEligible(schedule, userId);
        departureRepository.findBySchedule_IdAndUser_Id(scheduleId, userId).ifPresent(departureRepository::delete);
        departureRepository.flush();
        publishChange(schedule);
        return respond(schedule, eligible, userId);
    }

    @Transactional
    public RequestResult requestDepartures(UUID userId, UUID scheduleId) {
        Schedule schedule = findSchedule(scheduleId);
        Set<UUID> eligible = requireEligible(schedule, userId);
        requireAuthor(schedule, userId, "일정을 만든 사람만 출발지를 물어볼 수 있어요.");

        Set<UUID> entered = new LinkedHashSet<>();
        departureRepository.findAllBySchedule(scheduleId).forEach(d -> entered.add(d.getUser().getId()));

        String message = schedule.getAuthor().getNickname() + "님이 '" + shortTitle(schedule)
                + "' 일정의 중간 지점을 찾고 있어요. 출발지를 적어 주세요.";
        int sent = 0;
        for (UUID recipient : eligible) {
            if (recipient.equals(userId) || entered.contains(recipient)) continue;
            eventPublisher.publishEvent(new NotificationEvent(recipient, userId,
                    NotificationType.SCHEDULE_PARTICIPANT, message, "/schedule/" + scheduleId));
            sent++;
        }
        return new RequestResult(sent);
    }

    @Transactional
    public MidpointResponse confirm(UUID userId, UUID scheduleId, ConfirmRequest request) {
        Schedule schedule = findScheduleForUpdate(scheduleId);
        Set<UUID> eligible = requireEligible(schedule, userId);
        requireAuthor(schedule, userId, "일정을 만든 사람만 장소를 정할 수 있어요.");

        String place = request.name().trim();
        boolean hasCoords = request.latitude() != null && request.longitude() != null;
        schedule.changeLocation(place, hasCoords ? request.latitude() : null, hasCoords ? request.longitude() : null);
        scheduleRepository.flush();
        publishChange(schedule);

        String message = schedule.getAuthor().getNickname() + "님이 '" + shortTitle(schedule)
                + "' 일정 장소를 정했어요: " + CodePointSizeValidator.truncate(place, 40);
        for (UUID recipient : eligible) {
            if (recipient.equals(userId)) continue;
            eventPublisher.publishEvent(new NotificationEvent(recipient, userId,
                    NotificationType.SCHEDULE_PARTICIPANT, message, "/schedule/" + scheduleId));
        }
        return respond(schedule, eligible, userId);
    }

    /** 회원 탈퇴: 그 사람이 적은 출발지를 지운다. */
    @EventListener
    @Transactional
    public void onUserDeleting(UserDeletingEvent event) {
        departureRepository.deleteAllByUserId(event.userId());
    }

    // ─── Internal Helpers ──────────────────────────────────────────

    private Schedule findSchedule(UUID scheduleId) {
        return scheduleRepository.findById(scheduleId)
                .orElseThrow(() -> new ItemNotFoundException("일정을 찾을 수 없어요. 삭제됐을 수 있어요."));
    }

    private Schedule findScheduleForUpdate(UUID scheduleId) {
        return scheduleRepository.findByIdForUpdate(scheduleId)
                .orElseThrow(() -> new ItemNotFoundException("일정을 찾을 수 없어요. 삭제됐을 수 있어요."));
    }

    /** 이 일정에 출발지를 적을 수 있는 사람들. 요청한 사람이 그 안에 없으면 403. */
    private Set<UUID> requireEligible(Schedule schedule, UUID userId) {
        Set<UUID> eligible = new LinkedHashSet<>();
        if (schedule.getGroupId() != null) {
            eligible.addAll(groupMemberRepository.findUserIdsByGroupId(schedule.getGroupId()));
        } else {
            eligible.add(schedule.getAuthor().getId());
            if (schedule.getParticipants() != null) {
                schedule.getParticipants().forEach(p -> eligible.add(p.getId()));
            }
        }
        if (!eligible.contains(userId)) {
            throw new UnauthorizedAccessException(schedule.getGroupId() != null
                    ? "모임 멤버만 할 수 있어요."
                    : "일정을 만든 사람과 참여자만 할 수 있어요.");
        }
        return eligible;
    }

    /**
     * 커밋 뒤 실시간 갱신: 출발지·장소가 바뀌면 그 일정과 일정 목록(출발지 미입력·장소 미정 표시), 모임 일정이면 모임 달력.
     * 출발지 대상자(eligible)가 아니라 그 일정을 볼 수 있는 사람 전부다: 모임을 나간 작성자도 자기 일정은 본다.
     */
    private void publishChange(Schedule schedule) {
        LiveAudience.addSchedule(new ChangeEvents(), schedule, groupMemberRepository).publish(eventPublisher);
    }

    private void requireAuthor(Schedule schedule, UUID userId, String message) {
        if (!schedule.getAuthor().getId().equals(userId)) {
            throw new UnauthorizedAccessException(message);
        }
    }

    private String shortTitle(Schedule schedule) {
        String title = schedule.getTitle() == null ? "" : schedule.getTitle().trim();
        return CodePointSizeValidator.length(title) > 30 ? CodePointSizeValidator.truncate(title, 30) + "…" : title;
    }

    private MidpointResponse respond(Schedule schedule, Set<UUID> eligible, UUID viewerId) {
        // 모임을 나갔거나 참여자에서 빠진 사람의 출발지는 계산에서 뺀다.
        List<ScheduleDeparture> departures = departureRepository.findAllBySchedule(schedule.getId()).stream()
                .filter(d -> eligible.contains(d.getUser().getId()))
                .toList();

        List<Entry> entries = departures.stream()
                .map(d -> new Entry(d.getUser().getId(), d.getUser().getNickname(), d.getUser().getProfileImage(),
                        d.getName(), d.getUser().getId().equals(viewerId)))
                .toList();

        Place mine = departures.stream()
                .filter(d -> d.getUser().getId().equals(viewerId))
                .findFirst().map(MidpointService::toPlace).orElse(null);

        Place last = departureRepository
                .findFirstByUser_IdAndSchedule_IdNotOrderByUpdatedAtDesc(viewerId, schedule.getId())
                .map(MidpointService::toPlace).orElse(null);

        Center center = null;
        if (departures.size() >= 2) {
            center = new Center(
                    departures.stream().mapToDouble(ScheduleDeparture::getLatitude).average().orElse(0),
                    departures.stream().mapToDouble(ScheduleDeparture::getLongitude).average().orElse(0));
        }

        return new MidpointResponse(entries, mine, last, center, departures.size(), eligible.size(),
                schedule.getAuthor().getId().equals(viewerId), schedule.getLocation());
    }

    private static Place toPlace(ScheduleDeparture d) {
        return new Place(d.getName(), d.getLatitude(), d.getLongitude());
    }
}
