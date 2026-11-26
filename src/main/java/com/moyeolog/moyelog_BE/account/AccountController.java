package com.moyeolog.moyelog_BE.account;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

/**
 * 이메일 가입·인증·비밀번호 찾기(공개, /api/account/**)와 로그인한 사용자의 비밀번호·이메일 설정.
 * nginx 가 /api/auth/* 를 프론트로 보내므로 브라우저가 부르는 이 API 는 /api/auth 아래에 두지 않는다.
 */
@RestController
@RequiredArgsConstructor
public class AccountController {

    private final AccountService accountService;

    @PostMapping("/api/account/signup")
    public ResponseEntity<Map<String, Object>> signup(@RequestBody AccountDtos.SignupRequest request) {
        accountService.signup(request.email(), request.password(), request.nickname());
        return ResponseEntity.accepted().body(Map.of());
    }

    @PostMapping("/api/account/verify")
    public ResponseEntity<AccountDtos.VerifyResponse> verify(@AuthenticationPrincipal Object principal,
                                                             @RequestBody AccountDtos.VerifyRequest request) {
        // permitAll 경로: 유효한 Bearer 가 오면 JwtAuthenticationFilter 가 사용자 id 를, 없으면 익명 principal 을 채운다.
        String userId = principal instanceof String s ? s : null;
        return ResponseEntity.ok(accountService.verify(request.token(), request.password(), userId));
    }

    /** 메일 링크를 쓰지 않고 상태·이메일만 본다(SIGNUP·EMAIL). 쓸 수 없으면 400. */
    @PostMapping("/api/account/verify/preview")
    public ResponseEntity<AccountDtos.VerifyResponse> previewVerify(@RequestBody AccountDtos.VerifyRequest request) {
        return ResponseEntity.ok(accountService.previewToken(request.token()));
    }

    @PostMapping("/api/account/password-reset")
    public ResponseEntity<Map<String, Object>> passwordReset(@RequestBody AccountDtos.EmailRequest request) {
        accountService.requestPasswordReset(request.email());
        return ResponseEntity.accepted().body(Map.of());
    }

    @PostMapping("/api/account/password-reset/confirm")
    public ResponseEntity<Void> passwordResetConfirm(@RequestBody AccountDtos.PasswordResetConfirmRequest request) {
        accountService.confirmPasswordReset(request.token(), request.newPassword());
        return ResponseEntity.noContent().build();
    }

    /** 재설정 링크가 아직 쓸 수 있는지(소모하지 않음). 화면이 열 때 불러 두 칸을 다 쓰기 전에 알려 준다. */
    @PostMapping("/api/account/password-reset/check")
    public ResponseEntity<Void> passwordResetCheck(@RequestBody AccountDtos.TokenRequest request) {
        accountService.checkPasswordResetToken(request.token());
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/api/users/me/password")
    public ResponseEntity<Void> changePassword(@AuthenticationPrincipal String userId,
                                               @RequestBody AccountDtos.PasswordChangeRequest request) {
        accountService.changePassword(UUID.fromString(userId), request.currentPassword(), request.newPassword());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/api/users/me/email")
    public ResponseEntity<Map<String, Object>> requestEmailChange(@AuthenticationPrincipal String userId,
                                                                  @RequestBody AccountDtos.EmailChangeRequest request) {
        accountService.requestEmailChange(UUID.fromString(userId), request.email(), request.currentPassword());
        return ResponseEntity.accepted().body(Map.of());
    }
}
