package com.moyeolog.moyelog_BE.live;

import com.fasterxml.jackson.databind.JsonNode;
import com.moyeolog.moyelog_BE.event.ChangeEvent;
import com.moyeolog.moyelog_BE.notification.NotifyTestSupport.Session;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

import static com.moyeolog.moyelog_BE.notification.NotifyTestSupport.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 실시간 갱신 GET /api/events (SSE): 인증, 받는 사람 계산, 커밋 뒤 발행, 연결 상한, 로그아웃 시 닫기 */
@SpringBootTest(properties = {"live.heartbeat-ms=300", "live.max-connections-per-user=6"})
class LiveIntegrationTest {

    private static final long WAIT_MS = 3000;
    /** 이벤트가 '오지 않음'을 확인할 때 기다리는 시간 (보내기는 별도 스레드라 조금 기다린다) */
    private static final long QUIET_MS = 400;

    @Autowired private WebApplicationContext context;
    @Autowired private LiveHub liveHub;
    @Autowired private ApplicationEventPublisher publisher;
    @Autowired private PlatformTransactionManager transactionManager;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    /** 열린 스트림 하나. 받은 내용은 응답 본문에 쌓인다. */
    private record Stream(MvcResult result) {
        String body() {
            try {
                return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }
    }

    private Stream connect(Session session) throws Exception {
        MvcResult result = mockMvc.perform(auth(get("/api/events"), session).accept("text/event-stream"))
                .andExpect(request().asyncStarted())
                .andReturn();
        return new Stream(result);
    }

    private static boolean waitFor(Supplier<Boolean> condition, long ms) throws InterruptedException {
        long end = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < end) {
            if (condition.get()) return true;
            Thread.sleep(20);
        }
        return condition.get();
    }

    private static void assertReceives(Stream stream, String fragment) throws InterruptedException {
        assertThat(waitFor(() -> stream.body().contains(fragment), WAIT_MS))
                .as("스트림이 %s 를 받아야 한다. 받은 것: %s", fragment, stream.body()).isTrue();
    }

    private static void assertNotReceives(Stream stream, String fragment) throws InterruptedException {
        Thread.sleep(QUIET_MS);
        assertThat(stream.body()).doesNotContain(fragment);
    }

    private static int changedCount(Stream stream) {
        return stream.body().split("event:changed", -1).length - 1;
    }

    private JsonNode createSchedule(Session session, Map<String, Object> body) throws Exception {
        return json(mockMvc.perform(jsonBody(auth(post("/api/schedules"), session), body))
                .andExpect(status().isOk()).andReturn());
    }

    private static Map<String, Object> schedule(String title, List<UUID> participants) {
        return Map.of("title", title, "startTime", "2026-12-01T10:00:00", "endTime", "2026-12-01T11:00:00",
                "participantIds", participants);
    }

    @Test
    @DisplayName("토큰 없이 또는 틀린 토큰으로 붙으면 401 JSON")
    void unauthorized() throws Exception {
        mockMvc.perform(get("/api/events").accept("text/event-stream"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/events").header("Authorization", "Bearer not-a-token").accept("text/event-stream"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").exists());
    }

    @Test
    @DisplayName("붙으면 200 text/event-stream 과 ready 이벤트, 그 뒤 주석 하트비트(: ping)")
    void readyAndHeartbeat() throws Exception {
        Session a = login(mockMvc, "라이브준비");
        Stream stream = connect(a);
        assertThat(stream.result().getResponse().getStatus()).isEqualTo(200);
        assertThat(stream.result().getResponse().getContentType()).startsWith("text/event-stream");
        assertReceives(stream, "event:ready\ndata:{}\n\n");
        assertReceives(stream, ": ping\n\n");
        assertThat(stream.result().getResponse().getHeader("X-Accel-Buffering")).isEqualTo("no");
    }

