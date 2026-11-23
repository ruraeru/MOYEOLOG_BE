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
import java.util.ArrayList;
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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 2차 QA (메모·친구·일정) 통합 테스트. 실제 필터 체인/컨트롤러/JPA(H2) 를 거친다.
 */
@SpringBootTest
class MemoFriendScheduleIntegrationTest {

    private static final String SECRET_HEADER = "X-Internal-Secret";
    private static final String SECRET = "test-internal-secret";

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final ObjectMapper mapper = new ObjectMapper();
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    // ─── helpers ────────────────────────────────────────────────────

    private record Session(String userId, String customId, String accessToken) {
    }

    private Session login(String name) throws Exception {
        String kakaoId = name + "-" + UUID.randomUUID();
        String body = mapper.writeValueAsString(Map.of(
                "kakaoId", kakaoId, "email", kakaoId + "@example.com", "nickname", name));
        MvcResult result = mockMvc.perform(post("/api/auth/sync")
                        .header(SECRET_HEADER, SECRET)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode json = json(result);
        return new Session(json.path("user").path("id").asText(), json.path("user").path("customId").asText(),
                json.path("accessToken").asText());
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

    private void makeFriends(Session a, Session b) throws Exception {
        mockMvc.perform(jsonBody(auth(post("/api/friends/request"), a), Map.of("customId", b.customId())))
                .andExpect(status().isOk());
        JsonNode requests = json(mockMvc.perform(auth(get("/api/friends/requests"), b))
                .andExpect(status().isOk()).andReturn());
        mockMvc.perform(auth(put("/api/friends/accept/" + requests.get(0).path("id").asText()), b))
                .andExpect(status().isOk());
    }

    private String friendshipId(Session a) throws Exception {
        return json(mockMvc.perform(auth(get("/api/friends"), a)).andReturn()).get(0).path("id").asText();
    }

    private MockMultipartFile memoPart(Object memo) throws Exception {
        return new MockMultipartFile("memo", "", "application/json", mapper.writeValueAsBytes(memo));
    }

    private JsonNode createMemo(Session session, Map<String, Object> memo) throws Exception {
        return json(mockMvc.perform(auth(multipart("/api/memos").file(memoPart(memo)), session))
                .andExpect(status().isOk()).andReturn());
    }

    private MvcResult updateMemo(Session session, String memoId, Map<String, Object> memo) throws Exception {
        return mockMvc.perform(auth(multipart("/api/memos/" + memoId).file(memoPart(memo))
                .with(r -> { r.setMethod("PUT"); return r; }), session)).andReturn();
    }

    private String createGroup(Session owner, String name) throws Exception {
        return json(mockMvc.perform(jsonBody(auth(post("/api/groups"), owner), Map.of("name", name)))
                .andExpect(status().isOk()).andReturn()).path("id").asText();
    }

    private void joinGroup(Session member, String groupId, Session owner) throws Exception {
        String code = json(mockMvc.perform(auth(get("/api/groups/" + groupId), owner)).andReturn())
                .path("inviteCode").asText();
        mockMvc.perform(auth(post("/api/groups/join").param("code", code), member));
    }

    private String createSchedule(Session session, Map<String, Object> schedule) throws Exception {
        Map<String, Object> body = new java.util.HashMap<>(Map.of(
                "title", "일정", "startTime", "2026-12-01T10:00:00", "endTime", "2026-12-01T11:00:00"));
        body.putAll(schedule);
        return json(mockMvc.perform(jsonBody(auth(post("/api/schedules"), session), body))
                .andExpect(status().isOk()).andReturn()).path("id").asText();
    }

    private JsonNode scheduleOf(Session session, String scheduleId) throws Exception {
        for (JsonNode s : json(mockMvc.perform(auth(get("/api/schedules"), session)).andReturn())) {
            if (s.path("id").asText().equals(scheduleId)) return s;
        }
        return null;
    }

    private List<Integer> concurrently(int n, Callable<Integer> task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(n);
        try {
            List<Future<Integer>> futures = new ArrayList<>();
            for (int i = 0; i < n; i++) futures.add(pool.submit(task));
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> f : futures) statuses.add(f.get());
            return statuses;
        } finally {
            pool.shutdown();
        }
    }

    private int countFriendRows(Session a, Session b) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM friends f JOIN users r ON f.requester_id = r.id JOIN users v ON f.receiver_id = v.id " +
                        "WHERE (r.custom_id = ? AND v.custom_id = ?) OR (r.custom_id = ? AND v.custom_id = ?)",
                Integer.class, a.customId(), b.customId(), b.customId(), a.customId());
    }

    // ─── 친구 ───────────────────────────────────────────────────────

    @Test
    @DisplayName("친구: 동시 요청·역요청도 관계는 1개, 보낸 요청 목록, 이중 수락 409, 수락된 요청 취소 뒤 수락은 404")
    void friends_pairUniqueAndRaces() throws Exception {
        Session a = login("pa");
        Session b = login("pb");

        // 같은 요청 6개 + 역방향 요청 6개를 동시에
        List<Integer> statuses = concurrently(12, new Callable<>() {
            private final java.util.concurrent.atomic.AtomicInteger i = new java.util.concurrent.atomic.AtomicInteger();
            @Override
            public Integer call() throws Exception {
                boolean forward = i.getAndIncrement() % 2 == 0;
                Session from = forward ? a : b;
                Session to = forward ? b : a;
                return mockMvc.perform(jsonBody(auth(post("/api/friends/request"), from), Map.of("customId", to.customId())))
                        .andReturn().getResponse().getStatus();
            }
        });
        assertThat(statuses).doesNotContain(500);
        assertThat(statuses.stream().filter(s -> s == 200).count()).isEqualTo(1);
        assertThat(countFriendRows(a, b)).isEqualTo(1);

        // 보낸 요청 목록: 보낸 쪽에서만 보이고, 받은 요청은 받은 쪽에서만
        JsonNode sentA = json(mockMvc.perform(auth(get("/api/friends/requests/sent"), a)).andExpect(status().isOk()).andReturn());
        JsonNode sentB = json(mockMvc.perform(auth(get("/api/friends/requests/sent"), b)).andExpect(status().isOk()).andReturn());
        assertThat(sentA.size() + sentB.size()).isEqualTo(1);
        Session requester = sentA.size() == 1 ? a : b;
        Session receiver = requester == a ? b : a;
        JsonNode sent = (requester == a ? sentA : sentB).get(0);
        assertThat(sent.path("userId").asText()).isEqualTo(receiver.userId());
        assertThat(sent.path("status").asText()).isEqualTo("PENDING");
        assertThat(sent.path("email").isNull()).isTrue();
        String requestId = sent.path("id").asText();

        mockMvc.perform(auth(put("/api/friends/accept/" + requestId), receiver)).andExpect(status().isOk());
        mockMvc.perform(auth(put("/api/friends/accept/" + requestId), receiver)).andExpect(status().isConflict());
        assertThat(json(mockMvc.perform(auth(get("/api/friends/requests/sent"), requester)).andReturn()).size()).isZero();

        // 끊은 뒤에 수락은 404, 다시 끊기도 404
        mockMvc.perform(auth(delete("/api/friends/" + requestId), requester)).andExpect(status().isNoContent());
        mockMvc.perform(auth(put("/api/friends/accept/" + requestId), receiver)).andExpect(status().isNotFound());
        mockMvc.perform(auth(delete("/api/friends/" + requestId), receiver)).andExpect(status().isNotFound());
        assertThat(countFriendRows(a, b)).isZero();
    }

    @Test
    @DisplayName("친구를 끊으면 개인 일정 참여자에서도 빠지고(모임 일정은 그대로), 공유로 생긴 즐겨찾기도 지워진다")
    void unfriend_removesPersonalParticipationAndFavorites() throws Exception {
        Session jiwoo = login("jiwoo");
        Session seoyeon = login("seoyeon");
        makeFriends(jiwoo, seoyeon);

        String scheduleId = createSchedule(jiwoo, Map.of("title", "발표 리허설", "participantIds", List.of(seoyeon.userId())));
        assertThat(scheduleOf(seoyeon, scheduleId)).isNotNull();

        String memoId = createMemo(jiwoo, Map.of("title", "m", "content", "c")).path("id").asText();
        mockMvc.perform(jsonBody(auth(post("/api/memos/" + memoId + "/share"), jiwoo), Map.of("friendIds", List.of(seoyeon.userId()))))
                .andExpect(status().isOk());
        mockMvc.perform(auth(put("/api/memos/" + memoId + "/favorite"), seoyeon)).andExpect(jsonPath("$.isFavorite").value(true));

        mockMvc.perform(auth(delete("/api/friends/" + friendshipId(seoyeon)), seoyeon)).andExpect(status().isNoContent());

        assertThat(scheduleOf(seoyeon, scheduleId)).isNull();
        assertThat(scheduleOf(jiwoo, scheduleId).path("participants").size()).isZero();
        mockMvc.perform(auth(get("/api/memos/" + memoId), seoyeon)).andExpect(status().isForbidden());

        // 다시 친구가 돼서 재공유해도 즐겨찾기는 되살아나지 않는다
        makeFriends(jiwoo, seoyeon);
        mockMvc.perform(jsonBody(auth(post("/api/memos/" + memoId + "/share"), jiwoo), Map.of("friendIds", List.of(seoyeon.userId()))))
                .andExpect(status().isOk());
        mockMvc.perform(auth(get("/api/memos/" + memoId), seoyeon)).andExpect(jsonPath("$.isFavorite").value(false));
    }

    // ─── 메모 ───────────────────────────────────────────────────────

    @Test
    @DisplayName("메모: version·permissions 응답, 오래된 version 409, removeImage, 이모지 제목, 동시 공유도 행 1개, 자기 자신만 공유 400")
    void memos_versionPermissionsAndShare() throws Exception {
        Session author = login("mauthor");
        Session friend = login("mfriend");
        makeFriends(author, friend);

        // 이모지 제목. (H2 의 VARCHAR 는 UTF-16 단위로 세서 255 를 넘길 수 없다. 200개 검증은 MemoServiceTest 에서,
        // 운영 MySQL utf8mb4 VARCHAR(255) 는 코드 포인트 255자까지 저장한다.)
        String emojiTitle = "😀".repeat(120);
        JsonNode memo = createMemo(author, Map.of("title", emojiTitle, "content", "본문"));
        String memoId = memo.path("id").asText();
        assertThat(memo.path("title").asText()).isEqualTo(emojiTitle);
        assertThat(memo.path("permissions").path("edit").asBoolean()).isTrue();
        assertThat(memo.path("permissions").path("analyze").asBoolean()).isTrue();
        long v0 = memo.path("version").asLong();

        // 같은 version 으로 수정 → 성공, version 증가
        JsonNode updated = json(updateMemo(author, memoId, Map.of("title", "새 제목", "content", "본문2", "version", v0)));
        assertThat(updated.path("version").asLong()).isGreaterThan(v0);
        // 오래된 version 으로 다시 저장 → 409, 내용 그대로
        MvcResult stale = updateMemo(author, memoId, Map.of("title", "옛 화면", "content", "x", "version", v0));
        assertThat(stale.getResponse().getStatus()).isEqualTo(409);
        assertThat(json(stale).path("message").asText()).contains("다른 곳에서 먼저 바뀌었어요");
        mockMvc.perform(auth(get("/api/memos/" + memoId), author)).andExpect(jsonPath("$.title").value("새 제목"));

        // 자기 자신·null 만 공유 → 400
        mockMvc.perform(auth(post("/api/memos/" + memoId + "/share"), author)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"friendIds\":[\"" + author.userId() + "\", null]}"))
                .andExpect(status().isBadRequest());

        // 같은 친구에게 동시에 8번 공유 → 행 1개, 500 없음
        List<Integer> statuses = concurrently(8, () -> mockMvc.perform(jsonBody(auth(post("/api/memos/" + memoId + "/share"), author),
                Map.of("friendIds", List.of(friend.userId())))).andReturn().getResponse().getStatus());
        assertThat(statuses).doesNotContain(500);
        Integer rows = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM memo_shares s JOIN users u ON s.shared_to_id = u.id " +
                "WHERE u.custom_id = ?", Integer.class, friend.customId());
        assertThat(rows).isEqualTo(1);

        // 공유받은 사람: 권한 모두 false, 즐겨찾기 목록에 보임
        mockMvc.perform(auth(get("/api/memos/" + memoId), friend))
                .andExpect(jsonPath("$.permissions.edit").value(false))
                .andExpect(jsonPath("$.permissions.tag").value(false))
                .andExpect(jsonPath("$.permissions.delete").value(false))
                .andExpect(jsonPath("$.permissions.share").value(false))
                .andExpect(jsonPath("$.permissions.analyze").value(false));
        mockMvc.perform(auth(put("/api/memos/" + memoId + "/favorite"), friend)).andExpect(status().isOk());
        mockMvc.perform(auth(get("/api/memos/favorites"), friend)).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(memoId));

        // 공유 해제 → 받은 사람의 즐겨찾기도 정리
        mockMvc.perform(auth(delete("/api/memos/" + memoId + "/share/" + friend.userId()), author)).andExpect(status().isNoContent());
        mockMvc.perform(auth(get("/api/memos/favorites"), friend)).andExpect(jsonPath("$.length()").value(0));

        // 이미지 첨부 후 removeImage 로 삭제
        byte[] png = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0, 1, 2, 3};
        JsonNode withImage = json(mockMvc.perform(auth(multipart("/api/memos/" + memoId)
                .file(memoPart(Map.of("title", "t", "content", "c")))
                .file(new MockMultipartFile("image", "a.png", "image/png", png))
                .with(r -> { r.setMethod("PUT"); return r; }), author)).andExpect(status().isOk()).andReturn());
        assertThat(withImage.path("imageUrl").asText()).startsWith("/uploads/");
        JsonNode removed = json(updateMemo(author, memoId, Map.of("title", "t", "content", "c", "removeImage", true)));
        assertThat(removed.path("imageUrl").isNull()).isTrue();

        // q 검색
        mockMvc.perform(auth(get("/api/memos").param("q", "없는검색어"), author)).andExpect(jsonPath("$.length()").value(0));
        mockMvc.perform(auth(get("/api/memos").param("q", "T"), author)).andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    @DisplayName("모임 메모: 멤버는 태그 수정 가능, 나간 작성자는 수정 불가·삭제 가능, 동시 수정은 500 없이 처리")
    void groupMemos_membersTagsAndFormerAuthor() throws Exception {
        Session owner = login("gowner");
        Session writer = login("gwriter");
        String groupId = createGroup(owner, "캡스톤");
        joinGroup(writer, groupId, owner);

        String memoId = createMemo(writer, Map.of("title", "회의록", "content", "c", "groupId", groupId)).path("id").asText();

        // 방장(작성자 아님)도 태그 수정 가능
        mockMvc.perform(jsonBody(auth(put("/api/memos/" + memoId + "/tags"), owner), Map.of("tags", List.of("디자인"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.tags[0]").value("디자인"))
                .andExpect(jsonPath("$.permissions.edit").value(true))
                .andExpect(jsonPath("$.permissions.delete").value(false));

        // 같은 메모 동시 수정 6건: 500 없음
        List<Integer> statuses = concurrently(6, () -> updateMemo(owner, memoId,
                Map.of("title", "동시", "content", "c", "tags", List.of("a", "b"))).getResponse().getStatus());
        assertThat(statuses).doesNotContain(500);
        assertThat(statuses).contains(200);

        // 작성자가 모임을 나가면 수정 불가(403), 삭제는 가능
        mockMvc.perform(auth(delete("/api/groups/" + groupId + "/members/me"), writer)).andExpect(status().is2xxSuccessful());
        assertThat(updateMemo(writer, memoId, Map.of("title", "나간 뒤 수정", "content", "x")).getResponse().getStatus()).isEqualTo(403);
        mockMvc.perform(auth(get("/api/memos/" + memoId), writer))
                .andExpect(jsonPath("$.permissions.edit").value(false))
                .andExpect(jsonPath("$.permissions.delete").value(true));
        mockMvc.perform(auth(delete("/api/memos/" + memoId), writer)).andExpect(status().isNoContent());
        mockMvc.perform(auth(delete("/api/memos/" + memoId), writer)).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("연결 노출·보존: 비공개 메모·일정 제목 숨김, 일정에서 만든 연결은 메모 수정에도 유지, 참여자는 남의 일정에 메모를 못 붙임")
    void links_visibilityAndPreservation() throws Exception {
        Session jiwoo = login("ljiwoo");
        Session seoyeon = login("lseoyeon");
        makeFriends(jiwoo, seoyeon);

        String secretMemo = createMemo(jiwoo, Map.of("title", "개인 일기 - 이직 고민", "content", "비밀")).path("id").asText();
        String privateSchedule = createSchedule(jiwoo, Map.of("title", "병원 상담"));
        JsonNode shared = createMemo(jiwoo, Map.of("title", "3주차 회의록", "content", "c",
                "taggedMemoIds", List.of(secretMemo), "taggedScheduleIds", List.of(privateSchedule)));
        String sharedId = shared.path("id").asText();
        assertThat(shared.path("taggedMemos").size()).isEqualTo(1);
        assertThat(shared.path("taggedSchedules").size()).isEqualTo(1);
        mockMvc.perform(jsonBody(auth(post("/api/memos/" + sharedId + "/share"), jiwoo), Map.of("friendIds", List.of(seoyeon.userId()))))
                .andExpect(status().isOk());

        // 받은 사람에게는 연결된 비공개 메모·일정이 보이지 않는다
        mockMvc.perform(auth(get("/api/memos/" + sharedId), seoyeon))
                .andExpect(jsonPath("$.taggedMemos.length()").value(0))
                .andExpect(jsonPath("$.taggedSchedules.length()").value(0));

        // 일정 쪽에서 메모를 연결 → 메모를 taggedScheduleIds=[] 로 고쳐도 연결 유지
        String rehearsal = createSchedule(jiwoo, Map.of("title", "리허설", "participantIds", List.of(seoyeon.userId()),
                "taggedMemoIds", List.of(secretMemo)));
        assertThat(updateMemo(jiwoo, secretMemo, Map.of("title", "개인 일기", "content", "추가", "taggedScheduleIds", List.of()))
                .getResponse().getStatus()).isEqualTo(200);
        assertThat(scheduleOf(jiwoo, rehearsal).path("taggedMemos").size()).isEqualTo(1);
        // 참여자에게는 읽을 수 없는 연결 메모가 보이지 않는다
        assertThat(scheduleOf(seoyeon, rehearsal).path("taggedMemos").size()).isZero();

        // 메모 쪽에서 만든 연결은 본문에서 빼면 끊긴다
        updateMemo(jiwoo, sharedId, Map.of("title", "3주차 회의록", "content", "c", "taggedScheduleIds", List.of()));
        mockMvc.perform(auth(get("/api/memos/" + sharedId), jiwoo)).andExpect(jsonPath("$.taggedSchedules.length()").value(0));

        // 참여자가 남의 일정에 자기 메모를 붙이려 해도 연결되지 않는다
        createMemo(seoyeon, Map.of("title", "불참 사유", "content", "c", "taggedScheduleIds", List.of(rehearsal)));
        assertThat(scheduleOf(jiwoo, rehearsal).path("taggedMemos").size()).isEqualTo(1);
    }

    // ─── 일정 ───────────────────────────────────────────────────────

    @Test
    @DisplayName("일정: 동시 삭제는 1건 204·나머지 404, 모임을 나간 작성자는 모임 일정 수정 403·삭제 가능")
    void schedules_concurrentDeleteAndFormerMember() throws Exception {
        Session owner = login("sowner");
        Session minsu = login("sminsu");
        String scheduleId = createSchedule(owner, Map.of("title", "지울 일정"));

        List<Integer> statuses = concurrently(5, () -> mockMvc.perform(auth(delete("/api/schedules/" + scheduleId), owner))
                .andReturn().getResponse().getStatus());
        assertThat(statuses).doesNotContain(500);
        assertThat(statuses.stream().filter(s -> s == 204).count()).isEqualTo(1);
        assertThat(statuses.stream().filter(s -> s == 404).count()).isEqualTo(4);

        String groupId = createGroup(owner, "발표 모임");
        joinGroup(minsu, groupId, owner);
        String groupSchedule = createSchedule(minsu, Map.of("title", "민수 발표 준비", "groupId", groupId));
        mockMvc.perform(auth(delete("/api/groups/" + groupId + "/members/me"), minsu)).andExpect(status().is2xxSuccessful());

        // 모임을 나간 작성자는 모임 일정을 고칠 수 없다 (403)
        mockMvc.perform(jsonBody(auth(put("/api/schedules/" + groupSchedule), minsu), Map.of(
                        "title", "민수 발표 준비(수정)", "startTime", "2026-12-01T10:00:00", "endTime", "2026-12-01T11:00:00",
                        "groupId", groupId)))
                .andExpect(status().isForbidden());

        // 종료 < 시작 은 400
        String personal = createSchedule(minsu, Map.of("title", "개인"));
        mockMvc.perform(jsonBody(auth(put("/api/schedules/" + personal), minsu), Map.of(
                        "title", "t", "startTime", "2026-12-01T10:00:00", "endTime", "2026-12-01T09:00:00")))
                .andExpect(status().isBadRequest());

        // 삭제는 작성자에게 허용
        mockMvc.perform(auth(delete("/api/schedules/" + groupSchedule), minsu)).andExpect(status().isNoContent());
    }
}
