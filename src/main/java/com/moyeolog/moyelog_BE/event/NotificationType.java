package com.moyeolog.moyelog_BE.event;

/** 알림 종류. 프론트 계약(CONTRACTS.md 7절)과 같은 이름. */
public enum NotificationType {
    FRIEND_REQUEST, FRIEND_ACCEPTED, GROUP_INVITE, MEMO_SHARED, TOPIC_COMMENT, SCHEDULE_PARTICIPANT, GROUP_KICKED
}
