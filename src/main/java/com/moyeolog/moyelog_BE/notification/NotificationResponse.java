package com.moyeolog.moyelog_BE.notification;

import com.moyeolog.moyelog_BE.entity.User;
import com.moyeolog.moyelog_BE.event.NotificationType;

import java.time.LocalDateTime;
import java.util.UUID;

/** 계약 7절: {id, type, message, link, read, createdAt, actor:{id,nickname,profileImage}|null} */
public record NotificationResponse(UUID id, NotificationType type, String message, String link, boolean read,
                                   LocalDateTime createdAt, Actor actor) {

    public record Actor(UUID id, String nickname, String profileImage) {
    }

    public static NotificationResponse from(Notification n) {
        User a = n.getActor();
        return new NotificationResponse(n.getId(), n.getType(), n.getMessage(), n.getLink(), n.isRead(),
                n.getCreatedAt(), a == null ? null : new Actor(a.getId(), a.getNickname(), a.getProfileImage()));
    }
}
