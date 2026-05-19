package com.moyeolog.moyelog_BE.controller;

import com.moyeolog.moyelog_BE.dto.GroupRequest;
import com.moyeolog.moyelog_BE.dto.GroupResponse;
import com.moyeolog.moyelog_BE.dto.MemoResponse;
import com.moyeolog.moyelog_BE.dto.ScheduleResponse;
import com.moyeolog.moyelog_BE.service.GroupService;
import com.moyeolog.moyelog_BE.service.MemoService;
import com.moyeolog.moyelog_BE.service.ScheduleService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/groups")
@RequiredArgsConstructor
public class GroupController {

    private final GroupService groupService;
    private final MemoService memoService;
    private final ScheduleService scheduleService;

    @PostMapping
    public ResponseEntity<GroupResponse> create(
            @AuthenticationPrincipal String userId,
            @RequestBody GroupRequest request) {
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

    @GetMapping("/{id}/memos")
    public ResponseEntity<List<MemoResponse>> getGroupMemos(@AuthenticationPrincipal String userId, @PathVariable UUID id) {
        return ResponseEntity.ok(memoService.getGroupMemos(UUID.fromString(userId), id));
    }

    @GetMapping("/{id}/schedules")
    public ResponseEntity<List<ScheduleResponse>> getGroupSchedules(@AuthenticationPrincipal String userId, @PathVariable UUID id) {
        // 실제 운영 환경에서는 멤버 여부 확인 로직 권장
        return ResponseEntity.ok(scheduleService.getGroupSchedules(id));
    }
}
