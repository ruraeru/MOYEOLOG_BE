package com.moyeolog.moyelog_BE.controller;

import com.moyeolog.moyelog_BE.dto.GroupRequest;
import com.moyeolog.moyelog_BE.dto.GroupResponse;
import com.moyeolog.moyelog_BE.service.GroupService;
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
}
