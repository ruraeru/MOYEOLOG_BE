package com.moyeolog.moyelog_BE.event;

import java.util.UUID;

/**
 * 이미 남긴 알림이 더는 맞지 않을 때(예: 친구 요청이 취소·거절·수락됨) 발행한다.
 * notification 패키지의 리스너가 커밋 뒤(AFTER_COMMIT)에 받는 사람의 해당 종류·행동한 사람 알림을 지운다.
 *
 * @param recipientId 알림을 받았던 사람
 * @param actorId     알림을 만든 행동을 한 사람
 * @param type        지울 알림 종류
 */
public record NotificationRetractEvent(UUID recipientId, UUID actorId, NotificationType type) {
}
