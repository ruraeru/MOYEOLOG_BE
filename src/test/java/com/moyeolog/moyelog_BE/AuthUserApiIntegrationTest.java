package com.moyeolog.moyelog_BE;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.AbstractMockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 인증·계정·사용자 API (CONTRACTS.md 1·2절) 통합 테스트.
 */
@SpringBootTest
class AuthUserApiIntegrationTest {

    private static final String SECRET_HEADER = "X-Internal-Secret";
    private static final String SECRET = "test-internal-secret";
    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0, 1, 2, 3};

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Value("${jwt.secret}")
    private String jwtSecret;

    @Value("${file.upload-dir}")
    private String uploadDir;

    private final ObjectMapper mapper = new ObjectMapper();
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    // ─── helpers ────────────────────────────────────────────────────

    private record Session(String userId, String customId, String accessToken, String refreshToken, JsonNode user) {
    }

    private Session sync(Map<String, Object> body) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/sync")
                        .header(SECRET_HEADER, SECRET)
                        .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode json = json(result);
        return new Session(json.path("user").path("id").asText(), json.path("user").path("customId").asText(),
                json.path("accessToken").asText(), json.path("refreshToken").asText(), json.path("user"));
    }

    private Session login(String name) throws Exception {
        return sync(Map.of("kakaoId", name + "-" + UUID.randomUUID(), "nickname", name));
    }

    private Session syncWith(String kakaoId, String email, boolean verified) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("kakaoId", kakaoId);
        body.put("nickname", "사용자");
        if (email != null) {
            body.put("email", email);
        }
        body.put("emailVerified", verified);
        return sync(body);
    }

    private JsonNode json(MvcResult result) throws Exception {
        return mapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private <B extends AbstractMockHttpServletRequestBuilder<B>> B auth(B builder, Session session) {
        return builder.header("Authorization", "Bearer " + session.accessToken());
    }

    private MockMultipartHttpServletRequestBuilder putMe(Object user) throws Exception {
        MockMultipartHttpServletRequestBuilder builder = multipart("/api/users/me");
        if (user != null) {
            builder.file(new MockMultipartFile("user", "", "application/json", mapper.writeValueAsBytes(user)));
        }
        builder.with(r -> {
            r.setMethod("PUT");
            return r;
        });
        return builder;
    }

    private String bearer(String token) {
        return "Bearer " + token;
    }

    private javax.crypto.SecretKey key() {
        return io.jsonwebtoken.security.Keys.hmacShaKeyFor(io.jsonwebtoken.io.Decoders.BASE64.decode(jwtSecret));
    }

    private Path uploaded(String url) {
        return Paths.get(uploadDir).resolve(url.substring("/uploads/".length()));
    }

    // ─── sync ───────────────────────────────────────────────────────

    @Test
    @DisplayName("sync: 닉네임은 보이지 않는 문자를 빼고(ZWJ·이체 선택자는 남김) 보이는 글자 20자로 자른다 (grapheme 경계), kakaoId 는 응답에 없다")
    void sync_nickname() throws Exception {
        Session a = sync(Map.of("kakaoId", "nick-" + UUID.randomUUID(), "nickname", "가".repeat(19) + "🐻🐻"));
        assertThat(a.user().path("nickname").asText()).isEqualTo("가".repeat(19) + "🐻");
        assertThat(a.user().has("kakaoId")).isFalse();
        assertThat(a.user().path("emailVisible").asBoolean()).isFalse();

        Session bears = sync(Map.of("kakaoId", "nick-" + UUID.randomUUID(), "nickname", "🐻".repeat(25)));
        assertThat(bears.user().path("nickname").asText()).isEqualTo("🐻".repeat(20));

        Session zw = sync(Map.of("kakaoId", "nick-" + UUID.randomUUID(), "nickname", " 최​도현\n "));
        assertThat(zw.user().path("nickname").asText()).isEqualTo("최도현");

        // ZWJ·이체 선택자는 지우지 않고, 자를 때 grapheme 경계에서 자른다
        Session dev = sync(Map.of("kakaoId", "nick-" + UUID.randomUUID(), "nickname", "👩\u200D💻 개발자"));
        assertThat(dev.user().path("nickname").asText()).isEqualTo("👩\u200D💻 개발자");
        String family = "👨\u200D👩\u200D👧";
        Session fam = sync(Map.of("kakaoId", "nick-" + UUID.randomUUID(), "nickname", "가".repeat(19) + family + family));
        assertThat(fam.user().path("nickname").asText()).isEqualTo("가".repeat(19) + family);
        Session heart = sync(Map.of("kakaoId", "nick-" + UUID.randomUUID(), "nickname", "\u200D\uFE0F"));
        assertThat(heart.user().path("nickname").asText()).startsWith("사용자_");

        String kakaoId = "nick-" + UUID.randomUUID();
        Session blank = sync(Map.of("kakaoId", kakaoId, "nickname", "​​"));
        assertThat(blank.user().path("nickname").asText()).isEqualTo("사용자_" + kakaoId.substring(kakaoId.length() - 4));

        Integer linked = jdbcTemplate.queryForObject(
                "select count(*) from users where custom_id = ? and kakao_linked_at is not null", Integer.class, a.customId());
        assertThat(linked).isEqualTo(1);
    }

    @Test
    @DisplayName("sync: 다른 카카오 계정이 같은 이메일로 와도 이미 연결된 계정은 가져가지 못한다")
    void sync_emailDoesNotTakeOverLinkedAccount() throws Exception {
        String email = "victim-" + UUID.randomUUID() + "@example.com";
        String victimKakao = "victim-" + UUID.randomUUID();
        Session victim = syncWith(victimKakao, email, true);
        assertThat(victim.user().path("email").asText()).isEqualTo(email);

        // 대소문자만 다른 같은 이메일, 인증됨 → 별도 계정, 이메일은 비움
        Session intruder = syncWith("intruder-" + UUID.randomUUID(), email.toUpperCase(), true);
        assertThat(intruder.userId()).isNotEqualTo(victim.userId());
        assertThat(intruder.user().path("email").isNull()).isTrue();

        // 원래 사용자는 이메일 없이 다시 로그인해도 원래 계정
        Session again = syncWith(victimKakao, null, false);
        assertThat(again.userId()).isEqualTo(victim.userId());
        String storedKakao = jdbcTemplate.queryForObject(
                "select kakao_id from users where custom_id = ?", String.class, victim.customId());
        assertThat(storedKakao).isEqualTo(victimKakao);
    }

    @Test
    @DisplayName("sync: 아직 연결된 적 없는 예전 계정은 검증된 이메일일 때 한 번만 연결된다")
    void sync_legacyAccountLinksOnceWithVerifiedEmail() throws Exception {
        String email = "legacy-" + UUID.randomUUID() + "@example.com";
        Session legacy = syncWith("old-app-" + UUID.randomUUID(), email, true);
        jdbcTemplate.update("update users set kakao_linked_at = null where custom_id = ?", legacy.customId());

        // 검증되지 않은 이메일 → 새 계정 (이메일은 비움)
        Session unverified = syncWith("unverified-" + UUID.randomUUID(), email, false);
        assertThat(unverified.userId()).isNotEqualTo(legacy.userId());
        assertThat(unverified.user().path("email").isNull()).isTrue();

        // 검증된 이메일 → 예전 계정에 연결
        String newKakao = "new-app-" + UUID.randomUUID();
        Session linked = syncWith(newKakao, email, true);
        assertThat(linked.userId()).isEqualTo(legacy.userId());

        // 이미 연결됐으므로 다른 카카오 계정은 다시 가져가지 못한다
        Session other = syncWith("other-" + UUID.randomUUID(), email, true);
        assertThat(other.userId()).isNotEqualTo(legacy.userId());
        assertThat(syncWith(newKakao, null, false).userId()).isEqualTo(legacy.userId());
    }

    // ─── tokens ─────────────────────────────────────────────────────

    @Test
    @DisplayName("토큰: exp 가 없는 토큰은 typ 이 access 여도 거부")
    void tokenWithoutExpIsRejected() throws Exception {
        Session session = login("noexp");
        String noExpAccess = io.jsonwebtoken.Jwts.builder().subject(session.userId()).claim("typ", "access")
                .id("abc123").issuedAt(new Date()).signWith(key()).compact();
        String noExpLegacy = io.jsonwebtoken.Jwts.builder().subject(session.userId()).signWith(key()).compact();
        mockMvc.perform(get("/api/users/me").header("Authorization", bearer(noExpAccess)))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/users/me").header("Authorization", bearer(noExpLegacy)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("logout: 내부 비밀 필요, 리프레시 토큰 폐기 + 액세스 토큰 거부, 다시 불러도 204")
    void logout() throws Exception {
        Session session = login("logout");
        Session otherDevice = sync(Map.of("kakaoId", jdbcTemplate.queryForObject(
                "select kakao_id from users where custom_id = ?", String.class, session.customId()), "nickname", "x"));
        String body = mapper.writeValueAsString(Map.of(
                "refreshToken", session.refreshToken(), "accessToken", session.accessToken()));

        mockMvc.perform(post("/api/auth/logout").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/auth/logout").header(SECRET_HEADER, SECRET)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isNoContent());
        mockMvc.perform(post("/api/auth/logout").header(SECRET_HEADER, SECRET)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isNoContent());
        mockMvc.perform(post("/api/auth/logout").header(SECRET_HEADER, SECRET)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isNoContent());

        mockMvc.perform(auth(get("/api/users/me"), session)).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/auth/refresh").contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("refreshToken", session.refreshToken()))))
                .andExpect(status().isUnauthorized());
        // 다른 기기 세션은 그대로
        mockMvc.perform(auth(get("/api/users/me"), otherDevice)).andExpect(status().isOk());
    }

    @Test
    @DisplayName("세션: 목록과 current 표시, 모든 기기 로그아웃 뒤에는 기존 토큰 전부 거부")
    void sessionsAndLogoutAll() throws Exception {
        Session a = login("sessions");
        String kakaoId = jdbcTemplate.queryForObject("select kakao_id from users where custom_id = ?", String.class, a.customId());
        Session b = sync(Map.of("kakaoId", kakaoId, "nickname", "x"));

        JsonNode sessions = json(mockMvc.perform(auth(get("/api/users/me/sessions"), a)
                        .header("X-Refresh-Token", b.refreshToken()))
                .andExpect(status().isOk()).andReturn());
        assertThat(sessions.size()).isEqualTo(2);
        int current = 0;
        for (JsonNode s : sessions) {
            assertThat(s.path("id").isNumber()).isTrue();
            assertThat(s.path("createdAt").isTextual()).isTrue();
            assertThat(s.path("lastUsedAt").isTextual()).isTrue();
            if (s.path("current").asBoolean()) {
                current++;
            }
        }
        assertThat(current).isEqualTo(1);

        mockMvc.perform(auth(delete("/api/users/me/sessions"), a)).andExpect(status().isNoContent());
        mockMvc.perform(auth(get("/api/users/me"), a)).andExpect(status().isUnauthorized());
        mockMvc.perform(auth(get("/api/users/me"), b)).andExpect(status().isUnauthorized());
        for (String refresh : List.of(a.refreshToken(), b.refreshToken())) {
            mockMvc.perform(post("/api/auth/refresh").contentType(MediaType.APPLICATION_JSON)
                            .content(mapper.writeValueAsString(Map.of("refreshToken", refresh))))
                    .andExpect(status().isUnauthorized());
        }
        Integer rows = jdbcTemplate.queryForObject(
                "select count(*) from refresh_tokens where user_id = (select id from users where custom_id = ?)",
                Integer.class, a.customId());
        assertThat(rows).isZero();
    }

    // ─── /api/users/me ──────────────────────────────────────────────

    @Test
    @DisplayName("내 정보: 계약 모양, 보낸 필드만 수정, 닉네임·자기소개 검증")
    void updateMe() throws Exception {
        Session s = login("me");
        mockMvc.perform(auth(get("/api/users/me"), s)).andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(s.userId()))
                .andExpect(jsonPath("$.customId").value(s.customId()))
                .andExpect(jsonPath("$.emailVisible").value(false))
                .andExpect(jsonPath("$.createdAt").isString())
                .andExpect(jsonPath("$.hasPassword").value(false))
                .andExpect(jsonPath("$.kakaoLinked").value(true))
                .andExpect(jsonPath("$.emailVerified").value(false))
                .andExpect(jsonPath("$.kakaoId").doesNotExist());

        mockMvc.perform(auth(putMe(Map.of("nickname", "원래닉", "bio", "원래 소개")), s)).andExpect(status().isOk());

        // 닉네임만 보내면 자기소개는 그대로
        mockMvc.perform(auth(putMe(Map.of("nickname", "  새닉  ")), s)).andExpect(status().isOk())
                .andExpect(jsonPath("$.nickname").value("새닉"))
                .andExpect(jsonPath("$.bio").value("원래 소개"));
        // 자기소개만, 이메일 공개만
        mockMvc.perform(auth(putMe(Map.of("bio", "🐻".repeat(200))), s)).andExpect(status().isOk())
                .andExpect(jsonPath("$.nickname").value("새닉"));
        mockMvc.perform(auth(putMe(Map.of("emailVisible", true)), s)).andExpect(status().isOk())
                .andExpect(jsonPath("$.emailVisible").value(true))
                .andExpect(jsonPath("$.bio").value("🐻".repeat(200)));

        for (String bad : List.of("     ", "", "도", "가".repeat(21), "\u200B", "\u200B\u200B\u200B", "최\n도현\t!",
                "a\u200Bb", "\uFEFF이름", "\u202E이름", "이름\u2066", "\u200D\u200D", "\uFE0F\uFE0F", "\u200D \uFE0F",
                "👩\u200D💻".repeat(21))) {
            mockMvc.perform(auth(putMe(Map.of("nickname", bad)), s))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").isString())
                    .andExpect(jsonPath("$.status").value(400));
        }
        // U+200B 만으로 된 닉네임은 400
        mockMvc.perform(auth(putMe(Map.of("nickname", "\u200B")), s))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("닉네임에 쓸 수 없는 문자가 들어 있어요."));
        // ZWJ·이체 선택자가 든 이모지는 그대로 저장 (길이는 보이는 글자 기준)
        for (String good : List.of("👩\u200D💻 개발자", "❤\uFE0F 하트", "🏳\uFE0F\u200D🌈 무지개", "👨\u200D👩\u200D👧 가족",
                "👩\u200D💻❤\uFE0F", "👨\u200D👩\u200D👧".repeat(20), "a\u200Db")) {
            mockMvc.perform(auth(putMe(Map.of("nickname", good)), s)).andExpect(status().isOk())
                    .andExpect(jsonPath("$.nickname").value(good));
            mockMvc.perform(auth(get("/api/users/me"), s)).andExpect(jsonPath("$.nickname").value(good));
        }
        mockMvc.perform(auth(putMe(Map.of("nickname", "🐻".repeat(20))), s)).andExpect(status().isOk());
        mockMvc.perform(auth(putMe(Map.of("bio", "가".repeat(201))), s))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("자기소개는 200자까지 쓸 수 있어요."));
        mockMvc.perform(auth(get("/api/users/me"), s))
                .andExpect(jsonPath("$.nickname").value("🐻".repeat(20)));
    }

    @Test
    @DisplayName("프로필 사진: 삭제하면 null + 파일 삭제, 동시에 바꿔도 마지막 사진 파일 하나만 남는다")
    void profileImage() throws Exception {
        Session s = login("photo");
        String url = json(mockMvc.perform(auth(putMe(null)
                        .file(new MockMultipartFile("image", "a.png", "image/png", PNG)), s))
                .andExpect(status().isOk()).andReturn()).path("profileImage").asText();
        assertThat(Files.exists(uploaded(url))).isTrue();

        mockMvc.perform(auth(delete("/api/users/me/profile-image"), s)).andExpect(status().isOk())
                .andExpect(jsonPath("$.profileImage").isEmpty())
                .andExpect(jsonPath("$.id").value(s.userId()));
        assertThat(Files.exists(uploaded(url))).isFalse();

        // 동시 교체
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            List<Callable<String>> tasks = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                tasks.add(() -> json(mockMvc.perform(auth(putMe(null)
                                .file(new MockMultipartFile("image", "a.png", "image/png", PNG)), s))
                        .andExpect(status().isOk()).andReturn()).path("profileImage").asText());
            }
            List<String> urls = new ArrayList<>();
            for (Future<String> f : pool.invokeAll(tasks)) {
                urls.add(f.get());
            }
            String finalUrl = json(mockMvc.perform(auth(get("/api/users/me"), s)).andReturn()).path("profileImage").asText();
            assertThat(urls).contains(finalUrl);
            for (String u : urls) {
                assertThat(Files.exists(uploaded(u))).as(u).isEqualTo(u.equals(finalUrl));
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("회원 탈퇴: 204, 토큰·사용자 행 삭제, 사진 파일 삭제, 기존 토큰 401")
    void deleteAccount() throws Exception {
        Session s = login("leaver");
        String url = json(mockMvc.perform(auth(putMe(null)
                        .file(new MockMultipartFile("image", "a.png", "image/png", PNG)), s))
                .andExpect(status().isOk()).andReturn()).path("profileImage").asText();

        mockMvc.perform(auth(delete("/api/users/me"), s)).andExpect(status().isNoContent());
        assertThat(jdbcTemplate.queryForObject("select count(*) from users where custom_id = ?", Integer.class, s.customId()))
                .isZero();
        assertThat(jdbcTemplate.queryForObject("select count(*) from refresh_tokens where current_token = ?",
                Integer.class, s.refreshToken())).isZero();
        assertThat(Files.exists(uploaded(url))).isFalse();
        mockMvc.perform(auth(get("/api/users/me"), s)).andExpect(status().isUnauthorized());
    }

    // ─── 다른 사용자 프로필 ─────────────────────────────────────────

    @Test
    @DisplayName("다른 사용자 프로필: friendStatus, mutualGroups, customId 대소문자 무시, 없으면 404(입력값 미포함), 이메일 없음")
    void otherProfile() throws Exception {
        Session a = login("pa");
        Session b = login("pb");

        mockMvc.perform(auth(get("/api/users/" + b.userId()), a)).andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(b.userId()))
                .andExpect(jsonPath("$.customId").value(b.customId()))
                .andExpect(jsonPath("$.nickname").value("pb"))
                .andExpect(jsonPath("$.friendStatus").value("NONE"))
                .andExpect(jsonPath("$.mutualGroups").isEmpty())
                .andExpect(jsonPath("$.email").doesNotExist());
        mockMvc.perform(auth(get("/api/users/" + a.userId()), a))
                .andExpect(jsonPath("$.friendStatus").value("SELF"));

        mockMvc.perform(auth(post("/api/friends/request"), a).contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("customId", b.customId()))))
                .andExpect(status().isOk());
        mockMvc.perform(auth(get("/api/users/" + b.userId()), a))
                .andExpect(jsonPath("$.friendStatus").value("PENDING_SENT"));
        mockMvc.perform(auth(get("/api/users/by-custom-id/" + a.customId().toLowerCase()), b)).andExpect(status().isOk())
                .andExpect(jsonPath("$.friendStatus").value("PENDING_RECEIVED"));

        String requestId = json(mockMvc.perform(auth(get("/api/friends/requests"), b)).andReturn()).get(0).path("id").asText();
        mockMvc.perform(auth(put("/api/friends/accept/" + requestId), b)).andExpect(status().isOk());
        mockMvc.perform(auth(get("/api/users/" + b.userId()), a))
                .andExpect(jsonPath("$.friendStatus").value("FRIEND"));

        JsonNode group = json(mockMvc.perform(auth(post("/api/groups"), a).contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("name", "같이 모임"))))
                .andExpect(status().isOk()).andReturn());
        mockMvc.perform(auth(post("/api/groups/join").param("code", group.path("inviteCode").asText()), b))
                .andExpect(status().isOk());
        mockMvc.perform(auth(get("/api/users/" + a.userId()), b))
                .andExpect(jsonPath("$.mutualGroups[0].id").value(group.path("id").asText()))
                .andExpect(jsonPath("$.mutualGroups[0].name").value("같이 모임"));

        String missing = UUID.randomUUID().toString();
        MvcResult notFound = mockMvc.perform(auth(get("/api/users/" + missing), a))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("사용자를 찾을 수 없어요."))
                .andReturn();
        assertThat(notFound.getResponse().getContentAsString(StandardCharsets.UTF_8)).doesNotContain(missing);
        mockMvc.perform(auth(get("/api/users/not-a-uuid"), a)).andExpect(status().isNotFound());
        mockMvc.perform(auth(get("/api/users/by-custom-id/zzzz9999"), a))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("사용자를 찾을 수 없어요."));
        mockMvc.perform(get("/api/users/" + b.userId())).andExpect(status().isUnauthorized());

        // QA02-X01: 방화벽이 거부하는 경로(%25·%2F·%00)는 유효 토큰이어도 401 이 아니라 400 JSON
        for (String raw : List.of("/api/users/%253Cb%253Ex%253C%252Fb%253E", "/api/users/a%2Fb", "/api/users/a%00b")) {
            mockMvc.perform(auth(get(java.net.URI.create(raw)), a))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.status").value(400))
                    .andExpect(jsonPath("$.message").value("요청 주소가 올바르지 않아요."));
        }
        // 인코딩된 꺾쇠(%3C) 처럼 방화벽을 통과하는 UUID 아닌 값은 404 JSON
        mockMvc.perform(auth(get(java.net.URI.create("/api/users/%3Cb%3Ex")), a))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("사용자를 찾을 수 없어요."));
    }

    // ─── 오류 형식·보안 설정 ────────────────────────────────────────

    @Test
    @DisplayName("오류 형식: 401 도 {error,message,status,timestamp}, 404 메시지에 입력값을 넣지 않는다")
    void errorShape() throws Exception {
        mockMvc.perform(get("/api/memos"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("Unauthorized"))
                .andExpect(jsonPath("$.message").isString())
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.timestamp").value(org.hamcrest.Matchers.matchesPattern(
                        "\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}")));

        Session s = login("errors2");
        String longCode = "A".repeat(5000);
        MvcResult result = mockMvc.perform(auth(post("/api/groups/join").param("code", longCode), s))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andReturn();
        String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(body).doesNotContain("AAAAAAAAAA").doesNotContain("ID:").doesNotContain("을(를)");
        // 친구 요청: 없는 ID
        MvcResult friend = mockMvc.perform(auth(post("/api/friends/request"), s).contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("customId", "zzzz9999"))))
                .andExpect(status().isNotFound()).andReturn();
        assertThat(friend.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .doesNotContain("zzzz9999").doesNotContain("을(를)");
    }

    @Test
    @DisplayName("CORS: 설정한 출처만 허용 (개발용 localhost·vercel 출처는 거부)")
    void cors() throws Exception {
        mockMvc.perform(options("/api/memos").header("Origin", "https://moyeolog.test")
                        .header("Access-Control-Request-Method", "GET"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "https://moyeolog.test"));
        for (String origin : List.of("http://localhost:3000", "http://127.0.0.1:3000", "https://moyeolog-fe.vercel.app")) {
            mockMvc.perform(options("/api/memos").header("Origin", origin)
                            .header("Access-Control-Request-Method", "GET"))
                    .andExpect(status().isForbidden())
                    .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
        }
    }
}
