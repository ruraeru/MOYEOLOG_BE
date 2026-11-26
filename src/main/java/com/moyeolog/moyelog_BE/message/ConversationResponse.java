package com.moyeolog.moyelog_BE.message;

import com.moyeolog.moyelog_BE.entity.User;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 계약 8절: {friend:{id,nickname,profileImage,customId}, lastMessage:{id,content,senderId,createdAt}|null, unreadCount, canSend}.
 * canSend: 지금 친구라 보낼 수 있는지(친구를 끊어도 대화는 목록에 남으므로 화면이 구분한다).
 */
public record ConversationResponse(Friend friend, LastMessage lastMessage, long unreadCount, boolean canSend) {

    public record Friend(UUID id, String nickname, String profileImage, String customId) {
        static Friend from(User u) {
            return new Friend(u.getId(), u.getNickname(), u.getProfileImage(), u.getCustomId());
        }
    }

    public record LastMessage(UUID id, String content, UUID senderId, LocalDateTime createdAt) {
        static LastMessage from(Message m) {
            return m == null ? null
                    : new LastMessage(m.getId(), m.getContent(), m.getSender().getId(), m.getCreatedAt());
        }
    }
}
