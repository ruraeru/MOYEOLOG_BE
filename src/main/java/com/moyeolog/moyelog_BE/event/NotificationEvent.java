package com.moyeolog.moyelog_BE.event;

import java.util.UUID;

/**
 * 도메인 서비스가 알림을 남기고 싶을 때 ApplicationEventPublisher 로 발행한다.
 * 알림 저장·설정 확인은 notification 패키지의 리스너가 커밋 뒤(AFTER_COMMIT)에 처리한다.
 * 발행하는 쪽은 알림 테이블을 몰라도 된다.
 *
 * @param recipientId 받는 사람 (actorId 와 같으면 리스너가 무시)
 * @param actorId     행동한 사람 (없으면 null)
 * @param type        알림 종류
 * @param message     사용자에게 보일 해요체 문장 (예: "김민지님이 친구 요청을 보냈어요.")
 * @param link        누르면 이동할 프론트 경로 (예: "/friends")
 */
public record NotificationEvent(UUID recipientId, UUID actorId, NotificationType type, String message, String link) {
}
