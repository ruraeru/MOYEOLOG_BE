package com.moyeolog.moyelog_BE.message;

import com.moyeolog.moyelog_BE.notification.CountResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/** 계약 8절 메시지 API */
@RestController
@RequestMapping("/api/messages")
@RequiredArgsConstructor
public class MessageController {

    private final MessageService messageService;

    @GetMapping("/conversations")
    public ResponseEntity<List<ConversationResponse>> conversations(@AuthenticationPrincipal String userId) {
        return ResponseEntity.ok(messageService.getConversations(UUID.fromString(userId)));
    }

    @GetMapping("/unread-count")
    public ResponseEntity<CountResponse> unreadCount(@AuthenticationPrincipal String userId) {
        return ResponseEntity.ok(new CountResponse(messageService.getUnreadCount(UUID.fromString(userId))));
    }

    @GetMapping("/{friendId}")
    public ResponseEntity<List<MessageResponse>> messages(
            @AuthenticationPrincipal String userId,
            @PathVariable UUID friendId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime before,
            @RequestParam(required = false) Integer size) {
        return ResponseEntity.ok(messageService.getMessages(UUID.fromString(userId), friendId, before, size));
    }

    @PostMapping("/{friendId}")
    public ResponseEntity<MessageResponse> send(
            @AuthenticationPrincipal String userId,
            @PathVariable UUID friendId,
            @RequestBody(required = false) MessageSendRequest request) {
        return ResponseEntity.ok(messageService.send(UUID.fromString(userId), friendId, request));
    }

    @PutMapping("/{friendId}/read")
    public ResponseEntity<Void> read(@AuthenticationPrincipal String userId, @PathVariable UUID friendId) {
        messageService.markRead(UUID.fromString(userId), friendId);
        return ResponseEntity.noContent().build();
    }
}
