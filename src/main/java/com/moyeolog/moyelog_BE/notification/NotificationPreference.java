package com.moyeolog.moyelog_BE.notification;

import com.moyeolog.moyelog_BE.event.NotificationType;
import jakarta.persistence.*;
import lombok.*;

import java.util.UUID;

/** 사용자별 알림 설정. 행이 없으면 모두 켜진 것으로 본다. */
@Entity
@Table(name = "notification_preferences")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class NotificationPreference {

    /** users.id 와 같은 값 */
    @Id
    @Column(name = "user_id", columnDefinition = "BINARY(16)")
    private UUID userId;

    @Builder.Default
    @Column(name = "friend_request", nullable = false)
    private boolean friendRequest = true;

    @Builder.Default
    @Column(name = "group_invite", nullable = false)
    private boolean groupInvite = true;

    @Builder.Default
    @Column(name = "memo_shared", nullable = false)
    private boolean memoShared = true;

    @Builder.Default
    @Column(name = "topic_comment", nullable = false)
    private boolean topicComment = true;

    @Builder.Default
    @Column(name = "schedule_participant", nullable = false)
    private boolean scheduleParticipant = true;

    @Builder.Default
    @Column(name = "message_enabled", nullable = false)
    private boolean message = true;

    public static NotificationPreference defaults(UUID userId) {
        return NotificationPreference.builder().userId(userId).build();
    }

    /** null 인 필드는 그대로 둔다 */
    public void apply(NotificationPreferenceDto dto) {
        if (dto.friendRequest() != null) friendRequest = dto.friendRequest();
        if (dto.groupInvite() != null) groupInvite = dto.groupInvite();
        if (dto.memoShared() != null) memoShared = dto.memoShared();
        if (dto.topicComment() != null) topicComment = dto.topicComment();
        if (dto.scheduleParticipant() != null) scheduleParticipant = dto.scheduleParticipant();
        if (dto.message() != null) message = dto.message();
    }

    /**
     * 알림 종류별 설정. 친구 수락은 '친구 요청' 설정을, 모임에서 내보내짐은 '모임 초대' 설정을 따른다.
     */
    public boolean allows(NotificationType type) {
        return switch (type) {
            case FRIEND_REQUEST, FRIEND_ACCEPTED -> friendRequest;
            case GROUP_INVITE, GROUP_KICKED -> groupInvite;
            case MEMO_SHARED -> memoShared;
            case TOPIC_COMMENT -> topicComment;
            case SCHEDULE_PARTICIPANT -> scheduleParticipant;
        };
    }
}
