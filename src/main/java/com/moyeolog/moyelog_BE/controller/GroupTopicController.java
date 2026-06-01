package com.moyeolog.moyelog_BE.controller;

import com.moyeolog.moyelog_BE.dto.*;
import com.moyeolog.moyelog_BE.service.GroupTopicService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class GroupTopicController {

    private final GroupTopicService groupTopicService;

    @GetMapping("/groups/{groupId}/topics")
    public ResponseEntity<List<GroupTopicResponse>> getTopics(
            @AuthenticationPrincipal String userId,
            @PathVariable UUID groupId) {
        return ResponseEntity.ok(groupTopicService.getTopicsByGroup(UUID.fromString(userId), groupId));
    }

    @PostMapping("/groups/{groupId}/topics")
    public ResponseEntity<GroupTopicResponse> createTopic(
            @AuthenticationPrincipal String userId,
            @PathVariable UUID groupId,
            @RequestBody GroupTopicRequest request) {
        return ResponseEntity.ok(groupTopicService.createTopic(UUID.fromString(userId), groupId, request));
    }

    @GetMapping("/topics/{topicId}")
    public ResponseEntity<GroupTopicDetailResponse> getTopic(
            @AuthenticationPrincipal String userId,
            @PathVariable UUID topicId) {
        return ResponseEntity.ok(groupTopicService.getTopicDetail(UUID.fromString(userId), topicId));
    }

    @PutMapping("/topics/{topicId}")
    public ResponseEntity<GroupTopicResponse> updateTopic(
            @AuthenticationPrincipal String userId,
            @PathVariable UUID topicId,
            @RequestBody GroupTopicRequest request) {
        return ResponseEntity.ok(groupTopicService.updateTopic(UUID.fromString(userId), topicId, request));
    }

    @DeleteMapping("/topics/{topicId}")
    public ResponseEntity<Void> deleteTopic(
            @AuthenticationPrincipal String userId,
            @PathVariable UUID topicId) {
        groupTopicService.deleteTopic(UUID.fromString(userId), topicId);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/topics/{topicId}/comments")
    public ResponseEntity<GroupTopicCommentResponse> createComment(
            @AuthenticationPrincipal String userId,
            @PathVariable UUID topicId,
            @RequestBody GroupTopicCommentRequest request) {
        return ResponseEntity.ok(groupTopicService.createComment(UUID.fromString(userId), topicId, request));
    }

    @PutMapping("/topics/comments/{commentId}")
    public ResponseEntity<GroupTopicCommentResponse> updateComment(
            @AuthenticationPrincipal String userId,
            @PathVariable UUID commentId,
            @RequestBody GroupTopicCommentRequest request) {
        return ResponseEntity.ok(groupTopicService.updateComment(UUID.fromString(userId), commentId, request));
    }

    @DeleteMapping("/topics/comments/{commentId}")
    public ResponseEntity<Void> deleteComment(
            @AuthenticationPrincipal String userId,
            @PathVariable UUID commentId) {
        groupTopicService.deleteComment(UUID.fromString(userId), commentId);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/topics/{topicId}/analyze")
    public ResponseEntity<MemoInsightResponse> analyze(
            @AuthenticationPrincipal String userId,
            @PathVariable UUID topicId) {
        return ResponseEntity.ok(groupTopicService.analyzeTopic(UUID.fromString(userId), topicId));
    }
}
