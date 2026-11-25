package com.moyeolog.moyelog_BE;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 비밀번호 로그인(+잠금), 카카오 연결·해제, sync 의 이메일 자동 연결 규칙 통합 테스트.
 */
@SpringBootTest
class AuthPasswordLinkIntegrationTest {

    private static final String SECRET_HEADER = "X-Internal-Secret";
    private static final String SECRET = "test-internal-secret";
    private static final String LOGIN_FAILED = "이메일 또는 비밀번호가 올바르지 않아요.";
    private static final String KAKAO_IN_USE = "이미 다른 계정에 연결된 카카오 계정이에요.";

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private com.moyeolog.moyelog_BE.service.AuthService authService;

    @Autowired
    private com.moyeolog.moyelog_BE.repository.UserRepository userRepository;

    @Autowired
    private org.springframework.transaction.support.TransactionTemplate transactionTemplate;

    private final ObjectMapper mapper = new ObjectMapper();
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    // ─── helpers ────────────────────────────────────────────────────

    private record Session(String userId, String customId, String accessToken, String refreshToken, JsonNode user) {
        static Session of(JsonNode json) {
            return new Session(json.path("user").path("id").asText(), json.path("user").path("customId").asText(),
                    json.path("accessToken").asText(), json.path("refreshToken").asText(), json.path("user"));
        }
    }

    private JsonNode json(MvcResult result) throws Exception {
        return mapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private ResultActions postInternal(String path, Object body) throws Exception {
        return mockMvc.perform(post(path).header(SECRET_HEADER, SECRET)
                .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(body)));
    }

