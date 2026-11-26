package com.moyeolog.moyelog_BE.controller;

import com.moyeolog.moyelog_BE.dto.MemoInsightResponse;
import com.moyeolog.moyelog_BE.dto.MemoRequest;
import com.moyeolog.moyelog_BE.dto.MemoResponse;
import com.moyeolog.moyelog_BE.dto.MemoShareRequest;
import com.moyeolog.moyelog_BE.service.MemoService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/memos")
@RequiredArgsConstructor
public class MemoController {

    private final MemoService memoService;

    @PostMapping(consumes = {"multipart/form-data"})
    public ResponseEntity<MemoResponse> create(
            @AuthenticationPrincipal String userId,
            @Valid @RequestPart("memo") MemoRequest request,
            @RequestPart(value = "image", required = false) MultipartFile image) {
        return ResponseEntity.ok(memoService.createMemo(UUID.fromString(userId), request, image));
    }

    @PutMapping(value = "/{id}", consumes = {"multipart/form-data"})
    public ResponseEntity<MemoResponse> update(
            @AuthenticationPrincipal String userId,
            @PathVariable UUID id,
            @Valid @RequestPart("memo") MemoRequest request,
            @RequestPart(value = "image", required = false) MultipartFile image) {
        return ResponseEntity.ok(memoService.updateMemo(UUID.fromString(userId), id, request, image));
    }

    @GetMapping
    public ResponseEntity<List<MemoResponse>> getMyMemos(
            @AuthenticationPrincipal String userId,
            @RequestParam(value = "q", required = false) String q) {
        return ResponseEntity.ok(memoService.getMyMemos(UUID.fromString(userId), q));
    }

    /** 내가 즐겨찾기했고 지금 볼 수 있는 모든 메모 (내 메모·공유받은 메모·모임 메모) */
    @GetMapping("/favorites")
    public ResponseEntity<List<MemoResponse>> getFavoriteMemos(@AuthenticationPrincipal String userId) {
        return ResponseEntity.ok(memoService.getFavoriteMemos(UUID.fromString(userId)));
    }

    @GetMapping("/shared")
    public ResponseEntity<List<MemoResponse>> getSharedMemos(@AuthenticationPrincipal String userId) {
        return ResponseEntity.ok(memoService.getSharedMemos(UUID.fromString(userId)));
    }

    @GetMapping("/{id}")
    public ResponseEntity<MemoResponse> getMemo(@AuthenticationPrincipal String userId, @PathVariable UUID id) {
        return ResponseEntity.ok(memoService.getMemo(UUID.fromString(userId), id));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@AuthenticationPrincipal String userId, @PathVariable UUID id) {
        memoService.deleteMemo(UUID.fromString(userId), id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/share")
    public ResponseEntity<Void> share(
            @AuthenticationPrincipal String userId,
            @PathVariable UUID id,
            @Valid @RequestBody MemoShareRequest request) {
        memoService.shareMemo(UUID.fromString(userId), id, request);
        return ResponseEntity.ok().build();
    }

    /** 메모 공유 해제: 작성자가 공유 대상을 제거 (공유받은 본인이 스스로 해제하는 것도 허용) */
    @DeleteMapping("/{id}/share/{targetUserId}")
    public ResponseEntity<Void> unshare(
            @AuthenticationPrincipal String userId,
            @PathVariable UUID id,
            @PathVariable UUID targetUserId) {
        memoService.unshareMemo(UUID.fromString(userId), id, targetUserId);
        return ResponseEntity.noContent().build();
    }

    /** 내 개인 메모를 내가 속한 모임에 공유 (그 모임 멤버는 읽기만) */
    @PostMapping("/{id}/group-share/{groupId}")
    public ResponseEntity<Void> shareToGroup(
            @AuthenticationPrincipal String userId,
            @PathVariable UUID id,
            @PathVariable UUID groupId) {
        memoService.shareMemoToGroup(UUID.fromString(userId), id, groupId);
        return ResponseEntity.ok().build();
    }

    /** 모임 공유 해제 (작성자만) */
    @DeleteMapping("/{id}/group-share/{groupId}")
    public ResponseEntity<Void> unshareFromGroup(
            @AuthenticationPrincipal String userId,
            @PathVariable UUID id,
            @PathVariable UUID groupId) {
        memoService.unshareMemoFromGroup(UUID.fromString(userId), id, groupId);
        return ResponseEntity.noContent().build();
    }

    /** 내 고정 (모든 메모, 읽을 수 있는 사람 누구나, 본인에게만 보인다). 이미 고정돼 있으면 그대로 200. */
    @PutMapping("/{id}/pin")
    public ResponseEntity<MemoResponse> pin(@AuthenticationPrincipal String userId, @PathVariable UUID id) {
        return ResponseEntity.ok(memoService.pinMemo(UUID.fromString(userId), id, true));
    }

    @DeleteMapping("/{id}/pin")
    public ResponseEntity<MemoResponse> unpin(@AuthenticationPrincipal String userId, @PathVariable UUID id) {
        return ResponseEntity.ok(memoService.pinMemo(UUID.fromString(userId), id, false));
    }

    /** 모임 고정 (모임 메모만, 그 모임의 방장·작성자. 멤버 모두에게 보인다. 개인 메모면 400). 이미 고정돼 있으면 그대로 200. */
    @PutMapping("/{id}/group-pin")
    public ResponseEntity<MemoResponse> groupPin(@AuthenticationPrincipal String userId, @PathVariable UUID id) {
        return ResponseEntity.ok(memoService.groupPinMemo(UUID.fromString(userId), id, true));
    }

    @DeleteMapping("/{id}/group-pin")
    public ResponseEntity<MemoResponse> groupUnpin(@AuthenticationPrincipal String userId, @PathVariable UUID id) {
        return ResponseEntity.ok(memoService.groupPinMemo(UUID.fromString(userId), id, false));
    }

    @PutMapping("/{id}/favorite")
    public ResponseEntity<MemoResponse> toggleFavorite(
            @AuthenticationPrincipal String userId,
            @PathVariable UUID id) {
        return ResponseEntity.ok(memoService.toggleFavorite(UUID.fromString(userId), id));
    }

    @PutMapping("/{id}/tags")
    public ResponseEntity<MemoResponse> updateTags(
            @AuthenticationPrincipal String userId,
            @PathVariable UUID id,
            @RequestBody Map<String, List<String>> request) {
        return ResponseEntity.ok(memoService.updateTags(UUID.fromString(userId), id, request != null ? request.get("tags") : null));
    }

    @PostMapping("/{id}/analyze")
    public ResponseEntity<MemoInsightResponse> analyze(
            @AuthenticationPrincipal String userId,
            @PathVariable UUID id) {
        return ResponseEntity.ok(memoService.analyzeMemo(UUID.fromString(userId), id));
    }

    @GetMapping("/{id}/insight")
    public ResponseEntity<MemoInsightResponse> getInsight(
            @AuthenticationPrincipal String userId,
            @PathVariable UUID id) {
        MemoInsightResponse insight = memoService.getMemoInsight(UUID.fromString(userId), id);
        // 아직 분석 결과가 없으면 204 No Content
        return insight != null ? ResponseEntity.ok(insight) : ResponseEntity.noContent().build();
    }
}
