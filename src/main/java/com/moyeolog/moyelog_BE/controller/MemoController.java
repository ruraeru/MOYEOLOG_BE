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
    public ResponseEntity<List<MemoResponse>> getMyMemos(@AuthenticationPrincipal String userId) {
        return ResponseEntity.ok(memoService.getMyMemos(UUID.fromString(userId)));
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
