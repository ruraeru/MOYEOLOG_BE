package com.moyeolog.moyelog_BE.controller;

import com.moyeolog.moyelog_BE.dto.MemoRequest;
import com.moyeolog.moyelog_BE.dto.MemoResponse;
import com.moyeolog.moyelog_BE.service.MemoService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/memos")
@RequiredArgsConstructor
public class MemoController {

    private final MemoService memoService;

    @PostMapping(consumes = {"multipart/form-data"})
    public ResponseEntity<MemoResponse> create(
            @AuthenticationPrincipal String userId,
            @RequestPart("memo") MemoRequest request,
            @RequestPart(value = "image", required = false) org.springframework.web.multipart.MultipartFile image) {
        return ResponseEntity.ok(memoService.createMemo(UUID.fromString(userId), request, image));
    }

    @GetMapping
    public ResponseEntity<List<MemoResponse>> getMyMemos(@AuthenticationPrincipal String userId) {
        return ResponseEntity.ok(memoService.getMyMemos(UUID.fromString(userId)));
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
}
