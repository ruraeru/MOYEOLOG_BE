package com.moyeolog.moyelog_BE.event;

import java.util.UUID;

/** 실시간 갱신 토픽 이름. 프론트와의 계약(LIVE_BRIEF.md '계약')과 같은 문자열이다. */
public final class LiveTopics {

    public static final String SCHEDULES = "schedules";
    public static final String MEMOS = "memos";
    public static final String FRIENDS = "friends";
    public static final String GROUPS = "groups";
    public static final String MESSAGES = "messages";
    public static final String NOTIFICATIONS = "notifications";

    private LiveTopics() {
    }

    /** 그 모임 안의 것(멤버, 모임 메모·일정, 토픽, 댓글)이 바뀜. groupId 가 null 이면 null(무시된다). */
    public static String group(UUID groupId) {
        return groupId == null ? null : "group:" + groupId;
    }

    /** 그 일정의 내용·중간 지점(출발지)이 바뀜 */
    public static String schedule(UUID scheduleId) {
        return scheduleId == null ? null : "schedule:" + scheduleId;
    }
}
