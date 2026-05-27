package com.moyeolog.moyelog_BE.controller;

import com.moyeolog.moyelog_BE.dto.UserUpdateRequest;
import com.moyeolog.moyelog_BE.entity.User;
import com.moyeolog.moyelog_BE.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.UUID;

@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    @GetMapping("/me")
    public ResponseEntity<User> getMe(@AuthenticationPrincipal String userId) {
        return ResponseEntity.ok(userService.getUser(UUID.fromString(userId)));
    }

    @PutMapping(value = "/me", consumes = {"multipart/form-data"})
    public ResponseEntity<User> updateMe(
            @AuthenticationPrincipal String userId,
            @RequestPart("user") UserUpdateRequest request,
            @RequestPart(value = "image", required = false) MultipartFile image) {
        return ResponseEntity.ok(userService.updateUser(UUID.fromString(userId), request, image));
    }
}
