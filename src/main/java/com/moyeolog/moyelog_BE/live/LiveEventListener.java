package com.moyeolog.moyelog_BE.live;

import com.moyeolog.moyelog_BE.entity.Friend;
import com.moyeolog.moyelog_BE.entity.GroupMember;
import com.moyeolog.moyelog_BE.entity.Schedule;
import com.moyeolog.moyelog_BE.event.ChangeEvent;
import com.moyeolog.moyelog_BE.event.ChangeEvents;
import com.moyeolog.moyelog_BE.event.LiveCloseEvent;
import com.moyeolog.moyelog_BE.event.LiveTopics;
import com.moyeolog.moyelog_BE.event.UserDeletingEvent;
import com.moyeolog.moyelog_BE.repository.FriendRepository;
import com.moyeolog.moyelog_BE.repository.GroupMemberRepository;
import com.moyeolog.moyelog_BE.repository.ScheduleRepository;
import com.moyeolog.moyelog_BE.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/**
 * 도메인 서비스가 발행한 ChangeEvent·LiveCloseEvent 를 발행한 트랜잭션이 커밋된 뒤에 연결로 보낸다.
 * 롤백되면 보내지 않는다. 트랜잭션 밖에서 발행하면 바로 보낸다(fallbackExecution).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class LiveEventListener {

    private final LiveHub liveHub;
    private final FriendRepository friendRepository;
    private final GroupMemberRepository groupMemberRepository;
    private final UserRepository userRepository;
    private final ScheduleRepository scheduleRepository;
    private final ApplicationEventPublisher eventPublisher;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onChange(ChangeEvent event) {
        try {
            liveHub.publish(event.recipients(), event.topics());
        } catch (RuntimeException e) {
            // 원래 요청은 이미 커밋됐다. 놓친 변경은 클라이언트의 안전망 폴링이 메운다.
            log.warn("실시간 알림 실패: topics={}, error={}", event.topics(), e.getClass().getSimpleName());
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onClose(LiveCloseEvent event) {
        liveHub.close(event.userId(), event.jti());
    }

    /**
     * 회원 탈퇴 직전(같은 트랜잭션, 다른 리스너가 데이터를 지우기 전): 그 사람과 얽힌 사람들에게 다시 불러오라고 알리고,
     * 탈퇴한 사람의 연결을 닫는다. 둘 다 커밋 뒤에 나간다.
     * - 친구·요청 상대: friends, memos, schedules, messages (메모 공유·개인 일정 참여·메시지는 친구 사이에만 있다)
     * - 같은 모임 멤버: groups, group:{id}, memos, schedules (모임 토픽은 그 모임 멤버에게만)
     * - 탈퇴자가 만들었거나 참여한 일정을 보던 사람: schedules, schedule:{id}
     */
    @EventListener
    @Order(0)
    public void onUserDeleting(UserDeletingEvent event) {
        UUID userId = event.userId();
        ChangeEvents changes = new ChangeEvents();
        Set<UUID> friends = new LinkedHashSet<>();
        for (Friend friend : friendRepository.findAllInvolving(userId)) {
            friends.add(friend.getRequester().getId());
            friends.add(friend.getReceiver().getId());
        }
        changes.add(friends, LiveTopics.FRIENDS, LiveTopics.MEMOS, LiveTopics.SCHEDULES, LiveTopics.MESSAGES);
        userRepository.findById(userId).ifPresent(user -> {
            for (GroupMember membership : groupMemberRepository.findByUser(user)) {
                UUID groupId = membership.getGroup().getId();
                changes.add(groupMemberRepository.findUserIdsByGroupId(groupId), LiveTopics.GROUPS,
                        LiveTopics.group(groupId), LiveTopics.MEMOS, LiveTopics.SCHEDULES);
            }
        });
        for (Schedule schedule : scheduleRepository.findAllByAuthorId(userId)) {
            LiveAudience.addSchedule(changes, schedule, groupMemberRepository);
        }
        for (Schedule schedule : scheduleRepository.findAllByParticipantId(userId)) {
            LiveAudience.addSchedule(changes, schedule, groupMemberRepository);
        }
        // 탈퇴한 사람 자신의 연결은 닫으므로 보내지 않는다
        changes.build().forEach(change -> {
            Set<UUID> recipients = new LinkedHashSet<>(change.recipients());
            recipients.remove(userId);
            if (!recipients.isEmpty()) eventPublisher.publishEvent(new ChangeEvent(recipients, change.topics()));
        });
        eventPublisher.publishEvent(new LiveCloseEvent(userId, null));
    }
}
