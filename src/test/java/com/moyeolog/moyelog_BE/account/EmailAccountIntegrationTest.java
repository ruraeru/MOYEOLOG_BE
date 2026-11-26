package com.moyeolog.moyelog_BE.account;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.never;
import static org.mockito.ArgumentMatchers.any;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 이메일 가입·인증·비밀번호 찾기·비밀번호 설정·이메일 등록 (EA_BRIEF be-account). 메일은 목으로 받아 토큰을 꺼낸다. */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:moyeolog-account-test;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE"
})
class EmailAccountIntegrationTest {

    private static final String SECRET = "test-internal-secret";
    private static final Pattern TOKEN = Pattern.compile("token=([A-Za-z0-9_-]{43})");
    private static final String PASSWORD = "correct-horse-1";

    @MockitoBean
    private JavaMailSender mailSender;

    @Autowired private WebApplicationContext context;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PasswordEncoder passwordEncoder;

    private final ObjectMapper mapper = new ObjectMapper();
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        when(mailSender.createMimeMessage()).thenAnswer(i -> new MimeMessage((jakarta.mail.Session) null));
    }

    // ─── helpers ────────────────────────────────────────────────────

    private static String email(String tag) {
        return tag + "-" + UUID.randomUUID().toString().substring(0, 8) + "@example.com";
    }

    private ResultActions postJson(String url, Object body) throws Exception {
        return mockMvc.perform(post(url).contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(body)));
    }

    private ResultActions authJson(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder builder,
                                   String accessToken, Object body) throws Exception {
        return mockMvc.perform(builder.header("Authorization", "Bearer " + accessToken)
                .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(body)));
    }

    /** n 번째(1부터)까지 보낸 메일을 기다려 모두 돌려준다. */
    private List<MimeMessage> mails(int count) {
        ArgumentCaptor<MimeMessage> captor = ArgumentCaptor.forClass(MimeMessage.class);
        verify(mailSender, timeout(5000).times(count)).send(captor.capture());
        return captor.getAllValues();
    }

    private void noMoreMailsThan(int count) {
        verify(mailSender, after(400).times(count)).send(any(MimeMessage.class));
    }

    private static String text(Part part) throws Exception {
        // 아직 saveChanges 전이라 Content-Type 헤더가 없다: 내용 객체의 타입으로 나눈다.
        Object content = part.getContent();
        if (content instanceof String s) {
            return s;
        }
        Multipart multipart = (Multipart) content;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < multipart.getCount(); i++) {
            sb.append(text(multipart.getBodyPart(i))).append('\n');
        }
        return sb.toString();
    }

    private static String tokenIn(MimeMessage message) throws Exception {
        Matcher m = TOKEN.matcher(text(message));
        assertThat(m.find()).as("메일에 토큰 링크가 있어야 함").isTrue();
        return m.group(1);
    }

    private static String to(MimeMessage message) throws Exception {
        return message.getAllRecipients()[0].toString();
    }

    /** 카카오 로그인. verified=false 면 sync 는 이메일을 저장하지 않는다(미인증 이메일 저장 안 함). */
    private JsonNode sync(String kakaoId, String email, boolean verified) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("kakaoId", kakaoId);
        body.put("nickname", "카카오");
        body.put("email", email);
        body.put("emailVerified", verified);
        MvcResult result = mockMvc.perform(post("/api/auth/sync").header("X-Internal-Secret", SECRET)
                        .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(body)))
                .andExpect(status().isOk()).andReturn();
        return mapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private JsonNode kakao(String email, boolean verified) throws Exception {
        return sync("kakao-" + UUID.randomUUID(), email, verified);
    }

    /** 예전 데이터처럼 미인증 이메일을 가진 카카오 계정 (지금 sync 는 미인증 이메일을 저장하지 않으므로 직접 넣는다). */
    private JsonNode kakaoWithUnverifiedEmail(String email) throws Exception {
        JsonNode session = kakao(null, false);
        jdbc.update("UPDATE users SET email = ? WHERE custom_id = ?", email, session.path("user").path("customId").asText());
        return session;
    }

    private static String access(JsonNode session) {
        return session.path("accessToken").asText();
    }

    private Map<String, Object> userRow(String email) {
        return jdbc.queryForMap("SELECT * FROM users WHERE email = ?", email);
    }

    private int userCount(String email) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE email = ?", Integer.class, email);
    }

    private ResultActions verifyToken(String token, String password, String accessToken) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("token", token);
        if (password != null) {
            body.put("password", password);
        }
        var builder = post("/api/account/verify").contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(body));
        if (accessToken != null) {
            builder.header("Authorization", "Bearer " + accessToken);
        }
        return mockMvc.perform(builder);
    }

    // ─── 가입·인증 ───────────────────────────────────────────────────

    @Test
    @DisplayName("가입: 202 → 인증 메일(purpose=signup) → 가입 비밀번호를 넣어야 계정 생성(kakaoId 없음, 인증됨). 재사용·PASSWORD 토큰은 400")
    void signup_verify_createsAccount() throws Exception {
        String addr = email("Signup").toUpperCase();
        postJson("/api/account/signup", Map.of("email", "  " + addr + " ", "password", PASSWORD, "nickname", " 새 사용자 "))
                .andExpect(status().isAccepted());

        MimeMessage mail = mails(1).getFirst();
        String lower = addr.toLowerCase();
        assertThat(to(mail)).isEqualTo(lower);
        String token = tokenIn(mail);
        assertThat(text(mail)).contains("https://moyeolog.test/verify?token=" + token + "&purpose=signup");
        assertThat(userCount(lower)).isZero();
        // 토큰 원문은 DB 에 없다
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM email_verifications WHERE token_hash = ?", Integer.class, token))
                .isZero();

        // 메일만 연 사람(비밀번호 모름)은 확정할 수 없다
        verifyToken(token, null, null).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(AccountService.WRONG_PASSWORD_MESSAGE));
        verifyToken(token, "wrong-password", null).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(AccountService.WRONG_PASSWORD_MESSAGE));
        assertThat(userCount(lower)).isZero();

        verifyToken(token, PASSWORD, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.purpose").value("SIGNUP"))
                .andExpect(jsonPath("$.email").value(lower));

        Map<String, Object> row = userRow(lower);
        assertThat(row.get("kakao_id")).isNull();
        assertThat(row.get("email_verified_at")).isNotNull();
        assertThat(row.get("nickname")).isEqualTo("새 사용자");
        assertThat((String) row.get("custom_id")).hasSize(8);
        assertThat(passwordEncoder.matches(PASSWORD, (String) row.get("password_hash"))).isTrue();

        verifyToken(token, PASSWORD, null).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(AccountService.INVALID_TOKEN_MESSAGE));
        verifyToken("x".repeat(43), PASSWORD, null).andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("가입: 한 토큰에 비밀번호를 5번 틀리면 그 토큰은 맞는 비밀번호로도 못 쓴다")
    void signup_verify_lockedAfterFiveWrongPasswords() throws Exception {
        String addr = email("guess");
        postJson("/api/account/signup", Map.of("email", addr, "password", PASSWORD, "nickname", "a"))
                .andExpect(status().isAccepted());
        String token = tokenIn(mails(1).getFirst());
        for (int i = 0; i < 5; i++) {
            verifyToken(token, "wrong-pass-" + i, null).andExpect(status().isBadRequest());
        }
        verifyToken(token, PASSWORD, null).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(AccountService.INVALID_TOKEN_MESSAGE));
        assertThat(userCount(addr)).isZero();
    }

    @Test
    @DisplayName("가입: 인증 메일을 받은 사이에 그 이메일이 다른 계정(카카오 인증)으로 확정됐으면 verify 는 409")
    void signup_emailTakenMeanwhile() throws Exception {
        String addr = email("meanwhile");
        postJson("/api/account/signup", Map.of("email", addr, "password", PASSWORD, "nickname", "하나"))
                .andExpect(status().isAccepted());
        String token = tokenIn(mails(1).getFirst());
        kakao(addr, true);
        verifyToken(token, PASSWORD, null).andExpect(status().isConflict());
        assertThat(userCount(addr)).isEqualTo(1);
        assertThat(userRow(addr).get("password_hash")).isNull();
    }

    @Test
    @DisplayName("가입: 인증된 계정이 있으면 '이미 가입됨' 메일 + reset-password 링크(요청 비밀번호는 버림)")
    void signup_existingVerifiedAccount() throws Exception {
        String addr = email("kv");
        kakao(addr, true);
        postJson("/api/account/signup", Map.of("email", addr, "password", PASSWORD, "nickname", "가로채기"))
                .andExpect(status().isAccepted());
        MimeMessage already = mails(1).getFirst();
        assertThat(already.getSubject()).contains("이미 가입된");
        assertThat(text(already)).contains("https://moyeolog.test/reset-password?token=");
        assertThat(text(already)).doesNotContain("purpose=");
        assertThat(userRow(addr).get("password_hash")).isNull();
    }

    @Test
    @DisplayName("가입·비밀번호 찾기: 미인증 이메일을 가진 기존 카카오 계정이 있으면 링크 없는 안내 메일만(202), 그 계정의 이메일은 그대로")
    void signup_unverifiedKakaoHolder_getsNotice() throws Exception {
        String addr = email("legacy");
        JsonNode holder = kakaoWithUnverifiedEmail(addr);
        postJson("/api/account/signup", Map.of("email", addr, "password", PASSWORD, "nickname", "새로"))
                .andExpect(status().isAccepted());
        MimeMessage notice = mails(1).getFirst();
        assertThat(notice.getSubject()).contains("카카오로 가입한 계정");
        assertThat(TOKEN.matcher(text(notice)).find()).isFalse();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM email_verifications WHERE email = ?", Integer.class, addr)).isZero();
        assertThat(jdbc.queryForObject("SELECT email FROM users WHERE custom_id = ?", String.class,
                holder.path("user").path("customId").asText())).isEqualTo(addr);

        String resetAddr = email("legacy-reset");
        kakaoWithUnverifiedEmail(resetAddr);
        postJson("/api/account/password-reset", Map.of("email", resetAddr)).andExpect(status().isAccepted());
        MimeMessage resetNotice = mails(2).get(1);
        assertThat(to(resetNotice)).isEqualTo(resetAddr);
        assertThat(TOKEN.matcher(text(resetNotice)).find()).isFalse();
    }

    @Test
    @DisplayName("가입: 카카오 없는 미인증 계정만 있으면 '사용 중' 이 아니다 → 가입 진행, 확정 때 그 계정의 email 은 비워진다")
    void signup_unverifiedNonKakaoHolderDoesNotBlock() throws Exception {
        String addr = email("squat");
        JsonNode squatter = kakaoWithUnverifiedEmail(addr);
        String squatterCustomId = squatter.path("user").path("customId").asText();
        jdbc.update("UPDATE users SET kakao_id = NULL WHERE custom_id = ?", squatterCustomId);
        postJson("/api/account/signup", Map.of("email", addr, "password", PASSWORD, "nickname", "주인"))
                .andExpect(status().isAccepted());
        MimeMessage mail = mails(1).getFirst();
        assertThat(mail.getSubject()).contains("확인해 주세요");
        verifyToken(tokenIn(mail), PASSWORD, null).andExpect(status().isOk());

        assertThat(userCount(addr)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT email FROM users WHERE custom_id = ?", String.class, squatterCustomId)).isNull();
    }

    @Test
    @DisplayName("가입: 형식 오류만 400 (이메일·8자 미만·72바이트 초과·빈 닉네임), 같은 이메일 1분 안 두 번째 요청은 202 지만 메일 없음")
    void signup_validation_andRateLimit() throws Exception {
        postJson("/api/account/signup", Map.of("email", "not-an-email", "password", PASSWORD, "nickname", "a"))
                .andExpect(status().isBadRequest());
        postJson("/api/account/signup", Map.of("email", email("v"), "password", "short", "nickname", "a"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("비밀번호는 8자 이상이어야 해요."));
        postJson("/api/account/signup", Map.of("email", email("v"), "password", "가".repeat(25), "nickname", "a"))
                .andExpect(status().isBadRequest()); // 75바이트
        postJson("/api/account/signup", Map.of("email", email("v"), "password", PASSWORD, "nickname", "\u200B "))
                .andExpect(status().isBadRequest());
        noMoreMailsThan(0);

        String addr = email("rate");
        postJson("/api/account/signup", Map.of("email", addr, "password", PASSWORD, "nickname", "a"))
                .andExpect(status().isAccepted());
        postJson("/api/account/signup", Map.of("email", addr, "password", PASSWORD, "nickname", "a"))
                .andExpect(status().isAccepted());
        mails(1);
        noMoreMailsThan(1);
    }

    @Test
    @DisplayName("발송 제한: 같은 이메일 1분 1통 / 이메일 비교는 정규화 값 정확 일치")
    void rateLimit_and_sameEmail_unit() {
        AccountService service = new AccountService(null, null, null, null, null);
        assertThat(service.allowSend("a@x.com")).isTrue();
        assertThat(service.allowSend("a@x.com")).isFalse();
        assertThat(service.allowSend("b@x.com")).isTrue();
        assertThat(AccountService.sameEmail("a@x.com", " A@X.com")).isTrue();
        assertThat(AccountService.sameEmail("a@example.com", "a@ex\u00e1mple.com")).isFalse();
        assertThat(AccountService.sameEmail("a@x.com", null)).isFalse();
    }

    // ─── 비밀번호 찾기 ───────────────────────────────────────────────

    @Test
    @DisplayName("비밀번호 찾기: 없는 이메일은 202 + 메일 없음. 인증된 카카오 계정은 링크로 처음 설정 → 모든 기기 로그아웃 + 알림 메일, 링크 1회용")
    void passwordReset_flow() throws Exception {
        postJson("/api/account/password-reset", Map.of("email", email("nobody"))).andExpect(status().isAccepted());
        noMoreMailsThan(0);

        String addr = email("reset");
        String accessToken = access(kakao(addr, true));
        jdbc.update("UPDATE users SET failed_login_count = 9, "
                + "login_locked_until = DATEADD('MINUTE', 10, CURRENT_TIMESTAMP) WHERE email = ?", addr);
        postJson("/api/account/password-reset", Map.of("email", addr.toUpperCase())).andExpect(status().isAccepted());
        String token = tokenIn(mails(1).getFirst());

        // 토큰을 /verify 로 쓰면 안 된다(소모되지도 않음)
        verifyToken(token, PASSWORD, null).andExpect(status().isBadRequest());
        postJson("/api/account/password-reset/confirm", Map.of("token", token, "newPassword", "1234567"))
                .andExpect(status().isBadRequest());

        Thread.sleep(1100); // tokens_valid_after 는 초 단위
        postJson("/api/account/password-reset/confirm", Map.of("token", token, "newPassword", PASSWORD))
                .andExpect(status().isNoContent());
        Map<String, Object> row = userRow(addr);
        assertThat(passwordEncoder.matches(PASSWORD, (String) row.get("password_hash"))).isTrue();
        assertThat(((Number) row.get("failed_login_count")).intValue()).isZero();
        assertThat(row.get("login_locked_until")).isNull();
        mockMvc.perform(get("/api/users/me").header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isUnauthorized());
        MimeMessage notice = mails(2).get(1);
        assertThat(to(notice)).isEqualTo(addr);
        assertThat(TOKEN.matcher(text(notice)).find()).isFalse();

        postJson("/api/account/password-reset/confirm", Map.of("token", token, "newPassword", "another-pass-2"))
                .andExpect(status().isBadRequest());
    }

    // ─── 로그인한 사용자 ─────────────────────────────────────────────

    @Test
    @DisplayName("PUT /me/password: 인증 이메일 없으면 400, 처음 설정은 204(로그아웃 없음), 바꿀 때는 현재 비밀번호 필수 + 모든 기기 로그아웃, 매번 알림 메일")
    void changePassword() throws Exception {
        JsonNode session = kakao(null, false);
        String accessToken = access(session);

        authJson(put("/api/users/me/password"), accessToken, Map.of("newPassword", PASSWORD))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("이메일을 먼저 인증해 주세요."));

        String addr = email("me");
        jdbc.update("UPDATE users SET email = ?, email_verified_at = CURRENT_TIMESTAMP WHERE custom_id = ?",
                addr, session.path("user").path("customId").asText());
        authJson(put("/api/users/me/password"), accessToken, Map.of("newPassword", PASSWORD))
                .andExpect(status().isNoContent());
        assertThat(to(mails(1).getFirst())).isEqualTo(addr);
        mockMvc.perform(get("/api/users/me").header("Authorization", "Bearer " + accessToken)).andExpect(status().isOk());

        authJson(put("/api/users/me/password"), accessToken, Map.of("newPassword", "next-password-3"))
                .andExpect(status().isBadRequest());
        authJson(put("/api/users/me/password"), accessToken, Map.of("currentPassword", "wrong-password", "newPassword", "next-password-3"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(AccountService.WRONG_CURRENT_PASSWORD_MESSAGE));
        // 지금과 같은 비밀번호로는 바꾸지 않는다(모든 기기 로그아웃도 없음)
        authJson(put("/api/users/me/password"), accessToken, Map.of("currentPassword", PASSWORD, "newPassword", PASSWORD))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(AccountService.SAME_PASSWORD_MESSAGE));
        mockMvc.perform(get("/api/users/me").header("Authorization", "Bearer " + accessToken)).andExpect(status().isOk());
        authJson(put("/api/users/me/password"), accessToken, Map.of("currentPassword", PASSWORD, "newPassword", "next-password-3"))
                .andExpect(status().isNoContent());
        assertThat(passwordEncoder.matches("next-password-3", (String) userRow(addr).get("password_hash"))).isTrue();
        mails(2);
        mockMvc.perform(get("/api/users/me").header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(put("/api/users/me/password").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("비밀번호 없는 계정: 카카오 로그인이 10분보다 오래됐으면 첫 비밀번호 설정·이메일 등록은 403, 다시 카카오 로그인하면 된다")
    void noPassword_requiresRecentKakaoLogin() throws Exception {
        String kakaoId = "kakao-" + UUID.randomUUID();
        String addr = email("stale");
        JsonNode session = sync(kakaoId, addr, true);
        String accessToken = access(session);
        String customId = session.path("user").path("customId").asText();
        jdbc.update("UPDATE users SET last_kakao_login_at = DATEADD('MINUTE', -11, CURRENT_TIMESTAMP) WHERE custom_id = ?", customId);

        authJson(put("/api/users/me/password"), accessToken, Map.of("newPassword", PASSWORD))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value(AccountService.KAKAO_RELOGIN_MESSAGE));
        authJson(post("/api/users/me/email"), accessToken, Map.of("email", email("stale-new")))
                .andExpect(status().isForbidden());
        jdbc.update("UPDATE users SET last_kakao_login_at = NULL WHERE custom_id = ?", customId);
        authJson(post("/api/users/me/email"), accessToken, Map.of("email", email("stale-new")))
                .andExpect(status().isForbidden());
        noMoreMailsThan(0);
        assertThat(userRow(addr).get("password_hash")).isNull();

        String fresh = access(sync(kakaoId, addr, true));
        authJson(put("/api/users/me/password"), fresh, Map.of("newPassword", PASSWORD)).andExpect(status().isNoContent());
        mails(1);
    }

    @Test
    @DisplayName("POST /me/email: 메일(purpose=email) → 그 사용자로 로그인한 요청만 확정(아니면 401) → 이메일 교체 + 예전 주소로 알림. 인증된 계정이 쓰는 이메일은 202 + 메일 없음")
    void emailChange() throws Exception {
        String oldAddr = email("old");
        String accessToken = access(kakao(oldAddr, true));
        String takenAddr = email("taken");
        kakao(takenAddr, true);
        String otherAccess = access(kakao(null, false));

        authJson(post("/api/users/me/email"), accessToken, Map.of("email", takenAddr)).andExpect(status().isAccepted());
        noMoreMailsThan(0);

        String newAddr = email("new");
        authJson(post("/api/users/me/email"), accessToken, Map.of("email", newAddr)).andExpect(status().isAccepted());
        MimeMessage mail = mails(1).getFirst();
        assertThat(to(mail)).isEqualTo(newAddr);
        String token = tokenIn(mail);
        assertThat(text(mail)).contains("&purpose=email&email=");
        assertThat(userRow(oldAddr).get("email_verified_at")).isNotNull(); // 인증 전까지 그대로

        verifyToken(token, null, null).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value(AccountService.EMAIL_LOGIN_REQUIRED_MESSAGE));
        verifyToken(token, null, otherAccess).andExpect(status().isUnauthorized());
        verifyToken(token, null, "not-a-jwt").andExpect(status().isUnauthorized());
        assertThat(userCount(newAddr)).isZero();

        verifyToken(token, null, accessToken)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.purpose").value("EMAIL"))
                .andExpect(jsonPath("$.email").value(newAddr));
        assertThat(userCount(oldAddr)).isZero();
        assertThat(userRow(newAddr).get("email_verified_at")).isNotNull();
        MimeMessage notice = mails(2).get(1);
        assertThat(to(notice)).isEqualTo(oldAddr);
        assertThat(TOKEN.matcher(text(notice)).find()).isFalse();
    }

    @Test
    @DisplayName("POST /me/email: 비밀번호가 있는 계정은 현재 비밀번호 필수(없거나 틀리면 400)")
    void emailChange_requiresCurrentPassword() throws Exception {
        JsonNode session = kakao(email("pw"), true);
        String accessToken = access(session);
        authJson(put("/api/users/me/password"), accessToken, Map.of("newPassword", PASSWORD)).andExpect(status().isNoContent());
        mails(1);

        String newAddr = email("pw-new");
        authJson(post("/api/users/me/email"), accessToken, Map.of("email", newAddr))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(AccountService.WRONG_CURRENT_PASSWORD_MESSAGE));
        authJson(post("/api/users/me/email"), accessToken, Map.of("email", newAddr, "currentPassword", "wrong-password"))
                .andExpect(status().isBadRequest());
        noMoreMailsThan(1);
        authJson(post("/api/users/me/email"), accessToken, Map.of("email", newAddr, "currentPassword", PASSWORD))
                .andExpect(status().isAccepted());
        assertThat(to(mails(2).get(1))).isEqualTo(newAddr);
    }

    @Test
    @DisplayName("POST /me/email: 사용자별 하루 5통")
    void emailChange_dailyLimitPerUser() throws Exception {
        String accessToken = access(kakao(null, false));
        for (int i = 0; i < 6; i++) {
            authJson(post("/api/users/me/email"), accessToken, Map.of("email", email("daily" + i))).andExpect(status().isAccepted());
        }
        mails(5);
        noMoreMailsThan(5);
    }

    @Test
    @DisplayName("EMAIL 인증 사이에 인증된 다른 계정이 그 이메일을 가져갔으면 409")
    void emailChange_takenMeanwhile() throws Exception {
        String accessToken = access(kakao(email("mine"), true));
        String target = email("race");
        authJson(post("/api/users/me/email"), accessToken, Map.of("email", target)).andExpect(status().isAccepted());
        String token = tokenIn(mails(1).getFirst());
        kakao(target, true);
        verifyToken(token, null, accessToken).andExpect(status().isConflict());
    }

    @Test
    @DisplayName("회원 탈퇴하면 그 사용자의 email_verifications 행이 지워진다")
    void deleteAccount_removesVerifications() throws Exception {
        JsonNode session = kakao(null, false);
        String accessToken = access(session);
        authJson(post("/api/users/me/email"), accessToken, Map.of("email", email("bye-new"))).andExpect(status().isAccepted());
        mails(1);
        String userId = session.path("user").path("id").asText();
        String countSql = "SELECT COUNT(*) FROM email_verifications WHERE user_id = ?";
        byte[] idBytes = uuidBytes(UUID.fromString(userId));
        assertThat(jdbc.queryForObject(countSql, Integer.class, (Object) idBytes)).isEqualTo(1);

        mockMvc.perform(delete("/api/users/me").header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isNoContent());
        assertThat(jdbc.queryForObject(countSql, Integer.class, (Object) idBytes)).isZero();
    }

    @Test
    @DisplayName("전체 상한: 시간당 200통을 넘는 메일은 버리지만, 보안 알림은 상한에 걸리지 않는다")
    void globalCap_excludesSecurityNotices() throws Exception {
        AccountMailer mailer = new AccountMailer(context.getBeanProvider(JavaMailSender.class), "smtp.test", "", "", "https://x");
        for (int i = 0; i < AccountMailer.MAX_PER_HOUR + 1; i++) {
            mailer.sendPasswordReset("cap" + i + "@example.com", "t");
        }
        mailer.sendPasswordChangedNotice("victim@example.com");
        mailer.sendEmailChangedNotice("victim-old@example.com");
        List<MimeMessage> sent = mails(AccountMailer.MAX_PER_HOUR + 2);
        noMoreMailsThan(AccountMailer.MAX_PER_HOUR + 2);
        assertThat(to(sent.get(sent.size() - 2))).isEqualTo("victim@example.com");
        assertThat(to(sent.getLast())).isEqualTo("victim-old@example.com");
    }

    @Test
    @DisplayName("메일 서버 설정(spring.mail.host)이 비어 있으면 503")
    void mailUnavailable() {
        AccountMailer mailer = new AccountMailer(context.getBeanProvider(JavaMailSender.class), "", "", "", "https://x");
        assertThatThrownBy(mailer::requireAvailable)
                .isInstanceOf(MailUnavailableException.class)
                .hasMessage("지금은 이메일을 보낼 수 없어요. 잠시 후 다시 시도해 주세요.");
        verify(mailSender, never()).send(any(MimeMessage.class));
    }

    private static byte[] uuidBytes(UUID id) {
        java.nio.ByteBuffer buffer = java.nio.ByteBuffer.allocate(16);
        buffer.putLong(id.getMostSignificantBits());
        buffer.putLong(id.getLeastSignificantBits());
        return buffer.array();
    }
}
