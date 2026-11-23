package com.moyeolog.moyelog_BE.controller;

import com.moyeolog.moyelog_BE.dto.UserMeResponse;
import com.moyeolog.moyelog_BE.dto.UserProfileResponse;
import com.moyeolog.moyelog_BE.dto.UserSessionResponse;
import com.moyeolog.moyelog_BE.dto.UserUpdateRequest;
import com.moyeolog.moyelog_BE.service.AuthService;
import com.moyeolog.moyelog_BE.service.UserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
public class UserController {

    public static final String REFRESH_TOKEN_HEADER = "X-Refresh-Token";

    private final UserService userService;
    private final AuthService authService;

    @GetMapping("/me")
    public ResponseEntity<UserMeResponse> getMe(@AuthenticationPrincipal String userId) {
        return ResponseEntity.ok(userService.getMe(UUID.fromString(userId)));
    }

    /** 보낸 필드만 바꾼다. user 파트가 없으면 사진만 바꾼다. */
    @PutMapping(value = "/me", consumes = {"multipart/form-data"})
    public ResponseEntity<UserMeResponse> updateMe(
            @AuthenticationPrincipal String userId,
            @Valid @RequestPart(value = "user", required = false) UserUpdateRequest request,
            @RequestPart(value = "image", required = false) MultipartFile image) {
        return ResponseEntity.ok(userService.updateUser(UUID.fromString(userId), request, image));
    }

    @DeleteMapping("/me")
    public ResponseEntity<Void> deleteMe(@AuthenticationPrincipal String userId) {
        userService.deleteAccount(UUID.fromString(userId));
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/me/profile-image")
    public ResponseEntity<UserMeResponse> deleteProfileImage(@AuthenticationPrincipal String userId) {
        return ResponseEntity.ok(userService.deleteProfileImage(UUID.fromString(userId)));
    }

    @GetMapping("/me/sessions")
    public ResponseEntity<List<UserSessionResponse>> getSessions(
            @AuthenticationPrincipal String userId,
            @RequestHeader(value = REFRESH_TOKEN_HEADER, required = false) String refreshToken) {
        return ResponseEntity.ok(authService.getSessions(UUID.fromString(userId), refreshToken));
    }

    /** 모든 기기 로그아웃 */
    @DeleteMapping("/me/sessions")
    public ResponseEntity<Void> logoutAll(@AuthenticationPrincipal String userId) {
        authService.logoutAll(UUID.fromString(userId));
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/by-custom-id/{customId}")
    public ResponseEntity<UserProfileResponse> getByCustomId(
            @AuthenticationPrincipal String userId,
            @PathVariable String customId) {
        return ResponseEntity.ok(userService.getProfileByCustomId(UUID.fromString(userId), customId));
    }

    @GetMapping("/{id}")
    public ResponseEntity<UserProfileResponse> getProfile(
            @AuthenticationPrincipal String userId,
            @PathVariable String id) {
        return ResponseEntity.ok(userService.getProfile(UUID.fromString(userId), id));
    }
}
