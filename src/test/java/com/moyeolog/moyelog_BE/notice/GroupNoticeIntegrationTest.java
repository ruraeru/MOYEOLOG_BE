package com.moyeolog.moyelog_BE.notice;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.AbstractMockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 모임 공지·투표 통합 테스트. 실제 필터 체인/컨트롤러/JPA(H2) 를 거친다. */
@SpringBootTest
class GroupNoticeIntegrationTest {

    private static final String SECRET_HEADER = "X-Internal-Secret";
    private static final String SECRET = "test-internal-secret";
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

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

    private record Session(String userId, String accessToken) {
    }

    private Session login(String name) throws Exception {
        String kakaoId = name + "-" + UUID.randomUUID();
        String body = mapper.writeValueAsString(Map.of(
                "kakaoId", kakaoId, "email", kakaoId + "@example.com", "emailVerified", true, "nickname", name));
        JsonNode json = json(mockMvc.perform(post("/api/auth/sync").header(SECRET_HEADER, SECRET)
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk()).andReturn());
        return new Session(json.path("user").path("id").asText(), json.path("accessToken").asText());
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

    private String createGroup(Session owner, Session... members) throws Exception {
        String groupId = json(mockMvc.perform(jsonBody(auth(post("/api/groups"), owner), Map.of("name", "공지 모임")))
                .andExpect(status().isOk()).andReturn()).path("id").asText();
        String code = json(mockMvc.perform(auth(get("/api/groups/" + groupId), owner)).andReturn())
                .path("inviteCode").asText();
        for (Session member : members) {
            mockMvc.perform(auth(post("/api/groups/join").param("code", code), member)).andExpect(status().isOk());
        }
        return groupId;
    }

    private String base(String groupId) {
        return "/api/groups/" + groupId + "/notices";
    }

    private MvcResult create(Session s, String groupId, Map<String, Object> body) throws Exception {
        return mockMvc.perform(jsonBody(auth(post(base(groupId)), s), body)).andReturn();
    }

    private String createNotice(Session owner, String groupId, String title) throws Exception {
        MvcResult r = create(owner, groupId, Map.of("title", title, "content", "**내용** " + title));
        assertThat(r.getResponse().getStatus()).isEqualTo(201);
        return json(r).path("id").asText();
    }

    private static Map<String, Object> poll(List<String> options, boolean multiple, boolean anonymous, String closesAt) {
        Map<String, Object> poll = new HashMap<>();
        poll.put("options", options);
        poll.put("multiple", multiple);
        poll.put("anonymous", anonymous);
        poll.put("closesAt", closesAt);
        return poll;
    }

    private String createPollNotice(Session owner, String groupId, boolean multiple, boolean anonymous) throws Exception {
        MvcResult r = create(owner, groupId, Map.of("title", "투표", "content", "골라 주세요",
                "poll", poll(List.of("가", "나", "다"), multiple, anonymous, null)));
        assertThat(r.getResponse().getStatus()).isEqualTo(201);
        return json(r).path("id").asText();
    }

    private JsonNode list(Session s, String groupId) throws Exception {
        return json(mockMvc.perform(auth(get(base(groupId)), s)).andExpect(status().isOk()).andReturn());
    }

    private JsonNode detail(Session s, String groupId, String noticeId) throws Exception {
        return json(mockMvc.perform(auth(get(base(groupId) + "/" + noticeId), s)).andExpect(status().isOk()).andReturn());
    }

    private static JsonNode find(JsonNode array, String id) {
        for (JsonNode n : array) {
            if (n.path("id").asText().equals(id)) return n;
        }
        throw new AssertionError("목록에 없음: " + id);
    }

    private static List<String> ids(JsonNode array) {
        List<String> out = new ArrayList<>();
        array.forEach(n -> out.add(n.path("id").asText()));
        return out;
    }

    private static List<String> nicknames(JsonNode array) {
        List<String> out = new ArrayList<>();
        array.forEach(n -> out.add(n.path("nickname").asText()));
        return out;
    }

    /** 선택지 글자 → id */
    private static Map<String, String> optionIds(JsonNode detail) {
        Map<String, String> out = new HashMap<>();
        detail.path("poll").path("options").forEach(o -> out.put(o.path("text").asText(), o.path("id").asText()));
        return out;
    }

    private MvcResult vote(Session s, String groupId, String noticeId, String... optionIds) throws Exception {
        return mockMvc.perform(jsonBody(auth(put(base(groupId) + "/" + noticeId + "/poll/votes"), s),
                Map.of("optionIds", List.of(optionIds)))).andReturn();
    }

    private long count(String sql, Object... args) {
        Long n = jdbcTemplate.queryForObject(sql, Long.class, args);
        return n == null ? 0 : n;
    }

    private long notificationCount(Session s) {
        return count("SELECT COUNT(*) FROM notifications WHERE recipient_id = ?", UUID.fromString(s.userId()));
    }

    private long readsOf(Session s) {
        return count("SELECT COUNT(*) FROM group_notice_reads WHERE user_id = ?", UUID.fromString(s.userId()));
    }

    private long votesOf(Session s) {
        return count("SELECT COUNT(*) FROM group_notice_poll_votes WHERE user_id = ?", UUID.fromString(s.userId()));
    }

    @Test
    @DisplayName("권한·검증: 비멤버 403, 멤버는 쓰기·수정·삭제·고정 403, 없는 공지·다른 모임 경로 404, 제목 검증 400")
    void permissionsAndValidation() throws Exception {
        Session owner = login("방장"), member = login("멤버"), outsider = login("바깥");
        String groupId = createGroup(owner, member);

        assertThat(create(member, groupId, Map.of("title", "멤버 공지", "content", "x")).getResponse().getStatus())
                .isEqualTo(403);
        assertThat(create(owner, groupId, Map.of("title", " ", "content", "x")).getResponse().getStatus())
                .isEqualTo(400);
        assertThat(create(owner, groupId, Map.of("title", "가".repeat(101), "content", "x")).getResponse().getStatus())
                .isEqualTo(400);
        assertThat(create(owner, groupId, Map.of("title", "가".repeat(100), "content", "x")).getResponse().getStatus())
                .isEqualTo(201);

        String noticeId = createNotice(owner, groupId, "모임 안내");

        // 비멤버: 목록·상세 403
        mockMvc.perform(auth(get(base(groupId)), outsider)).andExpect(status().isForbidden());
        mockMvc.perform(auth(get(base(groupId) + "/" + noticeId), outsider)).andExpect(status().isForbidden());

        // 멤버: 관리 403, canManage false
        mockMvc.perform(jsonBody(auth(put(base(groupId) + "/" + noticeId), member), Map.of("title", "바꿈", "content", "y")))
                .andExpect(status().isForbidden());
        mockMvc.perform(auth(delete(base(groupId) + "/" + noticeId), member)).andExpect(status().isForbidden());
        mockMvc.perform(auth(put(base(groupId) + "/" + noticeId + "/pin"), member)).andExpect(status().isForbidden());
        assertThat(detail(member, groupId, noticeId).path("canManage").asBoolean()).isFalse();
        assertThat(detail(owner, groupId, noticeId).path("canManage").asBoolean()).isTrue();

        // 없는 공지, 다른 모임 경로
        mockMvc.perform(auth(get(base(groupId) + "/" + UUID.randomUUID()), member)).andExpect(status().isNotFound());
        String otherGroup = createGroup(owner);
        mockMvc.perform(auth(get(base(otherGroup) + "/" + noticeId), owner)).andExpect(status().isNotFound());

        // 투표 없는 공지의 투표 경로는 404
        String optionId = UUID.randomUUID().toString();
        assertThat(vote(member, groupId, noticeId, optionId).getResponse().getStatus()).isEqualTo(404);
        mockMvc.perform(auth(post(base(groupId) + "/" + noticeId + "/poll/close"), owner)).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("공지: 알림(설정과 관계없이, 본인 제외, 링크), 읽음 처리·n명 중 m명, 수정·고정·정렬·삭제")
    void noticeLifecycle() throws Exception {
        Session owner = login("방장"), a = login("가"), b = login("나");
        String groupId = createGroup(owner, a, b);

        // 알림 설정을 다 꺼도 공지 알림은 온다(설정 칸 없음)
        mockMvc.perform(jsonBody(auth(put("/api/notifications/preferences"), b), Map.of(
                "friendRequest", false, "groupInvite", false, "memoShared", false,
                "topicComment", false, "scheduleParticipant", false))).andExpect(status().isOk());

        long ownerBefore = notificationCount(owner), aBefore = notificationCount(a), bBefore = notificationCount(b);
        String first = createNotice(owner, groupId, "첫 공지");
        assertThat(notificationCount(owner)).isEqualTo(ownerBefore);
        assertThat(notificationCount(a)).isEqualTo(aBefore + 1);
        assertThat(notificationCount(b)).isEqualTo(bBefore + 1);
        assertThat(count("SELECT COUNT(*) FROM notifications WHERE recipient_id = ? AND type = 'GROUP_NOTICE' AND link = ?",
                UUID.fromString(a.userId()), "/groups/" + groupId + "?tab=notice&notice=" + first)).isEqualTo(1);

        // 읽음: 목록은 읽음 처리하지 않고, 상세를 열면 읽음
        JsonNode summary = find(list(a, groupId), first);
        assertThat(summary.path("read").asBoolean()).isFalse();
        assertThat(summary.path("memberCount").asInt()).isEqualTo(3);
        assertThat(summary.path("preview").asText()).doesNotContain("**");
        int readBefore = summary.path("readCount").asInt();
        JsonNode opened = detail(a, groupId, first);
        assertThat(opened.path("read").asBoolean()).isTrue();
        assertThat(opened.path("readCount").asInt()).isEqualTo(readBefore + 1);
        assertThat(nicknames(opened.path("readers"))).contains("가");
        assertThat(nicknames(opened.path("notReaders"))).contains("나").doesNotContain("가");
        assertThat(find(list(a, groupId), first).path("read").asBoolean()).isTrue();
        // 다시 열어도 한 번만 센다
        assertThat(detail(a, groupId, first).path("readCount").asInt()).isEqualTo(readBefore + 1);

        // 수정
        JsonNode edited = json(mockMvc.perform(jsonBody(auth(put(base(groupId) + "/" + first), owner),
                Map.of("title", "고친 공지", "content", "새 내용"))).andExpect(status().isOk()).andReturn());
        assertThat(edited.path("title").asText()).isEqualTo("고친 공지");
        assertThat(edited.path("content").asText()).isEqualTo("새 내용");

        // 정렬: 고정 먼저, 그다음 최신순
        String second = createNotice(owner, groupId, "둘째 공지");
        String third = createNotice(owner, groupId, "셋째 공지");
        assertThat(ids(list(a, groupId))).containsExactly(third, second, first);
        JsonNode pinned = json(mockMvc.perform(auth(put(base(groupId) + "/" + first + "/pin"), owner))
                .andExpect(status().isOk()).andReturn());
        assertThat(pinned.path("pinnedAt").isNull()).isFalse();
        assertThat(ids(list(a, groupId))).containsExactly(first, third, second);
        JsonNode unpinned = json(mockMvc.perform(auth(delete(base(groupId) + "/" + first + "/pin"), owner))
                .andExpect(status().isOk()).andReturn());
        assertThat(unpinned.path("pinnedAt").isNull()).isTrue();
        assertThat(ids(list(a, groupId))).containsExactly(third, second, first);

        // 삭제
        mockMvc.perform(auth(delete(base(groupId) + "/" + second), owner)).andExpect(status().isNoContent());
        assertThat(ids(list(a, groupId))).containsExactly(third, first);
        mockMvc.perform(auth(get(base(groupId) + "/" + second), a)).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("투표: 만들기 검증, 단일 선택 2개 400, 바꾸기·취소, 기명 이름·안 한 사람, 마감 409, 재촉 알림")
    void pollNamed() throws Exception {
        Session owner = login("방장"), a = login("가"), b = login("나");
        String groupId = createGroup(owner, a, b);

        // 만들기 검증: 선택지 1개, 11개, 빈 글자, 중복, 지난 마감 시각
        String past = LocalDateTime.now().minusHours(1).format(TIME);
        List<String> eleven = new ArrayList<>();
        for (int i = 0; i < 11; i++) eleven.add("선택" + i);
        for (Map<String, Object> bad : List.of(
                poll(List.of("하나"), false, false, null),
                poll(eleven, false, false, null),
                poll(List.of("가", " "), false, false, null),
                poll(List.of("가", "가"), false, false, null),
                poll(List.of("가", "가".repeat(101)), false, false, null),
                poll(List.of("가", "나"), false, false, past))) {
            assertThat(create(owner, groupId, Map.of("title", "투표", "content", "x", "poll", bad))
                    .getResponse().getStatus()).isEqualTo(400);
        }
        // 실패한 만들기는 공지도 남기지 않는다
        assertThat(list(owner, groupId).size()).isZero();

        String noticeId = createPollNotice(owner, groupId, false, false);
        JsonNode created = detail(a, groupId, noticeId);
        assertThat(created.path("hasPoll").asBoolean()).isTrue();
        assertThat(created.path("poll").path("multiple").asBoolean()).isFalse();
        assertThat(created.path("poll").path("closed").asBoolean()).isFalse();
        Map<String, String> opt = optionIds(created);
        assertThat(opt).containsOnlyKeys("가", "나", "다");

        // 단일 선택에 2개 → 400, 이 투표에 없는 선택지 → 400 또는 404(계약에 정해지지 않음)
        assertThat(vote(a, groupId, noticeId, opt.get("가"), opt.get("나")).getResponse().getStatus()).isEqualTo(400);
        assertThat(vote(a, groupId, noticeId, UUID.randomUUID().toString()).getResponse().getStatus()).isIn(400, 404);

        // 고르기 → 바꾸기
        assertThat(vote(a, groupId, noticeId, opt.get("가")).getResponse().getStatus()).isEqualTo(200);
        MvcResult changed = vote(a, groupId, noticeId, opt.get("나"));
        assertThat(changed.getResponse().getStatus()).isEqualTo(200);
        JsonNode afterChange = json(changed).path("poll");
        assertThat(afterChange.path("voterCount").asInt()).isEqualTo(1);
        for (JsonNode o : afterChange.path("options")) {
            boolean isNa = o.path("text").asText().equals("나");
            assertThat(o.path("count").asInt()).isEqualTo(isNa ? 1 : 0);
            assertThat(o.path("mine").asBoolean()).isEqualTo(isNa);
            assertThat(nicknames(o.path("voters"))).isEqualTo(isNa ? List.of("가") : List.of());
        }
        assertThat(nicknames(afterChange.path("notVoted"))).containsExactlyInAnyOrder("방장", "나");
        assertThat(find(list(a, groupId), noticeId).path("myVoted").asBoolean()).isTrue();

        // 취소(빈 배열)
        JsonNode cancelled = json(vote(a, groupId, noticeId)).path("poll");
        assertThat(cancelled.path("voterCount").asInt()).isZero();
        assertThat(find(list(a, groupId), noticeId).path("myVoted").asBoolean()).isFalse();
        vote(a, groupId, noticeId, opt.get("다"));

        // 재촉: 방장만, 안 한 멤버에게만(방장 본인 제외)
        mockMvc.perform(auth(post(base(groupId) + "/" + noticeId + "/poll/remind"), a)).andExpect(status().isForbidden());
        long ownerBefore = notificationCount(owner), aBefore = notificationCount(a), bBefore = notificationCount(b);
        mockMvc.perform(auth(post(base(groupId) + "/" + noticeId + "/poll/remind"), owner)).andExpect(status().isNoContent());
        assertThat(notificationCount(b)).isEqualTo(bBefore + 1);
        assertThat(notificationCount(a)).isEqualTo(aBefore);
        assertThat(notificationCount(owner)).isEqualTo(ownerBefore);

        // 마감: 방장만. 닫히면 고르기 409, 목록에 pollClosed
        mockMvc.perform(auth(post(base(groupId) + "/" + noticeId + "/poll/close"), a)).andExpect(status().isForbidden());
        JsonNode closed = json(mockMvc.perform(auth(post(base(groupId) + "/" + noticeId + "/poll/close"), owner))
                .andExpect(status().isOk()).andReturn());
        assertThat(closed.path("poll").path("closed").asBoolean()).isTrue();
        assertThat(find(list(b, groupId), noticeId).path("pollClosed").asBoolean()).isTrue();
        assertThat(vote(b, groupId, noticeId, opt.get("가")).getResponse().getStatus()).isEqualTo(409);

        // 공지 수정은 투표를 바꾸지 않는다
        mockMvc.perform(jsonBody(auth(put(base(groupId) + "/" + noticeId), owner), Map.of("title", "투표 고침", "content", "y")))
                .andExpect(status().isOk());
        assertThat(optionIds(detail(owner, groupId, noticeId))).isEqualTo(opt);
    }

    @Test
    @DisplayName("투표: 복수 선택, 익명이면 voters·notVoted 빈 목록(방장에게도), 마감 시각")
    void pollMultipleAnonymous() throws Exception {
        Session owner = login("방장"), a = login("가"), b = login("나");
        String groupId = createGroup(owner, a, b);

        String noticeId = createPollNotice(owner, groupId, true, true);
        Map<String, String> opt = optionIds(detail(a, groupId, noticeId));
        assertThat(vote(a, groupId, noticeId, opt.get("가"), opt.get("나")).getResponse().getStatus()).isEqualTo(200);
        assertThat(vote(b, groupId, noticeId, opt.get("가")).getResponse().getStatus()).isEqualTo(200);

        JsonNode poll = detail(owner, groupId, noticeId).path("poll");
        assertThat(poll.path("anonymous").asBoolean()).isTrue();
        assertThat(poll.path("voterCount").asInt()).isEqualTo(2);
        Map<String, Integer> counts = new HashMap<>();
        for (JsonNode o : poll.path("options")) {
            counts.put(o.path("text").asText(), o.path("count").asInt());
            assertThat(o.path("voters").size()).isZero();
        }
        assertThat(counts).isEqualTo(Map.of("가", 2, "나", 1, "다", 0));
        assertThat(poll.path("notVoted").size()).isZero();

        // 익명이어도 재촉은 안 한 사람(방장 본인 제외)에게 간다. 여기서는 모두 했거나 방장뿐이라 0
        long aBefore = notificationCount(a), bBefore = notificationCount(b);
        mockMvc.perform(auth(post(base(groupId) + "/" + noticeId + "/poll/remind"), owner)).andExpect(status().isNoContent());
        assertThat(notificationCount(a)).isEqualTo(aBefore);
        assertThat(notificationCount(b)).isEqualTo(bBefore);

        // 마감 시각이 있는 투표: 응답에 그대로, 아직 열림
        String closesAt = LocalDateTime.now().plusDays(1).withNano(0).format(TIME);
        MvcResult r = create(owner, groupId, Map.of("title", "마감 있음", "content", "x",
                "poll", poll(List.of("예", "아니오"), false, false, closesAt)));
        assertThat(r.getResponse().getStatus()).isEqualTo(201);
        JsonNode timed = json(r).path("poll");
        assertThat(timed.path("closesAt").asText()).startsWith(closesAt.substring(0, 16));
        assertThat(timed.path("closed").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("정리: 나가기·내보내기 → 그 사람의 읽음·투표 삭제, 회원 탈퇴 → 읽음·투표 삭제와 작성자 넘김, 모임 삭제 → 공지 삭제")
    void cleanup() throws Exception {
        Session owner = login("방장"), a = login("가"), b = login("나"), c = login("다");
        String groupId = createGroup(owner, a, b, c);
        String noticeId = createPollNotice(owner, groupId, false, false);
        Map<String, String> opt = optionIds(detail(owner, groupId, noticeId));
        for (Session s : List.of(a, b, c)) {
            detail(s, groupId, noticeId);
            assertThat(vote(s, groupId, noticeId, opt.get("가")).getResponse().getStatus()).isEqualTo(200);
        }

        // 나가기
        mockMvc.perform(auth(delete("/api/groups/" + groupId + "/members/me"), a)).andExpect(status().isNoContent());
        assertThat(readsOf(a)).isZero();
        assertThat(votesOf(a)).isZero();
        // 내보내기
        mockMvc.perform(auth(delete("/api/groups/" + groupId + "/members/" + b.userId()), owner))
                .andExpect(status().isOk());
        assertThat(readsOf(b)).isZero();
        assertThat(votesOf(b)).isZero();
        JsonNode after = detail(owner, groupId, noticeId);
        assertThat(after.path("memberCount").asInt()).isEqualTo(2);
        assertThat(after.path("poll").path("voterCount").asInt()).isEqualTo(1);

        // 회원 탈퇴: 멤버는 읽음·투표 삭제, 방장이 탈퇴하면 공지 작성자가 새 방장으로
        mockMvc.perform(auth(delete("/api/users/me"), owner)).andExpect(status().isNoContent());
        assertThat(readsOf(owner)).isZero();
        assertThat(votesOf(owner)).isZero();
        assertThat(count("SELECT COUNT(*) FROM group_notices WHERE id = ? AND author_id = ?",
                UUID.fromString(noticeId), UUID.fromString(c.userId()))).isEqualTo(1);
        JsonNode inherited = detail(c, groupId, noticeId);
        assertThat(inherited.path("author").path("nickname").asText()).isEqualTo("다");
        assertThat(inherited.path("canManage").asBoolean()).isTrue();

        mockMvc.perform(auth(delete("/api/users/me"), c)).andExpect(status().isNoContent()); // 혼자 남은 모임 → 모임 삭제
        assertThat(count("SELECT COUNT(*) FROM group_notices WHERE id = ?", UUID.fromString(noticeId))).isZero();
        assertThat(readsOf(c)).isZero();
        assertThat(votesOf(c)).isZero();

        // 모임 삭제 → 공지·읽음·투표 삭제
        Session o2 = login("방장2"), m2 = login("멤버2");
        String g2 = createGroup(o2, m2);
        String n2 = createPollNotice(o2, g2, false, false);
        detail(m2, g2, n2);
        vote(m2, g2, n2, optionIds(detail(m2, g2, n2)).get("가"));
        assertThat(votesOf(m2)).isEqualTo(1);
        mockMvc.perform(auth(delete("/api/groups/" + g2), o2)).andExpect(status().isNoContent());
        assertThat(count("SELECT COUNT(*) FROM group_notices WHERE id = ?", UUID.fromString(n2))).isZero();
        assertThat(count("SELECT COUNT(*) FROM group_notice_polls WHERE notice_id = ?", UUID.fromString(n2))).isZero();
        assertThat(readsOf(m2)).isZero();
        assertThat(votesOf(m2)).isZero();
    }
}