    private Session sync(String kakaoId, String email, boolean verified) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("kakaoId", kakaoId);
        body.put("nickname", "사용자");
        if (email != null) {
            body.put("email", email);
        }
        body.put("emailVerified", verified);
        return Session.of(json(postInternal("/api/auth/sync", body).andExpect(status().isOk()).andReturn()));
    }

    private ResultActions login(String email, String password) throws Exception {
        return postInternal("/api/auth/login", Map.of("email", email, "password", password));
    }

    private ResultActions loginFrom(String email, String password, String ip) throws Exception {
        return postInternal("/api/auth/login", Map.of("email", email, "password", password, "clientIp", ip));
    }

    private int failedCount(Session s) {
        return jdbcTemplate.queryForObject("select failed_login_count from users where custom_id = ?",
                Integer.class, s.customId());
    }

    private ResultActions unlink(Session s, String currentPassword) throws Exception {
        var builder = delete("/api/users/me/kakao").header("Authorization", "Bearer " + s.accessToken());
        if (currentPassword != null) {
            builder.contentType(MediaType.APPLICATION_JSON)
                    .content(mapper.writeValueAsString(Map.of("currentPassword", currentPassword)));
        }
        return mockMvc.perform(builder);
    }

    private Session loginOk(String email, String password) throws Exception {
        return Session.of(json(login(email, password).andExpect(status().isOk()).andReturn()));
    }

    private void expectLoginFailed(ResultActions actions) throws Exception {
        actions.andExpect(status().isUnauthorized()).andExpect(jsonPath("$.message").value(LOGIN_FAILED));
    }

    private ResultActions linkKakao(String accessToken, String kakaoId, String email, boolean verified) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("accessToken", accessToken);
        body.put("kakaoId", kakaoId);
        if (email != null) {
            body.put("email", email);
        }
        body.put("emailVerified", verified);
        return postInternal("/api/auth/link-kakao", body);
    }

    private JsonNode me(String accessToken) throws Exception {
        return json(mockMvc.perform(get("/api/users/me").header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk()).andReturn());
    }

    private static String email(String prefix) {
        return prefix + "-" + UUID.randomUUID() + "@example.com";
    }

    /** 카카오로 만든 계정을 '이메일로 가입한 계정' 모양으로 바꾼다(kakao_id·kakao_linked_at 없음, 인증된 이메일 + 비밀번호). */
    private Session emailSignupAccount(String email, String password) throws Exception {
        Session s = sync("tmp-" + UUID.randomUUID(), email, true);
        jdbcTemplate.update("update users set kakao_id = null, kakao_linked_at = null, password_hash = ?, "
                + "email_verified_at = current_timestamp where custom_id = ?", passwordEncoder.encode(password), s.customId());
        return s;
    }

    private void setPassword(Session s, String password) {
        jdbcTemplate.update("update users set password_hash = ? where custom_id = ?",
                passwordEncoder.encode(password), s.customId());
    }

    // ─── 비밀번호 로그인 ───────────────────────────────────────────────

    @Test
    @DisplayName("login: 맞는 비밀번호면 sync 와 같은 모양, 이메일은 trim·대소문자 무시, 토큰으로 /me 가 된다")
    void login_success() throws Exception {
        String email = email("pw");
        Session signup = emailSignupAccount(email, "correct horse 1");

        Session s = loginOk("  " + email.toUpperCase() + " ", "correct horse 1");
        assertThat(s.userId()).isEqualTo(signup.userId());
        assertThat(s.accessToken()).isNotBlank();
        assertThat(s.refreshToken()).isNotBlank();
        assertThat(s.user().has("kakaoId")).isFalse();
        assertThat(s.user().path("hasPassword").asBoolean()).isTrue();
        assertThat(s.user().path("kakaoLinked").asBoolean()).isFalse();
        assertThat(s.user().path("emailVerified").asBoolean()).isTrue();

        JsonNode me = me(s.accessToken());
        assertThat(me.path("id").asText()).isEqualTo(signup.userId());
        assertThat(me.has("kakaoId")).isFalse();
    }

    @Test
    @DisplayName("login: 없는 계정·비밀번호 없는 계정·틀린 비밀번호·72바이트 초과·빈 값은 모두 같은 401, 내부 비밀 없으면 401")
    void login_failuresLookTheSame() throws Exception {
        String email = email("pwfail");
        emailSignupAccount(email, "right-password");
        String kakaoOnly = email("kakaoonly");
        sync("kakaoonly-" + UUID.randomUUID(), kakaoOnly, true);

        expectLoginFailed(login(email, "wrong-password"));
        expectLoginFailed(login(email("nobody"), "whatever-123"));
        expectLoginFailed(login(kakaoOnly, "whatever-123"));
        expectLoginFailed(login(email, "가".repeat(25)));          // 75바이트
        expectLoginFailed(login("", ""));
        expectLoginFailed(postInternal("/api/auth/login", Map.of()));
        expectLoginFailed(mockMvc.perform(post("/api/auth/login").header(SECRET_HEADER, SECRET)
                .contentType(MediaType.APPLICATION_JSON).content("{}")));

        mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("email", email, "password", "right-password"))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("login: 같은 (이메일, IP) 로 10회 연속 실패하면 그 조합만 차단(맞는 비밀번호도 같은 401), 다른 IP 는 된다. 차단 중 시도는 계정 횟수에 안 들어간다")
    void login_pairLock() throws Exception {
        String email = email("pairlock");
        Session account = emailSignupAccount(email, "right-password");
        String attacker = "203.0.113." + (int) (Math.random() * 200);
        String victimIp = "198.51.100.7";

        for (int i = 0; i < 9; i++) {
            expectLoginFailed(loginFrom(email, "wrong-" + i, attacker));
        }
        // 성공하면 계정 횟수도, 그 조합의 횟수도 0
        loginFrom(email, "right-password", attacker).andExpect(status().isOk());
        assertThat(failedCount(account)).isZero();

        for (int i = 0; i < 10; i++) {
            expectLoginFailed(loginFrom(email, "wrong-" + i, attacker));
        }
        assertThat(failedCount(account)).isEqualTo(10);
        expectLoginFailed(loginFrom(email, "right-password", attacker));
        expectLoginFailed(loginFrom(email, "wrong-again", attacker));
        assertThat(failedCount(account)).isEqualTo(10);
        assertThat(jdbcTemplate.queryForObject("select login_locked_until from users where custom_id = ?",
                java.sql.Timestamp.class, account.customId())).isNull();

        // 다른 IP 의 주인은 그대로 들어간다
        loginFrom(email, "right-password", victimIp).andExpect(status().isOk());
        assertThat(failedCount(account)).isZero();

        // 없는 이메일도 같은 응답
        String nobody = email("pairnobody");
        for (int i = 0; i < 12; i++) {
            expectLoginFailed(loginFrom(nobody, "wrong-" + i, attacker));
        }
    }

    @Test
    @DisplayName("login: 계정 단위로 연속 100회 실패(여러 IP)하면 10분 잠금, 풀리면 다시 되고 실패 횟수는 0")
    void login_accountLock() throws Exception {
        String email = email("acctlock");
        Session account = emailSignupAccount(email, "right-password");

        for (int i = 0; i < 100; i++) {
            expectLoginFailed(loginFrom(email, "wrong", "10.1." + (i / 200) + "." + (i % 200)));
        }
        assertThat(jdbcTemplate.queryForObject("select login_locked_until from users where custom_id = ?",
                java.sql.Timestamp.class, account.customId())).isNotNull();
        expectLoginFailed(loginFrom(email, "right-password", "10.2.0.1"));

        jdbcTemplate.update("update users set login_locked_until = ? where custom_id = ?",
                java.sql.Timestamp.valueOf(java.time.LocalDateTime.now().minusSeconds(1)), account.customId());
        loginFrom(email, "right-password", "10.2.0.1").andExpect(status().isOk());
        assertThat(jdbcTemplate.queryForObject("select login_locked_until from users where custom_id = ?",
                java.sql.Timestamp.class, account.customId())).isNull();
        assertThat(failedCount(account)).isZero();
    }

    // ─── 카카오 연결 ──────────────────────────────────────────────────

    @Test
    @DisplayName("link-kakao: 이메일 가입 계정에 카카오를 붙이면 같은 사용자의 새 토큰, 이후 카카오 로그인도 같은 계정")
    void linkKakao_success() throws Exception {
        String email = email("link");
        Session signup = emailSignupAccount(email, "right-password");
        Session s = loginOk(email, "right-password");
        String kakaoId = "link-" + UUID.randomUUID();

        Session linked = Session.of(json(linkKakao(s.accessToken(), kakaoId, email("other"), true)
                .andExpect(status().isOk()).andReturn()));
        assertThat(linked.userId()).isEqualTo(signup.userId());
        assertThat(linked.accessToken()).isNotEqualTo(s.accessToken());
        assertThat(linked.user().path("kakaoLinked").asBoolean()).isTrue();
        assertThat(linked.user().has("kakaoId")).isFalse();
        // 이메일이 이미 있으므로 카카오 이메일로 바꾸지 않는다
        assertThat(linked.user().path("email").asText()).isEqualTo(email);
        assertThat(me(linked.accessToken()).path("kakaoLinked").asBoolean()).isTrue();

        // 같은 카카오로 다시 연결해도 200 (멱등)
        linkKakao(linked.accessToken(), kakaoId, null, false).andExpect(status().isOk());
        assertThat(sync(kakaoId, null, false).userId()).isEqualTo(signup.userId());
    }

    @Test
    @DisplayName("link-kakao: 다른 계정의 카카오면 409, 이미 다른 카카오가 있으면 409, 잘못된 토큰·폐기된 토큰·리프레시 토큰·비밀 없음은 401")
    void linkKakao_rejections() throws Exception {
        String email = email("linkrej");
        emailSignupAccount(email, "right-password");
        Session s = loginOk(email, "right-password");

        String takenKakao = "taken-" + UUID.randomUUID();
        Session other = sync(takenKakao, null, false);
        linkKakao(s.accessToken(), takenKakao, null, false)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(KAKAO_IN_USE));
        // 다른 계정은 그대로
        assertThat(sync(takenKakao, null, false).userId()).isEqualTo(other.userId());

        // 카카오로 가입한 사용자에게 다른 카카오를 붙이려 하면 409
        linkKakao(other.accessToken(), "second-" + UUID.randomUUID(), null, false)
                .andExpect(status().isConflict());

        linkKakao("not-a-token", "x-" + UUID.randomUUID(), null, false).andExpect(status().isUnauthorized());
        linkKakao(null, "x-" + UUID.randomUUID(), null, false).andExpect(status().isUnauthorized());
        linkKakao(s.refreshToken(), "x-" + UUID.randomUUID(), null, false).andExpect(status().isUnauthorized());
        Map<String, Object> body = Map.of("accessToken", s.accessToken(), "kakaoId", "x-" + UUID.randomUUID());
        mockMvc.perform(post("/api/auth/link-kakao").contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(body)))
                .andExpect(status().isUnauthorized());
        linkKakao(s.accessToken(), " ", null, false).andExpect(status().isBadRequest());

        // 로그아웃한 액세스 토큰
        postInternal("/api/auth/logout", Map.of("accessToken", s.accessToken())).andExpect(status().isNoContent());
        linkKakao(s.accessToken(), "x-" + UUID.randomUUID(), null, false).andExpect(status().isUnauthorized());

        // 모든 기기 로그아웃 이전 토큰
        Session s2 = loginOk(email, "right-password");
        mockMvc.perform(delete("/api/users/me/sessions").header("Authorization", "Bearer " + s2.accessToken()))
                .andExpect(status().isNoContent());
        linkKakao(s2.accessToken(), "x-" + UUID.randomUUID(), null, false).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("link-kakao: 카카오 이메일이 인증됐고 계정 이메일과 같으면 인증 표시, 이메일이 비어 있으면 인증된 카카오 이메일로 채운다")
    void linkKakao_fillsVerifiedEmail() throws Exception {
        // 이메일 없는 카카오 계정 + 비밀번호(설정 흐름을 흉내)
        Session noEmail = sync("noemail-" + UUID.randomUUID(), null, false);
        jdbcTemplate.update("update users set kakao_id = null where custom_id = ?", noEmail.customId());
        String kakaoEmail = email("kakaomail");
        Session linked = Session.of(json(linkKakao(noEmail.accessToken(), "fill-" + UUID.randomUUID(), kakaoEmail, true)
                .andExpect(status().isOk()).andReturn()));
        assertThat(linked.user().path("email").asText()).isEqualTo(kakaoEmail);
        assertThat(linked.user().path("emailVerified").asBoolean()).isTrue();
    }

    // ─── sync 규칙 ──────────────────────────────────────────────────

    @Test
    @DisplayName("sync: 인증된 카카오 이메일이면 저장하고 email_verified_at 을 채운다. 인증되지 않은 이메일은 저장하지 않는다")
    void sync_marksEmailVerified() throws Exception {
        Session verified = sync("v-" + UUID.randomUUID(), email("verified"), true);
        assertThat(verified.user().path("emailVerified").asBoolean()).isTrue();
        assertThat(verified.user().path("kakaoLinked").asBoolean()).isTrue();
        assertThat(verified.user().path("hasPassword").asBoolean()).isFalse();

        String kakaoId = "u-" + UUID.randomUUID();
        String email = email("unverified");
        Session unverified = sync(kakaoId, email, false);
        // 인증되지 않은 카카오 이메일은 저장하지 않는다
        assertThat(unverified.user().path("email").isNull()).isTrue();
        assertThat(unverified.user().path("emailVerified").asBoolean()).isFalse();
        // 나중에 카카오가 인증됨으로 알려 주면 채운다(대소문자는 소문자로)
        JsonNode later = sync(kakaoId, email.toUpperCase(), true).user();
        assertThat(later.path("email").asText()).isEqualTo(email);
        assertThat(later.path("emailVerified").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("sync: 이메일 가입 계정은 그 이메일이 인증돼 있을 때만 같은 인증된 카카오 이메일로 자동 연결된다")
    void sync_autoLinksVerifiedEmailSignupAccount() throws Exception {
        String unverifiedEmail = email("signup-unverified");
        Session unverifiedAccount = emailSignupAccount(unverifiedEmail, "right-password");
        jdbcTemplate.update("update users set email_verified_at = null where custom_id = ?", unverifiedAccount.customId());
        Session separate = sync("auto-" + UUID.randomUUID(), unverifiedEmail, true);
        assertThat(separate.userId()).isNotEqualTo(unverifiedAccount.userId());
        // 미인증 계정은 그 이메일을 '사용 중' 으로 잡고 있지 못한다: 인증한 쪽이 가져간다
        assertThat(separate.user().path("email").asText()).isEqualTo(unverifiedEmail);
        assertThat(jdbcTemplate.queryForObject("select email from users where custom_id = ?",
                String.class, unverifiedAccount.customId())).isNull();

        String email = email("signup-verified");
        Session account = emailSignupAccount(email, "right-password");
        // 인증되지 않은 카카오 이메일로는 붙지 않는다
        assertThat(sync("auto-" + UUID.randomUUID(), email, false).userId()).isNotEqualTo(account.userId());
        String kakaoId = "auto-" + UUID.randomUUID();
        Session linked = sync(kakaoId, email, true);
        assertThat(linked.userId()).isEqualTo(account.userId());
        assertThat(linked.user().path("hasPassword").asBoolean()).isTrue();
        assertThat(linked.user().path("kakaoLinked").asBoolean()).isTrue();
        // 비밀번호 로그인도 그대로 된다
        assertThat(loginOk(email, "right-password").userId()).isEqualTo(account.userId());
    }

    @Test
    @DisplayName("sync·link: 인증된 이메일이 확정되면 같은 이메일을 가진 미인증 계정의 이메일을 비운다. 인증된 계정의 이메일은 가져가지 못한다")
    void verifiedEmailClaimsFromUnverifiedAccounts() throws Exception {
        // 예전 데이터: 인증 안 된 이메일을 가진, 이미 카카오에 연결된 계정
        String email = email("squatted");
        Session squatter = sync("squatter-" + UUID.randomUUID(), null, false);
        jdbcTemplate.update("update users set email = ? where custom_id = ?", email, squatter.customId());

        Session owner = sync("owner-" + UUID.randomUUID(), email, true);
        assertThat(owner.userId()).isNotEqualTo(squatter.userId());
        assertThat(owner.user().path("email").asText()).isEqualTo(email);
        assertThat(owner.user().path("emailVerified").asBoolean()).isTrue();
        assertThat(me(squatter.accessToken()).path("email").isNull()).isTrue();

        // 인증된 주인의 이메일은 다른 카카오(인증됨)로도 가져가지 못한다
        Session other = sync("other-" + UUID.randomUUID(), email, true);
        assertThat(other.user().path("email").isNull()).isTrue();
        assertThat(me(owner.accessToken()).path("email").asText()).isEqualTo(email);

        // link-kakao 도 같은 규칙
        String email2 = email("squatted2");
        Session squatter2 = sync("squatter2-" + UUID.randomUUID(), null, false);
        jdbcTemplate.update("update users set email = ? where custom_id = ?", email2, squatter2.customId());
        Session noEmail = sync("noemail2-" + UUID.randomUUID(), null, false);
        jdbcTemplate.update("update users set kakao_id = null where custom_id = ?", noEmail.customId());
        Session linked = Session.of(json(linkKakao(noEmail.accessToken(), "link2-" + UUID.randomUUID(), email2, true)
                .andExpect(status().isOk()).andReturn()));
        assertThat(linked.user().path("email").asText()).isEqualTo(email2);
        assertThat(me(squatter2.accessToken()).path("email").isNull()).isTrue();
    }

    @Test
    @DisplayName("claimVerifiedEmail: 인증된 다른 계정이 쓰는 이메일이면 409, 아니면 미인증 계정을 비우고 확정")
    void claimVerifiedEmail_direct() throws Exception {
        String email = email("claim");
        Session verified = sync("claimv-" + UUID.randomUUID(), email, true);
        Session target = sync("claimt-" + UUID.randomUUID(), null, false);
        UUID targetId = UUID.fromString(target.userId());

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(tx -> {
                    var user = userRepository.findById(targetId).orElseThrow();
                    authService.claimVerifiedEmail(user, email, java.time.LocalDateTime.now());
                }))
                .isInstanceOf(com.moyeolog.moyelog_BE.exception.ConflictException.class);
        assertThat(me(verified.accessToken()).path("email").asText()).isEqualTo(email);

        String free = email("claimfree");
        Session squatter = sync("claims-" + UUID.randomUUID(), null, false);
        jdbcTemplate.update("update users set email = ? where custom_id = ?", free, squatter.customId());
        transactionTemplate.executeWithoutResult(tx -> {
            var user = userRepository.findById(targetId).orElseThrow();
            authService.claimVerifiedEmail(user, free, java.time.LocalDateTime.now());
        });
        assertThat(me(target.accessToken()).path("email").asText()).isEqualTo(free);
        assertThat(me(target.accessToken()).path("emailVerified").asBoolean()).isTrue();
        assertThat(me(squatter.accessToken()).path("email").isNull()).isTrue();
    }

    private java.sql.Timestamp lastKakaoLogin(Session s) {
        return jdbcTemplate.queryForObject("select last_kakao_login_at from users where custom_id = ?",
                java.sql.Timestamp.class, s.customId());
    }

    @Test
    @DisplayName("last_kakao_login_at: sync·link-kakao 가 성공할 때마다 채우고, 비밀번호 로그인·실패한 연결은 바꾸지 않는다")
    void lastKakaoLoginAt() throws Exception {
        String kakaoId = "lk-" + UUID.randomUUID();
        Session s = sync(kakaoId, null, false);
        assertThat(lastKakaoLogin(s)).isNotNull();

        java.sql.Timestamp old = java.sql.Timestamp.valueOf(java.time.LocalDateTime.now().minusHours(1));
        jdbcTemplate.update("update users set last_kakao_login_at = ? where custom_id = ?", old, s.customId());
        sync(kakaoId, null, false);
        assertThat(lastKakaoLogin(s)).isAfter(old);

        // 이메일 가입 계정: 비밀번호 로그인은 바꾸지 않고, 연결이 실패해도 바꾸지 않으며, 성공하면 채운다
        String email = email("lk");
        Session signup = emailSignupAccount(email, "right-password");
        jdbcTemplate.update("update users set last_kakao_login_at = null where custom_id = ?", signup.customId());
        Session pw = loginOk(email, "right-password");
        assertThat(lastKakaoLogin(signup)).isNull();
        linkKakao(pw.accessToken(), kakaoId, null, false).andExpect(status().isConflict());
        assertThat(lastKakaoLogin(signup)).isNull();
        linkKakao(pw.accessToken(), "lk2-" + UUID.randomUUID(), null, false).andExpect(status().isOk());
        assertThat(lastKakaoLogin(signup)).isNotNull();
    }

    // ─── 카카오 해제 ──────────────────────────────────────────────────

    @Test
    @DisplayName("DELETE /me/kakao: 비밀번호가 없으면 400, 현재 비밀번호가 틀리면 400, 맞으면 해제되고 그 카카오로 다시 로그인하면 새 계정(자동으로 다시 붙지 않음)")
    void unlinkKakao() throws Exception {
        String email = email("unlink");
        String kakaoId = "unlink-" + UUID.randomUUID();
        Session s = sync(kakaoId, email, true);

        unlink(s, "anything-123")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("비밀번호를 먼저 설정해 주세요."));
        mockMvc.perform(delete("/api/users/me/kakao")).andExpect(status().isUnauthorized());

        setPassword(s, "right-password");
        // 현재 비밀번호가 없거나 틀리면 400
        for (String bad : new String[]{null, "", "wrong-password", "가".repeat(25)}) {
            unlink(s, bad)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").value("현재 비밀번호가 올바르지 않아요."));
        }
        assertThat(me(s.accessToken()).path("kakaoLinked").asBoolean()).isTrue();
        unlink(s, "right-password")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kakaoLinked").value(false))
                .andExpect(jsonPath("$.hasPassword").value(true))
                .andExpect(jsonPath("$.kakaoId").doesNotExist());
        assertThat(jdbcTemplate.queryForObject("select kakao_linked_at from users where custom_id = ?",
                java.sql.Timestamp.class, s.customId())).isNotNull();

        // 같은 카카오 + 같은 인증 이메일로 와도 해제한 계정에 다시 붙지 않는다
        Session again = sync(kakaoId, email, true);
        assertThat(again.userId()).isNotEqualTo(s.userId());
        // 원래 계정은 비밀번호로 들어가 카카오를 다시 연결할 수 있다(새 계정이 그 카카오를 쓰고 있으면 409)
        Session byPassword = loginOk(email, "right-password");
        assertThat(byPassword.userId()).isEqualTo(s.userId());
        linkKakao(byPassword.accessToken(), kakaoId, null, false).andExpect(status().isConflict());
        linkKakao(byPassword.accessToken(), "relink-" + UUID.randomUUID(), null, false).andExpect(status().isOk());
    }
}
