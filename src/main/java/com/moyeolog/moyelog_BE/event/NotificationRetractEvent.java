package com.moyeolog.moyelog_BE.event;

import java.util.UUID;

/**
 * 이미 남긴 알림이 더는 맞지 않을 때(예: 친구 요청이 취소·거절·수락됨) 발행한다.
 * notification 패키지의 리스너가 커밋 뒤(AFTER_COMMIT)에 받는 사람의 해당 종류·행동한 사람 알림을 지운다.
 *
 * @param recipientId 알림을 받았던 사람
 * @param actorId     알림을 만든 행동을 한 사람
 * @param type        지울 알림 종류
 * @param link        있으면 actorId 대신 이 링크의 알림만 지운다(예: 처리된 모임 초대)
 */
public record NotificationRetractEvent(UUID recipientId, UUID actorId, NotificationType type, String link) {

    /** 받는 사람의, 그 사람이 행동해서 생긴 그 종류 알림 전부 */
    public NotificationRetractEvent(UUID recipientId, UUID actorId, NotificationType type) {
        this(recipientId, actorId, type, null);
    }
}
