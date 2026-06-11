package com.moyeolog.moyelog_BE.controller;

import com.moyeolog.moyelog_BE.dto.AuthSyncRequest;
import com.moyeolog.moyelog_BE.dto.AuthSyncResponse;
import com.moyeolog.moyelog_BE.service.AuthService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
@Slf4j
public class AuthController {

    private final AuthService authService;

    @PostMapping("/sync")
    public ResponseEntity<AuthSyncResponse> sync(@RequestBody AuthSyncRequest request) {
        log.info("Received sync request for kakaoId: {}", request.getKakaoId());
        AuthSyncResponse response = authService.syncUser(request);
        log.info("Successfully synced user: {}", response.getUser().getId());
        return ResponseEntity.ok(response);
    }

    @PostMapping("/refresh")
    public ResponseEntity<Map<String, String>> refresh(@RequestBody Map<String, String> request) {
        String refreshToken = request.get("refreshToken");
        log.info("Received token refresh request");
        Map<String, String> tokens = authService.refreshToken(refreshToken);
        return ResponseEntity.ok(tokens);
    }
}
