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
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.AbstractMockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 실제 필터 체인/컨트롤러/JPA(H2 인메모리)를 거치는 통합 테스트.
 */
@SpringBootTest
class ApiIntegrationTest {

    private static final String SECRET_HEADER = "X-Internal-Secret";
    private static final String SECRET = "test-internal-secret";
    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0, 1, 2, 3};

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @org.springframework.beans.factory.annotation.Value("${jwt.secret}")
    private String jwtSecret;

    private final ObjectMapper mapper = new ObjectMapper();
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    // ─── helpers ────────────────────────────────────────────────────

    private record Session(String userId, String customId, String accessToken, String refreshToken) {
    }

    private Session login(String name) throws Exception {
        String kakaoId = name + "-" + UUID.randomUUID();
        String body = mapper.writeValueAsString(java.util.Map.of(
                "kakaoId", kakaoId, "email", kakaoId + "@example.com", "nickname", name));
        MvcResult result = mockMvc.perform(post("/api/auth/sync")
                        .header(SECRET_HEADER, SECRET)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode json = json(result);
        return new Session(json.path("user").path("id").asText(), json.path("user").path("customId").asText(),
                json.path("accessToken").asText(), json.path("refreshToken").asText());
    }

    private JsonNode json(MvcResult result) throws Exception {
        return mapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private <B extends AbstractMockHttpServletRequestBuilder<B>> B auth(B builder, Session session) {
        return builder.header("Authorization", "Bearer " + session.accessToken());
    }

    private <B extends AbstractMockHttpServletRequestBuilder<B>> B jsonBody(B builder, Object body) throws Exception {
        return builder.contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(body));
    }

    private JsonNode claims(String jwt) throws Exception {
        return mapper.readTree(Base64.getUrlDecoder().decode(jwt.split("\\.")[1]));
    }

    private void makeFriends(Session a, Session b) throws Exception {
        mockMvc.perform(jsonBody(auth(post("/api/friends/request"), a), java.util.Map.of("customId", b.customId())))
                .andExpect(status().isOk());
        JsonNode requests = json(mockMvc.perform(auth(get("/api/friends/requests"), b))
                .andExpect(status().isOk()).andReturn());
        mockMvc.perform(auth(put("/api/friends/accept/" + requests.get(0).path("id").asText()), b))
                .andExpect(status().isOk());
    }

    private String createGroup(Session owner, String name) throws Exception {
        return json(mockMvc.perform(jsonBody(auth(post("/api/groups"), owner), java.util.Map.of("name", name)))
                .andExpect(status().isOk()).andReturn()).path("id").asText();
    }

    private MockMultipartFile memoPart(Object memo) throws Exception {
        return new MockMultipartFile("memo", "", "application/json", mapper.writeValueAsBytes(memo));
    }

    private JsonNode createMemo(Session session, java.util.Map<String, Object> memo) throws Exception {
        return json(mockMvc.perform(auth(multipart("/api/memos").file(memoPart(memo)), session))
                .andExpect(status().isOk()).andReturn());
    }

    // ─── auth ───────────────────────────────────────────────────────

    @Test
    @DisplayName("sync: 내부 비밀 헤더가 없거나 틀리면 401, kakaoId 가 없으면 400")
    void sync_requiresInternalSecret() throws Exception {
        String body = "{\"kakaoId\":\"k-1\",\"nickname\":\"n\"}";
        mockMvc.perform(post("/api/auth/sync").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").isString());
        mockMvc.perform(post("/api/auth/sync").header(SECRET_HEADER, "wrong")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/auth/sync").header(SECRET_HEADER, SECRET)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").isString());
        mockMvc.perform(post("/api/auth/sync").header(SECRET_HEADER, SECRET)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"kakaoId\":\"  \"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/auth/sync").header(SECRET_HEADER, SECRET)
                        .contentType(MediaType.APPLICATION_JSON).content("{not json"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("sync: 토큰 형식 (exp 는 초 단위 숫자, typ 클레임), 로그인마다 리프레시 토큰 행이 추가된다")
    void sync_tokenShape() throws Exception {
        Session session = login("shape");
        JsonNode access = claims(session.accessToken());
        JsonNode refresh = claims(session.refreshToken());

        assertThat(access.path("sub").asText()).isEqualTo(session.userId());
        assertThat(access.path("typ").asText()).isEqualTo("access");
        assertThat(access.path("exp").isNumber()).isTrue();
        long now = System.currentTimeMillis() / 1000;
        assertThat(access.path("exp").asLong()).isBetween(now + 3500, now + 3700);
        assertThat(refresh.path("typ").asText()).isEqualTo("refresh");
        assertThat(session.refreshToken().length()).isLessThanOrEqualTo(512);

        Integer rows = jdbcTemplate.queryForObject(
                "select count(*) from refresh_tokens where current_token = ?", Integer.class, session.refreshToken());
        assertThat(rows).isEqualTo(1);
    }

    @Test
    @DisplayName("refresh: 교체, 직전 토큰 유예(60초), 잘못된 토큰은 401")
    void refresh_rotationAndGrace() throws Exception {
        Session session = login("refresh");
        String r0 = session.refreshToken();

        // 잘못된/알 수 없는 토큰 → 401 JSON
        for (String bad : new String[]{"garbage", "", session.accessToken(), r0 + "x"}) {
            mockMvc.perform(jsonBody(post("/api/auth/refresh"), java.util.Map.of("refreshToken", bad)))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.message").isString());
        }
        mockMvc.perform(post("/api/auth/refresh").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());

        // 정상 교체
        JsonNode first = json(mockMvc.perform(jsonBody(post("/api/auth/refresh"), java.util.Map.of("refreshToken", r0)))
                .andExpect(status().isOk()).andReturn());
        String r1 = first.path("refreshToken").asText();
        assertThat(r1).isNotEqualTo(r0);
        assertThat(claims(first.path("accessToken").asText()).path("exp").isNumber()).isTrue();

        // 직전 토큰(r0)으로 다시 요청 → 200, 다시 교체하지 않고 현재 토큰(r1)을 돌려준다
        JsonNode grace = json(mockMvc.perform(jsonBody(post("/api/auth/refresh"), java.util.Map.of("refreshToken", r0)))
                .andExpect(status().isOk()).andReturn());
        assertThat(grace.path("refreshToken").asText()).isEqualTo(r1);
        assertThat(claims(grace.path("accessToken").asText()).path("typ").asText()).isEqualTo("access");

        // 유예 시간이 지난 뒤에는 직전 토큰이 거부된다
        jdbcTemplate.update("update refresh_tokens set rotated_at = dateadd('SECOND', -120, rotated_at) where current_token = ?", r1);
        mockMvc.perform(jsonBody(post("/api/auth/refresh"), java.util.Map.of("refreshToken", r0)))
                .andExpect(status().isUnauthorized());

        // 현재 토큰(r1)은 계속 유효
        JsonNode second = json(mockMvc.perform(jsonBody(post("/api/auth/refresh"), java.util.Map.of("refreshToken", r1)))
                .andExpect(status().isOk()).andReturn());
        assertThat(second.path("refreshToken").asText()).isNotEqualTo(r1);

        // 다른 기기 로그인(같은 사용자) 은 별도 행 → 서로 영향을 주지 않는다
        Integer rows = jdbcTemplate.queryForObject(
                "select count(*) from refresh_tokens where user_id = (select user_id from refresh_tokens where current_token = ?)",
                Integer.class, second.path("refreshToken").asText());
        assertThat(rows).isEqualTo(1);
    }

    @Test
    @DisplayName("JWT 필터: 리프레시 토큰은 액세스 토큰으로 쓸 수 없고, 잘못된 토큰은 permitAll 경로를 401 로 만들지 않는다")
    void jwtFilter() throws Exception {
        Session session = login("filter");

        mockMvc.perform(auth(get("/api/users/me"), session)).andExpect(status().isOk());
        mockMvc.perform(get("/api/users/me")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/users/me").header("Authorization", "Bearer " + session.refreshToken()))
                .andExpect(status().isUnauthorized());
        for (String bad : new String[]{"garbage", "a.b.c", session.accessToken() + "tampered", "eyJhbGciOiJub25lIn0.eyJzdWIiOiJ4In0."}) {
            mockMvc.perform(get("/api/users/me").header("Authorization", "Bearer " + bad))
                    .andExpect(status().isUnauthorized());
            // permitAll 경로는 잘못된 토큰이 있어도 인증 없이 그대로 처리된다 (없는 파일 → 404)
            mockMvc.perform(get("/uploads/missing.png").header("Authorization", "Bearer " + bad))
                    .andExpect(status().isNotFound());
        }

        // 삭제된 사용자의 유효한 토큰 → 401
        Session ghost = login("ghost");
        jdbcTemplate.update("delete from refresh_tokens where current_token = ?", ghost.refreshToken());
        jdbcTemplate.update("delete from users where custom_id = ?", ghost.customId());
        mockMvc.perform(auth(get("/api/users/me"), ghost)).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("배포 이전에 발급된 토큰(typ 없음, 원문 저장 행)도 계속 동작한다")
    void legacyTokens() throws Exception {
        Session session = login("legacy");
        javax.crypto.SecretKey key = io.jsonwebtoken.security.Keys.hmacShaKeyFor(
                io.jsonwebtoken.io.Decoders.BASE64.decode(jwtSecret));
        java.util.Date now = new java.util.Date();
        String legacyAccess = io.jsonwebtoken.Jwts.builder().subject(session.userId()).issuedAt(now)
                .expiration(new java.util.Date(now.getTime() + 3_600_000)).signWith(key).compact();
        String legacyRefresh = io.jsonwebtoken.Jwts.builder().subject(session.userId()).issuedAt(now)
                .expiration(new java.util.Date(now.getTime() + 604_800_000)).signWith(key).compact();
        // 예전 스키마의 행: token 컬럼에 원문, 새 컬럼은 모두 NULL
        jdbcTemplate.update("insert into refresh_tokens (token, user_id, expiry_date) "
                        + "select ?, user_id, dateadd('DAY', 7, current_timestamp) from refresh_tokens where current_token = ?",
                legacyRefresh, session.refreshToken());

        mockMvc.perform(get("/api/users/me").header("Authorization", "Bearer " + legacyAccess))
                .andExpect(status().isOk());
        // 저장되어 있는 예전 리프레시 토큰은 액세스 토큰으로 쓸 수 없다
        mockMvc.perform(get("/api/users/me").header("Authorization", "Bearer " + legacyRefresh))
                .andExpect(status().isUnauthorized());
        // 예전 액세스 토큰은 리프레시에 쓸 수 없다
        mockMvc.perform(jsonBody(post("/api/auth/refresh"), java.util.Map.of("refreshToken", legacyAccess)))
                .andExpect(status().isUnauthorized());

        JsonNode rotated = json(mockMvc.perform(jsonBody(post("/api/auth/refresh"), java.util.Map.of("refreshToken", legacyRefresh)))
                .andExpect(status().isOk()).andReturn());
        String current = rotated.path("refreshToken").asText();
        assertThat(claims(current).path("typ").asText()).isEqualTo("refresh");
        // 유예 구간
        mockMvc.perform(jsonBody(post("/api/auth/refresh"), java.util.Map.of("refreshToken", legacyRefresh)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.refreshToken").value(current));
        Integer hashed = jdbcTemplate.queryForObject(
                "select count(*) from refresh_tokens where current_token = ? and length(token) = 64 and length(previous_token) = 64",
                Integer.class, current);
        assertThat(hashed).isEqualTo(1);
    }

    // ─── error handling ─────────────────────────────────────────────

    @Test
    @DisplayName("클라이언트 오류는 500 이 아니라 4xx 로 응답한다")
    void clientErrors() throws Exception {
        Session session = login("errors");

        mockMvc.perform(auth(get("/api/memos/not-a-uuid"), session))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").isString());
        mockMvc.perform(auth(get("/api/memos/" + UUID.randomUUID()), session))
                .andExpect(status().isNotFound());
        mockMvc.perform(auth(get("/api/no-such-endpoint"), session))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/uploads/")).andExpect(status().isNotFound());
        mockMvc.perform(get("/uploads/missing.png")).andExpect(status().isNotFound());
        mockMvc.perform(auth(patch("/api/users/me"), session))
                .andExpect(status().isMethodNotAllowed());
        mockMvc.perform(auth(post("/api/groups"), session).contentType(MediaType.TEXT_PLAIN).content("x"))
                .andExpect(status().isUnsupportedMediaType());
        mockMvc.perform(auth(post("/api/groups"), session).contentType(MediaType.APPLICATION_JSON).content("{bad"))
                .andExpect(status().isBadRequest());
        // 검증 오류: 첫 번째 필드 오류의 한국어 메시지
        mockMvc.perform(jsonBody(auth(post("/api/groups"), session), java.util.Map.of("name", "")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("그룹 이름은 필수입니다."));
        // 본문의 잘못된 UUID
        mockMvc.perform(auth(post("/api/schedules"), session).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"t\",\"startTime\":\"2026-01-01T10:00:00\",\"endTime\":\"2026-01-01T11:00:00\",\"groupId\":\"nope\"}"))
                .andExpect(status().isBadRequest());
        // 필수 파라미터 / 파트 누락
        mockMvc.perform(auth(post("/api/groups/join"), session)).andExpect(status().isBadRequest());
        mockMvc.perform(auth(multipart("/api/memos"), session)).andExpect(status().isBadRequest());
        // null 목록
        mockMvc.perform(auth(post("/api/memos/" + UUID.randomUUID() + "/share"), session)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(auth(post("/api/groups/" + createGroup(session, "g") + "/invitations"), session)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
        // 없는 사용자에게 친구 요청 → 404, 자기 자신 → 400
        mockMvc.perform(jsonBody(auth(post("/api/friends/request"), session), java.util.Map.of("customId", "zzzzzzzz")))
                .andExpect(status().isNotFound());
        mockMvc.perform(jsonBody(auth(post("/api/friends/request"), session), java.util.Map.of("customId", session.customId())))
                .andExpect(status().isBadRequest());
        // 잘못된 초대 코드 → 404
        mockMvc.perform(auth(post("/api/groups/join").param("code", "NOPE00"), session))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("친구: 중복 요청 409, 수락 전에는 이메일 비공개, 친구 목록의 userId 는 상대 사용자 UUID")
    void friends() throws Exception {
        Session a = login("fa");
        Session b = login("fb");

        mockMvc.perform(jsonBody(auth(post("/api/friends/request"), a), java.util.Map.of("customId", b.customId())))
                .andExpect(status().isOk());
        mockMvc.perform(jsonBody(auth(post("/api/friends/request"), b), java.util.Map.of("customId", a.customId())))
                .andExpect(status().isConflict());

        JsonNode pending = json(mockMvc.perform(auth(get("/api/friends/requests"), b)).andReturn()).get(0);
        assertThat(pending.has("email")).isTrue();
        assertThat(pending.path("email").isNull()).isTrue();
        assertThat(pending.path("userId").asText()).isEqualTo(a.userId());

        // 받은 사람만 수락할 수 있다
        mockMvc.perform(auth(put("/api/friends/accept/" + pending.path("id").asText()), a))
                .andExpect(status().isForbidden());
        mockMvc.perform(auth(put("/api/friends/accept/" + pending.path("id").asText()), b))
                .andExpect(status().isOk());

        JsonNode friend = json(mockMvc.perform(auth(get("/api/friends"), a)).andReturn()).get(0);
        assertThat(friend.path("userId").asText()).isEqualTo(b.userId());
        assertThat(friend.path("email").asText()).contains("@example.com");
    }

    // ─── memos ──────────────────────────────────────────────────────

    @Test
    @DisplayName("메모: 긴 본문, 공유(친구 전용/읽기 전용), 사용자별 즐겨찾기, 공유 해제, 친구 삭제 시 공유 해제, 인사이트 204")
    void memos() throws Exception {
        Session author = login("author");
        Session friend = login("friend");
        Session stranger = login("stranger");
        makeFriends(author, friend);

        // 한글 30,000자 메모
        String longContent = "가".repeat(30000);
        JsonNode memo = createMemo(author, java.util.Map.of("title", "긴 메모", "content", longContent));
        String memoId = memo.path("id").asText();
        assertThat(memo.path("content").asText()).hasSize(30000);
        assertThat(memo.path("isFavorite").asBoolean()).isFalse();

        // 인사이트가 없으면 204
        mockMvc.perform(auth(get("/api/memos/" + memoId + "/insight"), author)).andExpect(status().isNoContent());

        // 친구가 아닌 사용자에게는 공유 불가
        mockMvc.perform(jsonBody(auth(post("/api/memos/" + memoId + "/share"), author),
                        java.util.Map.of("friendIds", java.util.List.of(stranger.userId()))))
                .andExpect(status().isForbidden());
        mockMvc.perform(auth(get("/api/memos/" + memoId), stranger)).andExpect(status().isForbidden());

        // 친구에게 공유 → 읽기는 가능, 수정은 불가
        mockMvc.perform(jsonBody(auth(post("/api/memos/" + memoId + "/share"), author),
                        java.util.Map.of("friendIds", java.util.List.of(friend.userId()))))
                .andExpect(status().isOk());
        mockMvc.perform(auth(get("/api/memos/" + memoId), friend)).andExpect(status().isOk())
                .andExpect(jsonPath("$.sharedWith").isEmpty());
        mockMvc.perform(auth(get("/api/memos/" + memoId), author)).andExpect(status().isOk())
                .andExpect(jsonPath("$.sharedWith[0].id").value(friend.userId()));
        mockMvc.perform(auth(multipart("/api/memos/" + memoId)
                        .file(memoPart(java.util.Map.of("title", "hacked", "content", "x")))
                        .with(r -> { r.setMethod("PUT"); return r; }), friend))
                .andExpect(status().isForbidden());

        // 즐겨찾기는 사용자별
        mockMvc.perform(auth(put("/api/memos/" + memoId + "/favorite"), friend))
                .andExpect(status().isOk()).andExpect(jsonPath("$.isFavorite").value(true));
        mockMvc.perform(auth(get("/api/memos/" + memoId), author))
                .andExpect(jsonPath("$.isFavorite").value(false));
        mockMvc.perform(auth(get("/api/memos/shared"), friend))
                .andExpect(jsonPath("$[0].isFavorite").value(true));
        mockMvc.perform(auth(put("/api/memos/" + memoId + "/favorite"), friend))
                .andExpect(status().isOk()).andExpect(jsonPath("$.isFavorite").value(false));
        mockMvc.perform(auth(put("/api/memos/" + memoId + "/favorite"), stranger))
                .andExpect(status().isForbidden());

        // 읽을 수 없는 메모는 태그할 수 없다
        mockMvc.perform(auth(multipart("/api/memos").file(memoPart(java.util.Map.of(
                        "title", "t", "content", "c", "taggedMemoIds", java.util.List.of(memoId)))), stranger))
                .andExpect(status().isForbidden());

        // 공유 해제: 제3자는 불가, 작성자는 가능
        mockMvc.perform(auth(delete("/api/memos/" + memoId + "/share/" + friend.userId()), stranger))
                .andExpect(status().isForbidden());
        mockMvc.perform(auth(delete("/api/memos/" + memoId + "/share/" + friend.userId()), author))
                .andExpect(status().isNoContent());
        mockMvc.perform(auth(get("/api/memos/" + memoId), friend)).andExpect(status().isForbidden());
        mockMvc.perform(auth(delete("/api/memos/" + memoId + "/share/" + friend.userId()), author))
                .andExpect(status().isNotFound());

        // 다시 공유한 뒤 친구 관계를 삭제하면 공유도 사라진다
        mockMvc.perform(jsonBody(auth(post("/api/memos/" + memoId + "/share"), author),
                        java.util.Map.of("friendIds", java.util.List.of(friend.userId()))))
                .andExpect(status().isOk());
        String friendshipId = json(mockMvc.perform(auth(get("/api/friends"), author)).andReturn()).get(0).path("id").asText();
        mockMvc.perform(auth(delete("/api/friends/" + friendshipId), friend)).andExpect(status().isNoContent());
        mockMvc.perform(auth(get("/api/memos/" + memoId), friend)).andExpect(status().isForbidden());

        // 태그: 50자 초과는 400
        mockMvc.perform(jsonBody(auth(put("/api/memos/" + memoId + "/tags"), author),
                        java.util.Map.of("tags", java.util.List.of("a".repeat(51)))))
                .andExpect(status().isBadRequest());
        mockMvc.perform(jsonBody(auth(put("/api/memos/" + memoId + "/tags"), author),
                        java.util.Map.of("tags", java.util.List.of("one", " one ", ""))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.tags.length()").value(1));

        // 삭제
        mockMvc.perform(auth(delete("/api/memos/" + memoId), author)).andExpect(status().isNoContent());
        mockMvc.perform(auth(get("/api/memos/" + memoId), author)).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("업로드: 이미지가 아닌 파일은 400, 이미지는 판별된 확장자로 저장되고 메모 삭제 시 파일도 삭제된다")
    void upload() throws Exception {
        Session author = login("uploader");

        mockMvc.perform(auth(multipart("/api/memos")
                        .file(memoPart(java.util.Map.of("title", "t", "content", "c")))
                        .file(new MockMultipartFile("image", "evil.png", "image/png", "<html></html>".getBytes())), author))
                .andExpect(status().isBadRequest());

        JsonNode memo = json(mockMvc.perform(auth(multipart("/api/memos")
                        .file(memoPart(java.util.Map.of("title", "t", "content", "c")))
                        .file(new MockMultipartFile("image", "../x.html", "text/html", PNG)), author))
                .andExpect(status().isOk()).andReturn());
        String imageUrl = memo.path("imageUrl").asText();
        assertThat(imageUrl).matches("/uploads/[0-9a-f-]{36}\\.png");

        mockMvc.perform(get(imageUrl)).andExpect(status().isOk());
        mockMvc.perform(auth(delete("/api/memos/" + memo.path("id").asText()), author)).andExpect(status().isNoContent());
        mockMvc.perform(get(imageUrl)).andExpect(status().isNotFound());
    }

    // ─── groups / schedules / topics ────────────────────────────────

    @Test
    @DisplayName("그룹: 멤버 전용 조회, userIds 초대, 이중 수락 409, 그룹장 전용 수정, 강퇴 시 초대 코드 재발급, 나가기, 삭제(메모/일정 분리)")
    void groups() throws Exception {
        Session owner = login("owner");
        Session member = login("member");
        Session outsider = login("outsider");
        String groupId = createGroup(owner, "모임");
        String unknownGroup = UUID.randomUUID().toString();

        // 멤버 전용
        for (String path : new String[]{"", "/memos", "/schedules", "/topics"}) {
            mockMvc.perform(auth(get("/api/groups/" + groupId + path), outsider)).andExpect(status().isForbidden());
            mockMvc.perform(auth(get("/api/groups/" + unknownGroup + path), outsider)).andExpect(status().isNotFound());
            mockMvc.perform(auth(get("/api/groups/" + groupId + path), owner)).andExpect(status().isOk());
        }

        // 그룹 메모/일정 생성은 멤버만
        mockMvc.perform(auth(multipart("/api/memos").file(memoPart(java.util.Map.of(
                        "title", "t", "content", "c", "groupId", groupId))), outsider))
                .andExpect(status().isForbidden());
        mockMvc.perform(auth(multipart("/api/memos").file(memoPart(java.util.Map.of(
                        "title", "t", "content", "c", "groupId", unknownGroup))), owner))
                .andExpect(status().isNotFound());
        java.util.Map<String, Object> schedule = new java.util.HashMap<>(java.util.Map.of(
                "title", "약속", "startTime", "2026-03-01T10:00:00", "endTime", "2026-03-01T11:00:00", "groupId", groupId));
        mockMvc.perform(jsonBody(auth(post("/api/schedules"), outsider), schedule)).andExpect(status().isForbidden());

        // 초대: 비멤버는 초대할 수 없다, userIds 로 초대
        mockMvc.perform(jsonBody(auth(post("/api/groups/" + groupId + "/invitations"), outsider),
                        java.util.Map.of("userIds", java.util.List.of(member.userId()))))
                .andExpect(status().isForbidden());
        mockMvc.perform(jsonBody(auth(post("/api/groups/" + groupId + "/invitations"), owner),
                        java.util.Map.of("userIds", java.util.List.of(member.userId()))))
                .andExpect(status().isOk());
        // 같은 초대를 다시 보내도 중복 생성되지 않는다 (null/빈 이메일은 건너뜀)
        mockMvc.perform(auth(post("/api/groups/" + groupId + "/invitations"), owner).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"emails\":[null,\"\"],\"userIds\":[\"" + member.userId() + "\"]}"))
                .andExpect(status().isOk());
        JsonNode invitations = json(mockMvc.perform(auth(get("/api/groups/invitations"), member)).andReturn());
        assertThat(invitations).hasSize(1);
        String invitationId = invitations.get(0).path("id").asText();

        // 다른 사람은 수락할 수 없다, 이중 수락은 409
        mockMvc.perform(auth(post("/api/groups/invitations/" + invitationId + "/accept"), outsider))
                .andExpect(status().isForbidden());
        mockMvc.perform(auth(post("/api/groups/invitations/" + invitationId + "/accept"), member))
                .andExpect(status().isOk());
        mockMvc.perform(auth(post("/api/groups/invitations/" + invitationId + "/accept"), member))
                .andExpect(status().isConflict());
        mockMvc.perform(auth(post("/api/groups/invitations/" + invitationId + "/reject"), member))
                .andExpect(status().isConflict());
        Integer memberships = jdbcTemplate.queryForObject("select count(*) from group_members gm join users u on u.id = gm.user_id where u.custom_id = ?",
                Integer.class, member.customId());
        assertThat(memberships).isEqualTo(1);

        JsonNode group = json(mockMvc.perform(auth(get("/api/groups/" + groupId), member)).andExpect(status().isOk()).andReturn());
        assertThat(group.path("memberCount").asInt()).isEqualTo(2);
        String inviteCode = group.path("inviteCode").asText();

        // 이미 멤버 → 409
        mockMvc.perform(auth(post("/api/groups/join").param("code", inviteCode), member)).andExpect(status().isConflict());

        // 그룹 수정은 그룹장만
        MockMultipartFile groupPart = new MockMultipartFile("group", "", "application/json",
                mapper.writeValueAsBytes(java.util.Map.of("name", "새 이름")));
        mockMvc.perform(auth(multipart("/api/groups/" + groupId).file(groupPart)
                        .with(r -> { r.setMethod("PUT"); return r; }), member))
                .andExpect(status().isForbidden());
        mockMvc.perform(auth(multipart("/api/groups/" + groupId).file(groupPart)
                        .with(r -> { r.setMethod("PUT"); return r; }), owner))
                .andExpect(status().isOk()).andExpect(jsonPath("$.name").value("새 이름"));

        // 멤버가 그룹 메모/일정/토픽 작성
        String groupMemoId = createMemo(member, java.util.Map.of("title", "그룹 메모", "content", "c", "groupId", groupId))
                .path("id").asText();
        // 그룹 멤버는 그룹 메모를 수정할 수 있다
        mockMvc.perform(auth(multipart("/api/memos/" + groupMemoId)
                        .file(memoPart(java.util.Map.of("title", "수정됨", "content", "c", "groupId", groupId)))
                        .with(r -> { r.setMethod("PUT"); return r; }), owner))
                .andExpect(status().isOk()).andExpect(jsonPath("$.title").value("수정됨"));

        // 그룹 일정 참여자는 그룹 멤버여야 한다
        schedule.put("participantIds", java.util.List.of(outsider.userId()));
        mockMvc.perform(jsonBody(auth(post("/api/schedules"), member), schedule)).andExpect(status().isBadRequest());
        schedule.put("participantIds", java.util.List.of(owner.userId(), member.userId()));
        schedule.put("taggedMemoIds", java.util.List.of(groupMemoId));
        String scheduleId = json(mockMvc.perform(jsonBody(auth(post("/api/schedules"), member), schedule))
                .andExpect(status().isOk()).andReturn()).path("id").asText();

        String topicId = json(mockMvc.perform(jsonBody(auth(post("/api/groups/" + groupId + "/topics"), member),
                        java.util.Map.of("title", "토픽", "content", "내용")))
                .andExpect(status().isOk()).andReturn()).path("id").asText();
        mockMvc.perform(jsonBody(auth(post("/api/topics/" + topicId + "/comments"), owner), java.util.Map.of("content", "댓글")))
                .andExpect(status().isOk());

        // 그룹장은 나갈 수 없다, 비멤버는 나갈 수 없다
        mockMvc.perform(auth(delete("/api/groups/" + groupId + "/members/me"), owner)).andExpect(status().isBadRequest());
        mockMvc.perform(auth(delete("/api/groups/" + groupId + "/members/me"), outsider)).andExpect(status().isForbidden());

        // 강퇴: 그룹장만, 강퇴 후 초대 코드가 바뀐다
        mockMvc.perform(auth(delete("/api/groups/" + groupId + "/members/" + owner.userId()), member))
                .andExpect(status().isForbidden());
        mockMvc.perform(auth(delete("/api/groups/" + groupId + "/members/" + member.userId()), owner))
                .andExpect(status().isOk());
        String newCode = json(mockMvc.perform(auth(get("/api/groups/" + groupId), owner)).andReturn()).path("inviteCode").asText();
        assertThat(newCode).isNotEqualTo(inviteCode);
        mockMvc.perform(auth(get("/api/groups/" + groupId + "/memos"), member)).andExpect(status().isForbidden());
        mockMvc.perform(auth(post("/api/groups/join").param("code", inviteCode), member)).andExpect(status().isNotFound());

        // 새 코드로 다시 가입한 뒤 스스로 나가기
        mockMvc.perform(auth(post("/api/groups/join").param("code", newCode), member)).andExpect(status().isOk());
        mockMvc.perform(auth(delete("/api/groups/" + groupId + "/members/me"), member)).andExpect(status().isNoContent());
        mockMvc.perform(auth(get("/api/groups/" + groupId), member)).andExpect(status().isForbidden());

        // 그룹 삭제: 그룹장만
        mockMvc.perform(auth(delete("/api/groups/" + groupId), member)).andExpect(status().isForbidden());
        mockMvc.perform(auth(delete("/api/groups/" + groupId), owner)).andExpect(status().isNoContent());
        mockMvc.perform(auth(get("/api/groups/" + groupId), owner)).andExpect(status().isNotFound());
        mockMvc.perform(auth(get("/api/topics/" + topicId), owner)).andExpect(status().isNotFound());

        // 그룹 메모/일정은 삭제되지 않고 작성자의 개인 메모/일정으로 남는다
        mockMvc.perform(auth(get("/api/memos/" + groupMemoId), member)).andExpect(status().isOk())
                .andExpect(jsonPath("$.groupId").doesNotExist());
        mockMvc.perform(auth(get("/api/memos/" + groupMemoId), owner)).andExpect(status().isForbidden());
        JsonNode memberSchedules = json(mockMvc.perform(auth(get("/api/schedules"), member)).andExpect(status().isOk()).andReturn());
        assertThat(memberSchedules).hasSize(1);
        assertThat(memberSchedules.get(0).path("id").asText()).isEqualTo(scheduleId);
        assertThat(memberSchedules.get(0).path("groupId").isNull()).isTrue();
    }

    @Test
    @DisplayName("일정: 벽시계 시간 그대로 저장, 예전 Z 형식은 Asia/Seoul 로 변환, 종료<시작 400, 참여자는 친구만")
    void schedules() throws Exception {
        Session author = login("sched");
        Session friend = login("schedfriend");
        Session stranger = login("schedstranger");
        makeFriends(author, friend);

        JsonNode local = json(mockMvc.perform(jsonBody(auth(post("/api/schedules"), author), java.util.Map.of(
                        "title", "local", "startTime", "2026-03-01T10:00:00", "endTime", "2026-03-01T11:00:00",
                        "participantIds", java.util.List.of(friend.userId(), author.userId()))))
                .andExpect(status().isOk()).andReturn());
        assertThat(local.path("startTime").asText()).startsWith("2026-03-01T10:00");
        assertThat(local.path("participants")).hasSize(2);

        // 예전 형식 (UTC, Z) → 한국 시간 19:00
        JsonNode legacy = json(mockMvc.perform(jsonBody(auth(post("/api/schedules"), author), java.util.Map.of(
                        "title", "legacy", "startTime", "2026-03-01T10:00:00.000Z", "endTime", "2026-03-01T11:00:00.000Z")))
                .andExpect(status().isOk()).andReturn());
        assertThat(legacy.path("startTime").asText()).startsWith("2026-03-01T19:00");

        mockMvc.perform(jsonBody(auth(post("/api/schedules"), author), java.util.Map.of(
                        "title", "bad", "startTime", "2026-03-01T10:00:00", "endTime", "2026-03-01T09:00:00")))
                .andExpect(status().isBadRequest());
        mockMvc.perform(jsonBody(auth(post("/api/schedules"), author), java.util.Map.of(
                        "title", "bad", "startTime", "not-a-date", "endTime", "2026-03-01T09:00:00")))
                .andExpect(status().isBadRequest());
        mockMvc.perform(jsonBody(auth(post("/api/schedules"), author), java.util.Map.of(
                        "title", "bad", "startTime", "2026-03-01T10:00:00", "endTime", "2026-03-01T11:00:00",
                        "participantIds", java.util.List.of(stranger.userId()))))
                .andExpect(status().isBadRequest());

        // 수정/삭제는 작성자만
        String id = local.path("id").asText();
        java.util.Map<String, Object> update = java.util.Map.of(
                "title", "updated", "startTime", "2026-03-02T10:00:00", "endTime", "2026-03-02T11:00:00");
        mockMvc.perform(jsonBody(auth(put("/api/schedules/" + id), friend), update)).andExpect(status().isForbidden());
        mockMvc.perform(jsonBody(auth(put("/api/schedules/" + id), author), update))
                .andExpect(status().isOk()).andExpect(jsonPath("$.title").value("updated"));
        // 다른 사람의 그룹으로 옮길 수 없다
        String foreignGroup = createGroup(stranger, "남의 그룹");
        java.util.Map<String, Object> move = new java.util.HashMap<>(update);
        move.put("groupId", foreignGroup);
        mockMvc.perform(jsonBody(auth(put("/api/schedules/" + id), author), move)).andExpect(status().isForbidden());
        mockMvc.perform(auth(delete("/api/schedules/" + id), stranger)).andExpect(status().isForbidden());
        mockMvc.perform(auth(delete("/api/schedules/" + UUID.randomUUID()), author)).andExpect(status().isNotFound());
        mockMvc.perform(auth(delete("/api/schedules/" + id), author)).andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("토픽: 제목/내용/댓글은 비어 있을 수 없고, imageUrl 은 허용된 형식만 가능")
    void topics() throws Exception {
        Session owner = login("topicowner");
        String groupId = createGroup(owner, "토픽 그룹");
        String url = "/api/groups/" + groupId + "/topics";

        mockMvc.perform(jsonBody(auth(post(url), owner), java.util.Map.of("title", "", "content", "c")))
                .andExpect(status().isBadRequest());
        mockMvc.perform(jsonBody(auth(post(url), owner), java.util.Map.of("title", "t", "content", "  ")))
                .andExpect(status().isBadRequest());
        mockMvc.perform(auth(post(url), owner).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(jsonBody(auth(post(url), owner),
                        java.util.Map.of("title", "t", "content", "c", "imageUrl", "/uploads/../../etc/passwd")))
                .andExpect(status().isBadRequest());
        mockMvc.perform(jsonBody(auth(post(url), owner),
                        java.util.Map.of("title", "t", "content", "c", "imageUrl", "https://example.com/a.png")))
                .andExpect(status().isBadRequest());

        String topicId = json(mockMvc.perform(jsonBody(auth(post(url), owner), java.util.Map.of(
                        "title", "t", "content", "나".repeat(30000), "imageUrl", "data:image/png;base64,iVBORw0KGgo=")))
                .andExpect(status().isOk()).andReturn()).path("id").asText();

        mockMvc.perform(jsonBody(auth(post("/api/topics/" + topicId + "/comments"), owner), java.util.Map.of("content", " ")))
                .andExpect(status().isBadRequest());
        mockMvc.perform(jsonBody(auth(post("/api/topics/" + topicId + "/comments"), owner),
                        java.util.Map.of("content", "다".repeat(10000))))
                .andExpect(status().isOk());
        mockMvc.perform(auth(get("/api/topics/" + topicId), owner)).andExpect(status().isOk())
                .andExpect(jsonPath("$.comments.length()").value(1));
        // AI 키가 설정되지 않은 환경: 502 고정 문구 (상위 URL/예외 메시지 미포함)
        MvcResult analyze = mockMvc.perform(auth(post("/api/topics/" + topicId + "/analyze"), owner))
                .andExpect(status().isBadGateway()).andReturn();
        assertThat(analyze.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .doesNotContain("googleapis").doesNotContain("Exception");
    }

    @Test
    @DisplayName("프로필: 닉네임 길이 제한, 이미지가 아닌 파일 거부")
    void profile() throws Exception {
        Session session = login("profile");
        MockMultipartFile tooLong = new MockMultipartFile("user", "", "application/json",
                mapper.writeValueAsBytes(java.util.Map.of("nickname", "가".repeat(51))));
        mockMvc.perform(auth(multipart("/api/users/me").file(tooLong).with(r -> { r.setMethod("PUT"); return r; }), session))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("닉네임은 50자를 초과할 수 없습니다."));

        MockMultipartFile ok = new MockMultipartFile("user", "", "application/json",
                mapper.writeValueAsBytes(java.util.Map.of("nickname", "새닉네임", "bio", "소개")));
        mockMvc.perform(auth(multipart("/api/users/me").file(ok)
                        .file(new MockMultipartFile("image", "a.png", "image/png", "not an image".getBytes()))
                        .with(r -> { r.setMethod("PUT"); return r; }), session))
                .andExpect(status().isBadRequest());
        mockMvc.perform(auth(multipart("/api/users/me").file(ok)
                        .file(new MockMultipartFile("image", "a.png", "image/png", PNG))
                        .with(r -> { r.setMethod("PUT"); return r; }), session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nickname").value("새닉네임"))
                .andExpect(jsonPath("$.profileImage").value(org.hamcrest.Matchers.matchesPattern("/uploads/[0-9a-f-]{36}\\.png")));
    }
}
