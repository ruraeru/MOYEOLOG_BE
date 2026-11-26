package com.moyeolog.moyelog_BE.notice;

import com.moyeolog.moyelog_BE.notice.NoticeDtos.*;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/** 모임 공지: 방장이 올리고 고치고 지우고 고정한다. 멤버는 읽고, 공지에 붙은 투표에 참여한다. */
@RestController
@RequestMapping("/api/groups/{groupId}/notices")
@RequiredArgsConstructor
public class GroupNoticeController {

    private final GroupNoticeService noticeService;
    private final GroupNoticePollService pollService;

    @GetMapping
    public ResponseEntity<List<NoticeSummary>> list(@AuthenticationPrincipal String userId, @PathVariable UUID groupId) {
        return ResponseEntity.ok(noticeService.list(UUID.fromString(userId), groupId));
    }

    @PostMapping
    public ResponseEntity<NoticeDetail> create(@AuthenticationPrincipal String userId, @PathVariable UUID groupId,
                                               @Valid @RequestBody CreateRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(noticeService.create(UUID.fromString(userId), groupId, request));
    }

    /** 보는 사람을 읽음으로 기록한다. */
    @GetMapping("/{noticeId}")
    public ResponseEntity<NoticeDetail> get(@AuthenticationPrincipal String userId, @PathVariable UUID groupId,
                                            @PathVariable UUID noticeId) {
        return ResponseEntity.ok(noticeService.detail(UUID.fromString(userId), groupId, noticeId, true));
    }

    @PutMapping("/{noticeId}")
    public ResponseEntity<NoticeDetail> update(@AuthenticationPrincipal String userId, @PathVariable UUID groupId,
                                               @PathVariable UUID noticeId, @Valid @RequestBody UpdateRequest request) {
        return ResponseEntity.ok(noticeService.update(UUID.fromString(userId), groupId, noticeId, request));
    }

    @DeleteMapping("/{noticeId}")
    public ResponseEntity<Void> delete(@AuthenticationPrincipal String userId, @PathVariable UUID groupId,
                                       @PathVariable UUID noticeId) {
        noticeService.delete(UUID.fromString(userId), groupId, noticeId);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/{noticeId}/pin")
    public ResponseEntity<NoticeDetail> pin(@AuthenticationPrincipal String userId, @PathVariable UUID groupId,
                                            @PathVariable UUID noticeId) {
        return ResponseEntity.ok(noticeService.pin(UUID.fromString(userId), groupId, noticeId, true));
    }

    @DeleteMapping("/{noticeId}/pin")
    public ResponseEntity<NoticeDetail> unpin(@AuthenticationPrincipal String userId, @PathVariable UUID groupId,
                                              @PathVariable UUID noticeId) {
        return ResponseEntity.ok(noticeService.pin(UUID.fromString(userId), groupId, noticeId, false));
    }

    /** 내 선택을 통째로 바꾼다(빈 배열 = 취소). 단일 선택인데 2개 이상이면 400, 닫혔으면 409. */
    @PutMapping("/{noticeId}/poll/votes")
    public ResponseEntity<NoticeDetail> vote(@AuthenticationPrincipal String userId, @PathVariable UUID groupId,
                                             @PathVariable UUID noticeId, @Valid @RequestBody VoteRequest request) {
        UUID me = UUID.fromString(userId);
        pollService.vote(me, groupId, noticeId, request.optionIds());
        return ResponseEntity.ok(noticeService.detail(me, groupId, noticeId, false));
    }

    /** 투표 마감 (방장) */
    @PostMapping("/{noticeId}/poll/close")
    public ResponseEntity<NoticeDetail> closePoll(@AuthenticationPrincipal String userId, @PathVariable UUID groupId,
                                                  @PathVariable UUID noticeId) {
        UUID me = UUID.fromString(userId);
        pollService.close(me, groupId, noticeId);
        return ResponseEntity.ok(noticeService.detail(me, groupId, noticeId, false));
    }

    /** 아직 투표하지 않은 멤버에게 알림 (방장) */
    @PostMapping("/{noticeId}/poll/remind")
    public ResponseEntity<Void> remindPoll(@AuthenticationPrincipal String userId, @PathVariable UUID groupId,
                                           @PathVariable UUID noticeId) {
        pollService.remind(UUID.fromString(userId), groupId, noticeId);
        return ResponseEntity.noContent().build();
    }
}