    @Test
    @DisplayName("일정: 작성자·참여자에게 schedules·schedule:{id}, 참여자에서 빠진 사람도 받고, 상관없는 사람은 못 받음")
    void scheduleRecipients() throws Exception {
        Session a = login(mockMvc, "일정작성");
        Session b = login(mockMvc, "일정참여");
        Session c = login(mockMvc, "일정남");
        makeFriends(mockMvc, a, b);
        Stream sa = connect(a);
        Stream sb = connect(b);
        Stream sc = connect(c);

        String id = createSchedule(a, schedule("같이 저녁", List.of(b.userId()))).path("id").asText();
        String topics = "{\"topics\":[\"schedules\",\"schedule:" + id + "\"]}";
        assertReceives(sa, topics);
        assertReceives(sb, topics);
        assertNotReceives(sc, "event:changed");

        // B 를 참여자에서 뺀다 → B 의 화면에서도 사라져야 하므로 B 도 받는다
        int before = changedCount(sb);
        mockMvc.perform(jsonBody(auth(put("/api/schedules/" + id), a), schedule("같이 저녁", List.of())))
                .andExpect(status().isOk());
        assertThat(waitFor(() -> changedCount(sb) > before, WAIT_MS)).isTrue();

        // 이제 B 는 상관없다 → 삭제는 A 만 받는다
        int afterRemoval = changedCount(sb);
        int aBefore = changedCount(sa);
        mockMvc.perform(auth(delete("/api/schedules/" + id), a)).andExpect(status().is2xxSuccessful());
        assertThat(waitFor(() -> changedCount(sa) > aBefore, WAIT_MS)).isTrue();
        Thread.sleep(QUIET_MS);
        assertThat(changedCount(sb)).isEqualTo(afterRemoval);
        assertThat(sc.body()).doesNotContain("event:changed");
    }

    @Test
    @DisplayName("실패한 요청(403)은 아무 것도 보내지 않는다")
    void failedRequestSendsNothing() throws Exception {
        Session a = login(mockMvc, "실패작성");
        Session b = login(mockMvc, "실패남");
        String id = createSchedule(a, schedule("혼자", List.of())).path("id").asText();
        Stream sa = connect(a);
        mockMvc.perform(auth(delete("/api/schedules/" + id), b)).andExpect(status().isForbidden());
        assertNotReceives(sa, "event:changed");
    }

