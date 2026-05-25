package com.moyeolog.moyelog_BE.controller;

import com.moyeolog.moyelog_BE.dto.FriendRequestDto;
import com.moyeolog.moyelog_BE.dto.FriendResponse;
import com.moyeolog.moyelog_BE.service.FriendService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/friends")
@RequiredArgsConstructor
public class FriendController {

    private final FriendService friendService;

    @PostMapping("/request")
    public ResponseEntity<Void> sendRequest(
            @AuthenticationPrincipal String userId,
            @RequestBody FriendRequestDto request) {
        friendService.sendFriendRequest(UUID.fromString(userId), request.getCustomId());
        return ResponseEntity.ok().build();
    }

    @GetMapping
    public ResponseEntity<List<FriendResponse>> getFriends(@AuthenticationPrincipal String userId) {
        return ResponseEntity.ok(friendService.getAcceptedFriends(UUID.fromString(userId)));
    }

    @GetMapping("/requests")
    public ResponseEntity<List<FriendResponse>> getRequests(@AuthenticationPrincipal String userId) {
        return ResponseEntity.ok(friendService.getPendingRequests(UUID.fromString(userId)));
    }

    @PutMapping("/accept/{requestId}")
    public ResponseEntity<Void> acceptRequest(
            @AuthenticationPrincipal String userId,
            @PathVariable UUID requestId) {
        friendService.acceptFriendRequest(UUID.fromString(userId), requestId);
        return ResponseEntity.ok().build();
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteFriendship(
            @AuthenticationPrincipal String userId,
            @PathVariable UUID id) {
        friendService.deleteFriendship(UUID.fromString(userId), id);
        return ResponseEntity.noContent().build();
    }
}
