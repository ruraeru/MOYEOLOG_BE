package com.moyeolog.moyelog_BE.notification;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/** 계약 7절 알림 API */
@RestController
@RequestMapping("/api/notifications")
@RequiredArgsConstructor
public class NotificationController {

    private final NotificationService notificationService;

    @GetMapping
    public ResponseEntity<List<NotificationResponse>> list(
            @AuthenticationPrincipal String userId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "30") int size) {
        return ResponseEntity.ok(notificationService.getNotifications(UUID.fromString(userId), page, size));
    }

    @GetMapping("/unread-count")
    public ResponseEntity<CountResponse> unreadCount(@AuthenticationPrincipal String userId) {
        return ResponseEntity.ok(new CountResponse(notificationService.getUnreadCount(UUID.fromString(userId))));
    }

    @PutMapping("/read-all")
    public ResponseEntity<Void> readAll(@AuthenticationPrincipal String userId) {
        notificationService.markAllRead(UUID.fromString(userId));
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/{id}/read")
    public ResponseEntity<Void> read(@AuthenticationPrincipal String userId, @PathVariable UUID id) {
        notificationService.markRead(UUID.fromString(userId), id);
        return ResponseEntity.noContent().build();
    }

    /** 읽은 알림 모두 지우기 */
    @DeleteMapping("/read")
    public ResponseEntity<Void> deleteRead(@AuthenticationPrincipal String userId) {
        notificationService.deleteRead(UUID.fromString(userId));
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@AuthenticationPrincipal String userId, @PathVariable UUID id) {
        notificationService.delete(UUID.fromString(userId), id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/preferences")
    public ResponseEntity<NotificationPreferenceDto> getPreferences(@AuthenticationPrincipal String userId) {
        return ResponseEntity.ok(notificationService.getPreferences(UUID.fromString(userId)));
    }

    @PutMapping("/preferences")
    public ResponseEntity<NotificationPreferenceDto> updatePreferences(
            @AuthenticationPrincipal String userId,
            @RequestBody(required = false) NotificationPreferenceDto request) {
        return ResponseEntity.ok(notificationService.updatePreferences(UUID.fromString(userId), request));
    }
}
