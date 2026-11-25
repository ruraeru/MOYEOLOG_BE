package com.moyeolog.moyelog_BE.account;

/** /api/account/**, /api/users/me/password·email 요청·응답 본문. 형식 검사는 AccountService 에서 한다. */
public final class AccountDtos {

    private AccountDtos() {
    }

    public record SignupRequest(String email, String password, String nickname) {
    }

    /** password: SIGNUP 확정 때 가입에 쓴 비밀번호(필수). EMAIL 은 쓰지 않는다(대신 로그인 필요). */
    public record VerifyRequest(String token, String password) {
    }

    public record VerifyResponse(String purpose, String email) {
    }

    public record EmailRequest(String email) {
    }

    /** currentPassword: 계정에 비밀번호가 있으면 필수. */
    public record EmailChangeRequest(String email, String currentPassword) {
    }

    public record PasswordResetConfirmRequest(String token, String newPassword) {
    }

    public record PasswordChangeRequest(String currentPassword, String newPassword) {
    }
}
