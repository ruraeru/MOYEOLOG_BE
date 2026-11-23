package com.moyeolog.moyelog_BE.controller;

import com.moyeolog.moyelog_BE.dto.*;
import com.moyeolog.moyelog_BE.service.GroupErrors;
import com.moyeolog.moyelog_BE.service.GroupService;
import com.moyeolog.moyelog_BE.service.MemoService;
import com.moyeolog.moyelog_BE.service.ScheduleService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/groups")
@RequiredArgsConstructor
public class GroupController {

    private final GroupService groupService;
    private final MemoService memoService;
    private final ScheduleService scheduleService;

    @GetMapping("/activities")
    public ResponseEntity<List<GroupActivityResponse>> getRecentActivities(@AuthenticationPrincipal String userId) {
        return ResponseEntity.ok(groupService.getRecentActivities(UUID.fromString(userId)));
    }

    @PostMapping
    public ResponseEntity<GroupResponse> create(
            @AuthenticationPrincipal String userId,
            @Valid @RequestBody GroupRequest request) {
        return ResponseEntity.ok(groupService.createGroup(UUID.fromString(userId), request));
    }

    @GetMapping
    public ResponseEntity<List<GroupResponse>> getMyGroups(@AuthenticationPrincipal String userId) {
        return ResponseEntity.ok(groupService.getMyGroups(UUID.fromString(userId)));
    }

    @GetMapping("/{id}")
    public ResponseEntity<GroupResponse> getGroup(@AuthenticationPrincipal String userId, @PathVariable UUID id) {
        return ResponseEntity.ok(groupService.getGroup(UUID.fromString(userId), id));
    }

    @PutMapping(value = "/{id}", consumes = {"multipart/form-data"})
    public ResponseEntity<GroupResponse> update(
            @AuthenticationPrincipal String userId,
            @PathVariable UUID id,
            @Valid @RequestPart("group") GroupRequest request,
            @RequestPart(value = "image", required = false) org.springframework.web.multipart.MultipartFile image,
            @RequestPart(value = "bgImage", required = false) org.springframework.web.multipart.MultipartFile bgImage) {
        return ResponseEntity.ok(groupService.updateGroup(UUID.fromString(userId), id, request, image, bgImage));
    }

    @GetMapping("/{id}/memos")
    public ResponseEntity<List<MemoResponse>> getGroupMemos(@AuthenticationPrincipal String userId, @PathVariable UUID id) {
        return ResponseEntity.ok(memoService.getGroupMemos(UUID.fromString(userId), id));
    }

    @GetMapping("/{id}/schedules")
    public ResponseEntity<List<ScheduleResponse>> getGroupSchedules(@AuthenticationPrincipal String userId, @PathVariable UUID id) {
        return ResponseEntity.ok(scheduleService.getGroupSchedules(UUID.fromString(userId), id));
    }

    /** 초대 코드로 모임 정보 미리 보기. 없는 코드는 404 */
    @GetMapping("/preview")
    public ResponseEntity<GroupPreviewResponse> preview(
            @AuthenticationPrincipal String userId,
            @RequestParam(required = false) String code) {
        return ResponseEntity.ok(groupService.previewByCode(UUID.fromString(userId), code));
    }

    /** 초대 코드로 가입 → {groupId}. 이미 멤버면 409 (본문에 groupId 포함) */
    @PostMapping("/join")
    public ResponseEntity<GroupJoinResponse> joinByCode(
            @AuthenticationPrincipal String userId,
            @RequestParam String code) {
        return ResponseEntity.ok(groupService.joinGroupByCode(UUID.fromString(userId), code));
    }

    /** 이미 참여한 모임: 공통 오류 형식에 groupId 를 더해 돌려준다 (프론트가 그 모임으로 이동). */
    @ExceptionHandler(GroupErrors.AlreadyMemberException.class)
    public ResponseEntity<Map<String, Object>> handleAlreadyMember(GroupErrors.AlreadyMemberException e) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", HttpStatus.CONFLICT.getReasonPhrase());
        body.put("message", e.getMessage());
        body.put("status", HttpStatus.CONFLICT.value());
        body.put("timestamp", LocalDateTime.now());
        body.put("groupId", e.getGroupId());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
    }

    @PostMapping("/{id}/invitations")
    public ResponseEntity<Void> inviteMembers(
            @AuthenticationPrincipal String userId,
            @PathVariable UUID id,
            @RequestBody GroupInviteRequest request) {
        groupService.inviteMembers(UUID.fromString(userId), id, request);
        return ResponseEntity.ok().build();
    }

    @GetMapping("/invitations")
    public ResponseEntity<List<GroupInvitationResponse>> getMyInvitations(@AuthenticationPrincipal String userId) {
        return ResponseEntity.ok(groupService.getMyInvitations(UUID.fromString(userId)));
    }

    @PostMapping("/invitations/{invitationId}/accept")
    public ResponseEntity<Void> acceptInvitation(
            @AuthenticationPrincipal String userId,
            @PathVariable UUID invitationId) {
        groupService.acceptInvitation(UUID.fromString(userId), invitationId);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/invitations/{invitationId}/reject")
    public ResponseEntity<Void> rejectInvitation(
            @AuthenticationPrincipal String userId,
            @PathVariable UUID invitationId) {
        groupService.rejectInvitation(UUID.fromString(userId), invitationId);
        return ResponseEntity.ok().build();
    }

    /** 모임 나가기 (멤버 본인). 방장은 나갈 수 없습니다. */
    @DeleteMapping("/{id}/members/me")
    public ResponseEntity<Void> leaveGroup(
            @AuthenticationPrincipal String userId,
            @PathVariable UUID id) {
        groupService.leaveGroup(UUID.fromString(userId), id);
        return ResponseEntity.noContent().build();
    }

    /** 모임 삭제 (방장 전용) */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteGroup(
            @AuthenticationPrincipal String userId,
            @PathVariable UUID id) {
        groupService.deleteGroup(UUID.fromString(userId), id);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{id}/members/{memberId}")
    public ResponseEntity<Void> kickMember(
            @AuthenticationPrincipal String userId,
            @PathVariable UUID id,
            @PathVariable UUID memberId) {
        groupService.kickMember(UUID.fromString(userId), id, memberId);
        return ResponseEntity.ok().build();
    }
}
