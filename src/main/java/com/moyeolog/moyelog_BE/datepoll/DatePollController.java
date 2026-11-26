package com.moyeolog.moyelog_BE.datepoll;

import com.moyeolog.moyelog_BE.datepoll.DatePollDtos.*;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/** 모임 날짜 조율: 멤버가 가능한 날을 고르고, 모임장이 하나를 정하면 모임 일정이 된다. */
@RestController
@RequestMapping("/api/groups/{groupId}/date-polls")
@RequiredArgsConstructor
public class DatePollController {

    private final DatePollService datePollService;

    @GetMapping
    public ResponseEntity<List<DatePollSummary>> list(@AuthenticationPrincipal String userId, @PathVariable UUID groupId) {
        return ResponseEntity.ok(datePollService.list(UUID.fromString(userId), groupId));
    }

    @PostMapping
    public ResponseEntity<DatePollDetail> create(@AuthenticationPrincipal String userId, @PathVariable UUID groupId,
                                                 @Valid @RequestBody CreateRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(datePollService.create(UUID.fromString(userId), groupId, request));
    }

    @GetMapping("/{pollId}")
    public ResponseEntity<DatePollDetail> get(@AuthenticationPrincipal String userId, @PathVariable UUID groupId,
                                              @PathVariable UUID pollId) {
        return ResponseEntity.ok(datePollService.get(UUID.fromString(userId), groupId, pollId));
    }

    @PutMapping("/{pollId}/votes")
    public ResponseEntity<DatePollDetail> vote(@AuthenticationPrincipal String userId, @PathVariable UUID groupId,
                                               @PathVariable UUID pollId, @Valid @RequestBody VotesRequest request) {
        return ResponseEntity.ok(datePollService.vote(UUID.fromString(userId), groupId, pollId, request));
    }

    /** AI 추천 (LLM 호출, 오래 걸린다. nginx 300초) */
    @PostMapping("/{pollId}/recommend")
    public ResponseEntity<DatePollDetail> recommend(@AuthenticationPrincipal String userId, @PathVariable UUID groupId,
                                                    @PathVariable UUID pollId) {
        return ResponseEntity.ok(datePollService.recommend(UUID.fromString(userId), groupId, pollId));
    }

    /** 아직 응답하지 않은 멤버에게 알림 (모임장만) */
    @PostMapping("/{pollId}/remind")
    public ResponseEntity<Void> remind(@AuthenticationPrincipal String userId, @PathVariable UUID groupId,
                                       @PathVariable UUID pollId) {
        datePollService.remind(UUID.fromString(userId), groupId, pollId);
        return ResponseEntity.noContent().build();
    }

    /** 날짜를 정하고 모임 일정을 만든다 (모임장만) */
    @PostMapping("/{pollId}/confirm")
    public ResponseEntity<DatePollDetail> confirm(@AuthenticationPrincipal String userId, @PathVariable UUID groupId,
                                                  @PathVariable UUID pollId, @Valid @RequestBody ConfirmRequest request) {
        return ResponseEntity.ok(datePollService.confirm(UUID.fromString(userId), groupId, pollId, request));
    }

    /** 조율 삭제 (모임장만). 만들어진 일정은 남는다. */
    @DeleteMapping("/{pollId}")
    public ResponseEntity<Void> delete(@AuthenticationPrincipal String userId, @PathVariable UUID groupId,
                                       @PathVariable UUID pollId) {
        datePollService.delete(UUID.fromString(userId), groupId, pollId);
        return ResponseEntity.noContent().build();
    }
}