    @Test
    @DisplayName("커밋 뒤에만 보낸다: 트랜잭션 안에서는 안 가고, 롤백되면 끝까지 안 간다")
    void afterCommitOnly() throws Exception {
        Session a = login(mockMvc, "커밋확인");
        Stream sa = connect(a);
        TransactionTemplate tx = new TransactionTemplate(transactionManager);

        tx.executeWithoutResult(status -> {
            publisher.publishEvent(ChangeEvent.of(Set.of(a.userId()), "memos"));
            try {
                Thread.sleep(QUIET_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            assertThat(sa.body()).doesNotContain("event:changed");
        });
        assertReceives(sa, "{\"topics\":[\"memos\"]}");

        tx.executeWithoutResult(status -> {
            publisher.publishEvent(ChangeEvent.of(Set.of(a.userId()), "friends"));
            status.setRollbackOnly();
        });
        assertNotReceives(sa, "friends");
    }

    @Test
    @DisplayName("모임: 가입·내보내기를 기존 멤버와 내보내진 사람 모두 받는다")
    void groupMembership() throws Exception {
        Session owner = login(mockMvc, "모임장");
        Session member = login(mockMvc, "모임원");
        JsonNode group = json(mockMvc.perform(jsonBody(auth(post("/api/groups"), owner), Map.of("name", "실시간 모임")))
                .andExpect(status().isOk()).andReturn());
        String groupId = group.path("id").asText();
        Stream so = connect(owner);
        Stream sm = connect(member);

        mockMvc.perform(auth(post("/api/groups/join").param("code", group.path("inviteCode").asText()), member))
                .andExpect(status().isOk());
        String topics = "\"groups\",\"group:" + groupId + "\",\"schedules\",\"memos\"";
        assertReceives(so, topics);
        assertReceives(sm, topics);

        int before = changedCount(sm);
        mockMvc.perform(auth(delete("/api/groups/" + groupId + "/members/" + member.userId()), owner))
                .andExpect(status().is2xxSuccessful());
        assertThat(waitFor(() -> changedCount(sm) > before, WAIT_MS)).as("내보내진 사람도 받는다").isTrue();

        // 모임 일정: 모임 멤버(방장)만, 내보내진 사람은 이제 받지 않는다
        int kicked = changedCount(sm);
        String scheduleId = createSchedule(owner, Map.of("title", "모임 일정", "groupId", groupId,
                "startTime", "2026-12-01T10:00:00", "endTime", "2026-12-01T11:00:00")).path("id").asText();
        assertReceives(so, "\"schedules\",\"schedule:" + scheduleId + "\",\"group:" + groupId + "\"");
        Thread.sleep(QUIET_MS);
        assertThat(changedCount(sm)).isEqualTo(kicked);
    }

    @Test
    @DisplayName("메모 공유·공유 해제: 공유받은 사람이 두 번 다 받는다. 친구 요청은 양쪽")
    void memoShareAndFriends() throws Exception {
        Session a = login(mockMvc, "메모작성");
        Session b = login(mockMvc, "메모친구");
        Stream sa = connect(a);
        Stream sb = connect(b);

        mockMvc.perform(jsonBody(auth(post("/api/friends/request"), a), Map.of("customId", b.customId())))
                .andExpect(status().isOk());
        assertReceives(sa, "{\"topics\":[\"friends\"]}");
        assertReceives(sb, "{\"topics\":[\"friends\"]}");
        JsonNode requests = json(mockMvc.perform(auth(get("/api/friends/requests"), b)).andReturn());
        mockMvc.perform(auth(put("/api/friends/accept/" + requests.get(0).path("id").asText()), b))
                .andExpect(status().is2xxSuccessful());

        MockMultipartFile part = new MockMultipartFile("memo", "", "application/json",
                MAPPER.writeValueAsBytes(Map.of("title", "공유할 메모", "content", "내용")));
        String memoId = json(mockMvc.perform(auth(multipart("/api/memos").file(part), a))
                .andExpect(status().isOk()).andReturn()).path("id").asText();
        assertReceives(sa, "{\"topics\":[\"memos\"]}");

        int before = sb.body().split("\"memos\"", -1).length;
        mockMvc.perform(jsonBody(auth(post("/api/memos/" + memoId + "/share"), a), Map.of("friendIds", List.of(b.userId()))))
                .andExpect(status().is2xxSuccessful());
        assertThat(waitFor(() -> sb.body().split("\"memos\"", -1).length > before, WAIT_MS)).isTrue();

        int shared = sb.body().split("\"memos\"", -1).length;
        mockMvc.perform(auth(delete("/api/memos/" + memoId + "/share/" + b.userId()), a))
                .andExpect(status().is2xxSuccessful());
        assertThat(waitFor(() -> sb.body().split("\"memos\"", -1).length > shared, WAIT_MS))
                .as("공유가 풀린 사람도 받는다").isTrue();
        // 공유 알림이 생기면 notifications 도 온다
        assertReceives(sb, "\"notifications\"");
    }

    @Test
    @DisplayName("사용자당 연결 상한(6)을 넘으면 가장 오래된 연결을 닫고, 닫힌 연결의 비동기 디스패치는 401 이 아니다")
    void connectionLimit() throws Exception {
        Session a = login(mockMvc, "연결상한");
        Stream first = connect(a);
        for (int i = 0; i < 6; i++) {
            connect(a);
        }
        assertThat(liveHub.connectionCount(a.userId())).isEqualTo(6);
        // 밀려나는 연결은 닫히기 직전에 evicted 를 받는다
        assertReceives(first, "event:evicted\ndata:{}\n\n");
        // 서버가 닫은 연결이 끝날 때의 ASYNC 디스패치가 보안 필터에서 401 로 바뀌지 않는다
        mockMvc.perform(asyncDispatch(first.result())).andExpect(status().isOk());
    }

    @Test
    @DisplayName("모든 기기 로그아웃·로그아웃 하면 그 사용자의 연결을 닫는다")
    void logoutClosesConnections() throws Exception {
        Session a = login(mockMvc, "로그아웃");
        Stream stream = connect(a);
        connect(a);
        assertThat(liveHub.connectionCount(a.userId())).isEqualTo(2);
        mockMvc.perform(auth(delete("/api/users/me/sessions"), a)).andExpect(status().is2xxSuccessful());
        assertThat(waitFor(() -> liveHub.connectionCount(a.userId()) == 0, WAIT_MS)).isTrue();
        mockMvc.perform(asyncDispatch(stream.result())).andExpect(status().isOk());

        // 이 기기만 로그아웃(서버 간 호출): 그 토큰으로 연 연결만 닫힌다
        Session b = login(mockMvc, "한기기");
        Session bOther = login(mockMvc, "다른사람");
        connect(b);
        connect(bOther);
        mockMvc.perform(jsonBody(post("/api/auth/logout").header("X-Internal-Secret", "test-internal-secret"),
                        Map.of("accessToken", b.accessToken())))
                .andExpect(status().is2xxSuccessful());
        assertThat(waitFor(() -> liveHub.connectionCount(b.userId()) == 0, WAIT_MS)).isTrue();
        assertThat(liveHub.connectionCount(bOther.userId())).isEqualTo(1);
    }

    private String createGroup(Session owner, String name) throws Exception {
        return json(mockMvc.perform(jsonBody(auth(post("/api/groups"), owner), Map.of("name", name)))
                .andExpect(status().isOk()).andReturn()).path("id").asText();
    }

    private void joinWithCode(Session owner, Session member, String groupId) throws Exception {
        String code = json(mockMvc.perform(auth(get("/api/groups/" + groupId), owner)).andReturn()).path("inviteCode").asText();
        mockMvc.perform(auth(post("/api/groups/join").param("code", code), member)).andExpect(status().isOk());
    }

    private static String data(String... topics) {
        return "data:" + LiveHub.toJson(List.of(topics)) + "\n";
    }

    @Test
    @DisplayName("M1: 모임 메모를 모임 밖 친구에게 공유하면 친구는 memos 만, 모임 멤버는 memos·group:{id}")
    void groupMemoSharedOutside() throws Exception {
        Session owner = login(mockMvc, "메모모임장");
        Session friend = login(mockMvc, "모임밖친구");
        makeFriends(mockMvc, owner, friend);
        String groupId = createGroup(owner, "메모 모임");
        MockMultipartFile part = new MockMultipartFile("memo", "", "application/json",
                MAPPER.writeValueAsBytes(Map.of("title", "모임 메모", "content", "내용", "groupId", groupId)));
        String memoId = json(mockMvc.perform(auth(multipart("/api/memos").file(part), owner))
                .andExpect(status().isOk()).andReturn()).path("id").asText();
        Stream so = connect(owner);
        Stream sf = connect(friend);

        mockMvc.perform(jsonBody(auth(post("/api/memos/" + memoId + "/share"), owner), Map.of("friendIds", List.of(friend.userId()))))
                .andExpect(status().is2xxSuccessful());
        assertReceives(sf, data("memos"));
        assertReceives(so, data("memos", "group:" + groupId));

        mockMvc.perform(jsonBody(auth(put("/api/memos/" + memoId + "/tags"), owner), Map.of("tags", List.of("태그"))))
                .andExpect(status().is2xxSuccessful());
        assertThat(waitFor(() -> sf.body().split("\"memos\"", -1).length > 2, WAIT_MS)).isTrue();
        Thread.sleep(QUIET_MS);
        assertThat(sf.body()).doesNotContain("group:");
    }

    @Test
    @DisplayName("M2: 일정을 다른 모임으로 옮기면 옛 모임 토픽은 옛 멤버에게만, 새 모임 토픽은 새 멤버에게만. 모임 밖 참여자는 group: 을 못 받음")
    void scheduleMovedBetweenGroups() throws Exception {
        Session owner = login(mockMvc, "옮기는사람");
        Session m1 = login(mockMvc, "옛모임원");
        Session m2 = login(mockMvc, "새모임원");
        Session friend = login(mockMvc, "개인참여자");
        makeFriends(mockMvc, owner, friend);
        String g1 = createGroup(owner, "옛 모임");
        String g2 = createGroup(owner, "새 모임");
        joinWithCode(owner, m1, g1);
        joinWithCode(owner, m2, g2);

        // 개인 일정(참여자 friend) → 옛 모임 일정: friend 는 참여자에서 빠지지만 group:{g1} 은 받지 않는다
        String id = createSchedule(owner, schedule("옮길 일정", List.of(friend.userId()))).path("id").asText();
        Stream so = connect(owner);
        Stream s1 = connect(m1);
        Stream s2 = connect(m2);
        Stream sf = connect(friend);
        Map<String, Object> toG1 = Map.of("title", "옮길 일정", "groupId", g1, "participantIds", List.of(friend.userId()),
                "startTime", "2026-12-01T10:00:00", "endTime", "2026-12-01T11:00:00");
        mockMvc.perform(jsonBody(auth(put("/api/schedules/" + id), owner), toG1)).andExpect(status().isOk());
        assertReceives(sf, data("schedules", "schedule:" + id));
        assertReceives(s1, data("schedules", "schedule:" + id, "group:" + g1));
        assertReceives(so, data("schedules", "schedule:" + id, "group:" + g1));

        // 옛 모임 → 새 모임
        Map<String, Object> toG2 = Map.of("title", "옮길 일정", "groupId", g2,
                "startTime", "2026-12-01T10:00:00", "endTime", "2026-12-01T11:00:00");
        mockMvc.perform(jsonBody(auth(put("/api/schedules/" + id), owner), toG2)).andExpect(status().isOk());
        assertReceives(so, data("schedules", "schedule:" + id, "group:" + g1, "group:" + g2));
        assertReceives(s2, data("schedules", "schedule:" + id, "group:" + g2));
        assertThat(waitFor(() -> s1.body().split("event:changed", -1).length > 2, WAIT_MS)).isTrue();
        Thread.sleep(QUIET_MS);
        assertThat(s1.body()).doesNotContain("group:" + g2);
        assertThat(s2.body()).doesNotContain("group:" + g1);
        assertThat(sf.body()).doesNotContain("group:");
    }

    @Test
    @DisplayName("L4①: 친구를 끊어 개인 일정 참여자가 정리되면 그 일정의 다른 참여자도 schedule:{id} 를 받는다")
    void unfriendNotifiesThirdParticipant() throws Exception {
        Session author = login(mockMvc, "일정주인");
        Session leaving = login(mockMvc, "끊는친구");
        Session third = login(mockMvc, "남는참여자");
        String friendshipId = makeFriends(mockMvc, author, leaving);
        makeFriends(mockMvc, author, third);
        String id = createSchedule(author, schedule("셋이 저녁", List.of(leaving.userId(), third.userId()))).path("id").asText();
        Stream st = connect(third);

        mockMvc.perform(auth(delete("/api/friends/" + friendshipId), leaving)).andExpect(status().is2xxSuccessful());
        assertReceives(st, data("schedules", "schedule:" + id));
    }

    @Test
    @DisplayName("L4②: 모임을 나가 모임 일정 참여자가 바뀌면 멤버가 schedule:{id} 를 같은 이벤트로 받는다")
    void leaveGroupNotifiesSchedule() throws Exception {
        Session owner = login(mockMvc, "일정모임장");
        Session member = login(mockMvc, "나갈모임원");
        String groupId = createGroup(owner, "나가기 모임");
        joinWithCode(owner, member, groupId);
        String id = createSchedule(owner, Map.of("title", "모임 일정", "groupId", groupId, "participantIds", List.of(member.userId()),
                "startTime", "2026-12-01T10:00:00", "endTime", "2026-12-01T11:00:00")).path("id").asText();
        Stream so = connect(owner);

        mockMvc.perform(auth(delete("/api/groups/" + groupId + "/members/me"), member)).andExpect(status().is2xxSuccessful());
        assertReceives(so, data("groups", "group:" + groupId, "schedules", "memos", "schedule:" + id));
        assertThat(changedCount(so)).isEqualTo(1);
    }

    @Test
    @DisplayName("L4③: 모임을 나간 작성자도 자기 모임 일정의 출발지 변경(schedule:{id})을 받고, group:{id} 는 받지 않는다")
    void midpointReachesAuthorWhoLeft() throws Exception {
        Session owner = login(mockMvc, "중간모임장");
        Session author = login(mockMvc, "나간작성자");
        String groupId = createGroup(owner, "중간 모임");
        joinWithCode(owner, author, groupId);
        String id = createSchedule(author, Map.of("title", "모임 약속", "groupId", groupId,
                "startTime", "2026-12-01T10:00:00", "endTime", "2026-12-01T11:00:00")).path("id").asText();
        mockMvc.perform(auth(delete("/api/groups/" + groupId + "/members/me"), author)).andExpect(status().is2xxSuccessful());
        Stream sa = connect(author);
        Stream so = connect(owner);

        mockMvc.perform(jsonBody(auth(put("/api/schedules/" + id + "/midpoint/departure"), owner),
                        Map.of("name", "강남역", "latitude", 37.4979, "longitude", 127.0276)))
                .andExpect(status().isOk());
        assertReceives(sa, data("schedules", "schedule:" + id));
        assertReceives(so, data("schedules", "schedule:" + id, "group:" + groupId));
        Thread.sleep(QUIET_MS);
        assertThat(sa.body()).doesNotContain("group:");
    }

    @Test
    @DisplayName("메시지: 보내면 양쪽이 messages 를 받는다")
    void messages() throws Exception {
        Session a = login(mockMvc, "메시지보냄");
        Session b = login(mockMvc, "메시지받음");
        makeFriends(mockMvc, a, b);
        Stream sa = connect(a);
        Stream sb = connect(b);
        mockMvc.perform(jsonBody(auth(post("/api/messages/" + b.userId()), a), Map.of("content", "안녕")))
                .andExpect(status().is2xxSuccessful());
        assertReceives(sa, "{\"topics\":[\"messages\"]}");
        assertReceives(sb, "{\"topics\":[\"messages\"]}");
    }
}
