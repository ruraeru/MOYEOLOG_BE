package com.moyeolog.moyelog_BE.controller;

import com.moyeolog.moyelog_BE.dto.ScheduleRequest;
import com.moyeolog.moyelog_BE.dto.ScheduleResponse;
import com.moyeolog.moyelog_BE.service.ScheduleService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/schedules")
@RequiredArgsConstructor
public class ScheduleController {

    private final ScheduleService scheduleService;

    @PostMapping
    public ResponseEntity<ScheduleResponse> create(
            @AuthenticationPrincipal String userId,
            @RequestBody ScheduleRequest request) {
        return ResponseEntity.ok(scheduleService.createSchedule(UUID.fromString(userId), request));
    }

    @GetMapping
    public ResponseEntity<List<ScheduleResponse>> getSchedules(@AuthenticationPrincipal String userId) {
        return ResponseEntity.ok(scheduleService.getSchedules(UUID.fromString(userId)));
    }

    @PutMapping("/{id}")
    public ResponseEntity<ScheduleResponse> update(
            @AuthenticationPrincipal String userId,
            @PathVariable UUID id,
            @RequestBody ScheduleRequest request) {
        return ResponseEntity.ok(scheduleService.updateSchedule(UUID.fromString(userId), id, request));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(
            @AuthenticationPrincipal String userId,
            @PathVariable UUID id) {
        scheduleService.deleteSchedule(UUID.fromString(userId), id);
        return ResponseEntity.noContent().build();
    }
}
