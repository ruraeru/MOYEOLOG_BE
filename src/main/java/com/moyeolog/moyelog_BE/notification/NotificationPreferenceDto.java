package com.moyeolog.moyelog_BE.notification;

/** 계약 7절: GET/PUT /api/notifications/preferences. PUT 에서 null(누락) 필드는 바꾸지 않는다. */
public record NotificationPreferenceDto(Boolean friendRequest, Boolean groupInvite, Boolean memoShared,
                                        Boolean topicComment, Boolean scheduleParticipant, Boolean message) {

    public static NotificationPreferenceDto from(NotificationPreference p) {
        return new NotificationPreferenceDto(p.isFriendRequest(), p.isGroupInvite(), p.isMemoShared(),
                p.isTopicComment(), p.isScheduleParticipant(), p.isMessage());
    }
}
