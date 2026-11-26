package com.moyeolog.moyelog_BE.event;

/** 알림 종류. 프론트 계약(CONTRACTS.md 7절)과 같은 이름. */
public enum NotificationType {
    FRIEND_REQUEST, FRIEND_ACCEPTED, GROUP_INVITE, MEMO_SHARED, TOPIC_COMMENT, SCHEDULE_PARTICIPANT, GROUP_KICKED,
    /** 모임 공지·투표 재촉. 설정 칸이 없어 항상 보낸다. */
    GROUP_NOTICE
}
