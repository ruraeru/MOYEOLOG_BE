package com.moyeolog.moyelog_BE.message;

import java.time.LocalDateTime;
import java.util.UUID;

/** 계약 8절: {id, senderId, content, createdAt, read} */
public record MessageResponse(UUID id, UUID senderId, String content, LocalDateTime createdAt, boolean read) {

    public static MessageResponse from(Message m) {
        return new MessageResponse(m.getId(), m.getSender().getId(), m.getContent(), m.getCreatedAt(), m.isRead());
    }
}
