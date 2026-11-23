package com.moyeolog.moyelog_BE.notification;

import com.moyeolog.moyelog_BE.event.NotificationEvent;
import com.moyeolog.moyelog_BE.event.NotificationRetractEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 도메인 서비스가 발행한 NotificationEvent 를 발행한 트랜잭션이 커밋된 뒤에 받아 저장한다.
 * 저장은 NotificationService.record 의 새 트랜잭션(REQUIRES_NEW)에서 한다.
 * 롤백되면 알림을 만들지 않는다. 트랜잭션 밖에서 발행하면 바로 처리한다(fallbackExecution).
 * 알림 저장이 실패해도 원래 요청은 이미 커밋됐으므로 예외를 던지지 않고 로그만 남긴다.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class NotificationEventListener {

    private final NotificationService notificationService;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onNotification(NotificationEvent event) {
        try {
            notificationService.record(event);
        } catch (RuntimeException e) {
            log.warn("알림 저장 실패: type={}, error={}", event.type(), e.getClass().getSimpleName(), e);
        }
    }

    /** 더는 맞지 않는 알림(취소·거절·수락된 친구 요청 등)을 커밋 뒤에 지운다. 트랜잭션 밖이면 바로 지운다. */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onRetract(NotificationRetractEvent event) {
        try {
            notificationService.retract(event);
        } catch (RuntimeException e) {
            log.warn("알림 정리 실패: type={}, error={}", event.type(), e.getClass().getSimpleName(), e);
        }
    }
}
