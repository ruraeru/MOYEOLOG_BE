package com.moyeolog.moyelog_BE.notification;

import com.moyeolog.moyelog_BE.entity.User;
import com.moyeolog.moyelog_BE.event.ChangeEvent;
import com.moyeolog.moyelog_BE.event.LiveTopics;
import com.moyeolog.moyelog_BE.event.NotificationEvent;
import com.moyeolog.moyelog_BE.event.NotificationRetractEvent;
import com.moyeolog.moyelog_BE.event.UserDeletingEvent;
import com.moyeolog.moyelog_BE.repository.UserRepository;
import com.moyeolog.moyelog_BE.validation.CodePointSizeValidator;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class NotificationService {

    static final int MAX_PAGE_SIZE = 100;
    static final int MAX_MESSAGE_LENGTH = 300;
    static final int MAX_LINK_LENGTH = 300;
    static final String NOT_FOUND_MESSAGE = "알림을 찾을 수 없어요.";

    private final NotificationRepository notificationRepository;
    private final NotificationPreferenceRepository preferenceRepository;
    private final UserRepository userRepository;
    private final ApplicationEventPublisher eventPublisher;

    // ─── 조회·읽음·삭제 ───────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<NotificationResponse> getNotifications(UUID userId, int page, int size) {
        int safePage = Math.max(page, 0);
        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        return notificationRepository
                .findByRecipient_IdOrderByCreatedAtDescIdDesc(userId, PageRequest.of(safePage, safeSize))
                .stream().map(NotificationResponse::from).toList();
    }

    @Transactional(readOnly = true)
    public long getUnreadCount(UUID userId) {
        return notificationRepository.countByRecipient_IdAndReadAtIsNull(userId);
    }

    @Transactional
    public void markRead(UUID userId, UUID notificationId) {
        notificationRepository.findByIdAndRecipient_Id(notificationId, userId)
                .orElseThrow(() -> new NotFoundException(NOT_FOUND_MESSAGE))
                .markRead();
        publishChange(userId);
    }

    @Transactional
    public void markAllRead(UUID userId) {
        notificationRepository.markAllRead(userId, LocalDateTime.now());
        publishChange(userId);
    }

    @Transactional
    public void delete(UUID userId, UUID notificationId) {
        Notification notification = notificationRepository.findByIdAndRecipient_Id(notificationId, userId)
                .orElseThrow(() -> new NotFoundException(NOT_FOUND_MESSAGE));
        notificationRepository.delete(notification);
        publishChange(userId);
    }

    /** 커밋 뒤 실시간 갱신: 받는 사람의 알림 목록·배지 */
    private void publishChange(UUID recipientId) {
        eventPublisher.publishEvent(ChangeEvent.of(java.util.List.of(recipientId), LiveTopics.NOTIFICATIONS));
    }

    // ─── 설정 ────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public NotificationPreference getPreferenceEntity(UUID userId) {
        return preferenceRepository.findById(userId).orElseGet(() -> NotificationPreference.defaults(userId));
    }

    @Transactional(readOnly = true)
    public NotificationPreferenceDto getPreferences(UUID userId) {
        return NotificationPreferenceDto.from(getPreferenceEntity(userId));
    }

    @Transactional
    public NotificationPreferenceDto updatePreferences(UUID userId, NotificationPreferenceDto request) {
        NotificationPreference preference = preferenceRepository.findById(userId)
                .orElseGet(() -> NotificationPreference.defaults(userId));
        if (request != null) {
            preference.apply(request);
        }
        return NotificationPreferenceDto.from(preferenceRepository.save(preference));
    }

    // ─── 이벤트로 알림 만들기 ─────────────────────────────────────────

    /**
     * NotificationEventListener 가 원래 트랜잭션 커밋 뒤에 부른다. 새 트랜잭션에서 저장한다.
     * 자기 자신에게 가는 알림, 받는 사람이 꺼 둔 종류, 없는 사용자는 저장하지 않는다.
     *
     * @return 저장했으면 true
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean record(NotificationEvent event) {
        if (event == null || event.recipientId() == null || event.type() == null) return false;
        if (event.recipientId().equals(event.actorId())) return false;
        String message = event.message() == null ? "" : event.message().strip();
        if (message.isEmpty()) return false;

        User recipient = userRepository.findById(event.recipientId()).orElse(null);
        if (recipient == null) return false;
        if (!getPreferenceEntity(recipient.getId()).allows(event.type())) return false;

        User actor = event.actorId() == null ? null : userRepository.findById(event.actorId()).orElse(null);

        notificationRepository.save(Notification.builder()
                .recipient(recipient)
                .actor(actor)
                .type(event.type())
                .message(CodePointSizeValidator.truncate(message, MAX_MESSAGE_LENGTH))
                .link(safeLink(event.link()))
                .build());
        publishChange(recipient.getId());
        return true;
    }

    /**
     * NotificationEventListener 가 커밋 뒤에 부른다. 받는 사람의, 그 사람이 행동해서 생긴 그 종류 알림을 지운다.
     *
     * @return 지운 개수
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int retract(NotificationRetractEvent event) {
        if (event == null || event.recipientId() == null || event.type() == null) return 0;
        int deleted;
        if (event.link() != null) {
            deleted = notificationRepository.deleteByRecipientAndTypeAndLink(event.recipientId(), event.type(), event.link());
        } else if (event.actorId() != null) {
            deleted = notificationRepository.deleteByRecipientAndActorAndType(event.recipientId(), event.actorId(), event.type());
        } else {
            return 0;
        }
        if (deleted > 0) publishChange(event.recipientId());
        return deleted;
    }

    /** 앱 안의 경로("/..." 이고 "//" 로 시작하지 않음)만 링크로 남긴다. */
    static String safeLink(String link) {
        if (link == null) return null;
        String trimmed = link.strip();
        if (!trimmed.startsWith("/") || trimmed.startsWith("//") || trimmed.contains("\\")
                || trimmed.length() > MAX_LINK_LENGTH) {
            return null;
        }
        return trimmed;
    }

    // ─── 회원 탈퇴 ───────────────────────────────────────────────────

    /** 회원 탈퇴 직전(같은 트랜잭션): 내가 받은·내가 행동한 알림과 내 설정을 지운다. */
    @EventListener
    public void onUserDeleting(UserDeletingEvent event) {
        UUID userId = event.userId();
        notificationRepository.deleteAllInvolving(userId);
        preferenceRepository.findById(userId).ifPresent(preferenceRepository::delete);
        preferenceRepository.flush();
    }
}
