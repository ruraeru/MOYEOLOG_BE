package com.moyeolog.moyelog_BE.notice;

import com.moyeolog.moyelog_BE.entity.Group;
import com.moyeolog.moyelog_BE.entity.GroupMember;
import com.moyeolog.moyelog_BE.entity.User;
import com.moyeolog.moyelog_BE.event.ChangeEvent;
import com.moyeolog.moyelog_BE.event.LiveTopics;
import com.moyeolog.moyelog_BE.event.NotificationEvent;
import com.moyeolog.moyelog_BE.event.NotificationType;
import com.moyeolog.moyelog_BE.exception.UnauthorizedAccessException;
import com.moyeolog.moyelog_BE.repository.GroupMemberRepository;
import com.moyeolog.moyelog_BE.repository.GroupRepository;
import com.moyeolog.moyelog_BE.service.ItemNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 공지·투표 서비스가 함께 쓰는 권한 검사, 멤버 목록, 실시간 갱신, 알림.
 * 호출하는 쪽의 트랜잭션 안에서 부른다(멤버의 User 를 지연 로딩한다).
 */
@Component
@RequiredArgsConstructor
public class NoticeAccess {

    static final String NOT_FOUND = "공지를 찾을 수 없어요. 삭제됐을 수 있어요.";

    private final GroupRepository groupRepository;
    private final GroupMemberRepository groupMemberRepository;
    private final GroupNoticeRepository noticeRepository;
    private final ApplicationEventPublisher eventPublisher;

    /** 모임이 없으면 404, 멤버가 아니면 403 */
    public Group requireMember(UUID groupId, UUID userId) {
        Group group = groupRepository.findById(groupId)
                .orElseThrow(() -> new ItemNotFoundException("모임을 찾을 수 없어요."));
        if (!groupMemberRepository.existsByGroup_IdAndUser_Id(groupId, userId)) {
            throw new UnauthorizedAccessException("모임 멤버만 할 수 있어요.");
        }
        return group;
    }

    /** 방장이 아니면 403 */
    public void requireOwner(Group group, UUID userId, String message) {
        if (!isOwner(group, userId)) {
            throw new UnauthorizedAccessException(message);
        }
    }

    public boolean isOwner(Group group, UUID userId) {
        return group.getCreatedBy().getId().equals(userId);
    }

    /** 없거나 다른 모임의 공지면 404. forUpdate 면 공지 행을 잠근다. */
    public GroupNotice findNotice(UUID groupId, UUID noticeId, boolean forUpdate) {
        GroupNotice notice = (forUpdate ? noticeRepository.findByIdForUpdate(noticeId) : noticeRepository.findById(noticeId))
                .orElseThrow(() -> new ItemNotFoundException(NOT_FOUND));
        if (!notice.getGroup().getId().equals(groupId)) {
            throw new ItemNotFoundException(NOT_FOUND);
        }
        return notice;
    }

    /** 지금 모임 멤버(가입 순서, 중복 행 제거) */
    public List<User> members(UUID groupId) {
        Map<UUID, User> members = new LinkedHashMap<>();
        for (GroupMember m : groupMemberRepository.findAllByGroup_IdOrderByJoinedAtAsc(groupId)) {
            members.putIfAbsent(m.getUser().getId(), m.getUser());
        }
        return new ArrayList<>(members.values());
    }

    /** 커밋 뒤 실시간 갱신: 받는 사람에게 group:{id} */
    public void publishChange(UUID groupId, Collection<User> recipients) {
        eventPublisher.publishEvent(ChangeEvent.of(recipients.stream().map(User::getId).toList(), LiveTopics.group(groupId)));
    }

    /** 모임 공지 알림(설정과 관계없이 보낸다). 행동한 본인은 빼고, 누르면 그 모임의 공지 탭에서 그 공지를 연다. */
    public void notify(Collection<User> recipients, UUID actorId, UUID groupId, UUID noticeId, String message) {
        String link = "/groups/" + groupId + "?tab=notice&notice=" + noticeId;
        for (User recipient : recipients) {
            if (recipient.getId().equals(actorId)) continue;
            eventPublisher.publishEvent(new NotificationEvent(recipient.getId(), actorId,
                    NotificationType.GROUP_NOTICE, message, link));
        }
    }
}
