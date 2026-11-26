package com.moyeolog.moyelog_BE.datepoll;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.moyeolog.moyelog_BE.exception.AiAnalysisException;
import com.moyeolog.moyelog_BE.service.GeminiService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.AbstractMockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 모임 날짜 조율 통합 테스트. 실제 필터 체인/컨트롤러/JPA(H2) 를 거치고 LLM 만 가짜로 바꾼다. */
@SpringBootTest
class DatePollIntegrationTest {

    private static final String SECRET_HEADER = "X-Internal-Secret";
    private static final String SECRET = "test-internal-secret";

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private GeminiService geminiService;

    private final ObjectMapper mapper = new ObjectMapper();
    private MockMvc mockMvc;

    // 자정 경계에 걸리지 않게 내일부터 닷새
    private final LocalDate d1 = LocalDate.now().plusDays(1), d2 = d1.plusDays(1), d3 = d1.plusDays(2),
            d4 = d1.plusDays(3), d5 = d1.plusDays(4);

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
        String groupId = json(mockMvc.perform(jsonBody(auth(post("/api/groups"), owner), Map.of("name", "날짜 모임")))
                .andExpect(status().isOk()).andReturn()).path("id").asText();
        String code = json(mockMvc.perform(auth(get("/api/groups/" + groupId), owner)).andReturn())
                .path("inviteCode").asText();
        for (Session member : members) {
            mockMvc.perform(auth(post("/api/groups/join").param("code", code), member)).andExpect(status().isOk());
        }
        return groupId;
    }

    private String base(String groupId) {
        return "/api/groups/" + groupId + "/date-polls";
    }

    private MvcResult createPoll(Session s, String groupId, String title, LocalDate start, LocalDate end) throws Exception {
        return mockMvc.perform(jsonBody(auth(post(base(groupId)), s),
                Map.of("title", title, "startDate", start.toString(), "endDate", end.toString()))).andReturn();
    }

    private String openPoll(Session owner, String groupId) throws Exception {
        MvcResult r = createPoll(owner, groupId, "송년회", d1, d5);
        assertThat(r.getResponse().getStatus()).isEqualTo(201);
        return json(r).path("id").asText();
    }

    private MvcResult vote(Session s, String groupId, String pollId, LocalDate... dates) throws Exception {
        List<String> list = new ArrayList<>();
        for (LocalDate d : dates) list.add(d.toString());
        return mockMvc.perform(jsonBody(auth(put(base(groupId) + "/" + pollId + "/votes"), s), Map.of("dates", list)))
                .andReturn();
    }

    private JsonNode detail(Session s, String groupId, String pollId) throws Exception {
        return json(mockMvc.perform(auth(get(base(groupId) + "/" + pollId), s)).andExpect(status().isOk()).andReturn());
    }

    private long notificationCount(Session s) {
        Long n = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM notifications WHERE recipient_id = ?", Long.class, UUID.fromString(s.userId()));
        return n == null ? 0 : n;
    }

    private long votesOf(Session s) {
        Long n = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM date_poll_votes WHERE user_id = ?", Long.class, UUID.fromString(s.userId()));
        return n == null ? 0 : n;
    }

    private long count(String sql, Object... args) {
        Long n = jdbcTemplate.queryForObject(sql, Long.class, args);
        return n == null ? 0 : n;
    }

    private static List<String> texts(JsonNode array) {
        List<String> out = new ArrayList<>();
        array.forEach(n -> out.add(n.isTextual() ? n.asText() : n.path("nickname").asText()));
        return out;
    }

    @Test
    @DisplayName("권한: 비멤버는 403, 만들기·재촉·정하기·지우기는 모임장만. 만들기 검증과 알림")
    void permissionsAndValidation() throws Exception {
        Session owner = login("방장"), member = login("멤버"), outsider = login("바깥");
        String groupId = createGroup(owner, member);

        // 만들기 검증
        assertThat(createPoll(owner, groupId, "어제부터", LocalDate.now().minusDays(1), d1).getResponse().getStatus()).isEqualTo(400);
        assertThat(createPoll(owner, groupId, "거꾸로", d3, d1).getResponse().getStatus()).isEqualTo(400);
        assertThat(createPoll(owner, groupId, "너무 김", d1, d1.plusDays(31)).getResponse().getStatus()).isEqualTo(400);
        assertThat(createPoll(owner, groupId, " ", d1, d2).getResponse().getStatus()).isEqualTo(400);
        assertThat(createPoll(owner, groupId, "가".repeat(51), d1, d2).getResponse().getStatus()).isEqualTo(400);
        assertThat(createPoll(owner, groupId, "딱 31일", LocalDate.now(), LocalDate.now().plusDays(30))
                .getResponse().getStatus()).isEqualTo(201);

        long before = notificationCount(member);
        assertThat(createPoll(member, groupId, "멤버가", d1, d2).getResponse().getStatus()).isEqualTo(403);
        String pollId = openPoll(owner, groupId);
        assertThat(notificationCount(member)).isEqualTo(before + 1);
        String link = jdbcTemplate.queryForObject(
                "SELECT link FROM notifications WHERE recipient_id = ? ORDER BY created_at DESC LIMIT 1",
                String.class, UUID.fromString(member.userId()));
        assertThat(link).isEqualTo("/groups/" + groupId + "?tab=calendar");

        // 비멤버: 목록·상세·선택·추천 403
        mockMvc.perform(auth(get(base(groupId)), outsider)).andExpect(status().isForbidden());
        mockMvc.perform(auth(get(base(groupId) + "/" + pollId), outsider)).andExpect(status().isForbidden());
        assertThat(vote(outsider, groupId, pollId, d1).getResponse().getStatus()).isEqualTo(403);
        mockMvc.perform(auth(post(base(groupId) + "/" + pollId + "/recommend"), outsider)).andExpect(status().isForbidden());

        // 멤버: 모임장 전용 403
        mockMvc.perform(auth(post(base(groupId) + "/" + pollId + "/remind"), member)).andExpect(status().isForbidden());
        mockMvc.perform(jsonBody(auth(post(base(groupId) + "/" + pollId + "/confirm"), member),
                Map.of("date", d1.toString(), "startTime", "19:00"))).andExpect(status().isForbidden());
        mockMvc.perform(auth(delete(base(groupId) + "/" + pollId), member)).andExpect(status().isForbidden());

        // 다른 모임 경로로는 찾을 수 없다
        String otherGroup = createGroup(member);
        mockMvc.perform(auth(get(base(otherGroup) + "/" + pollId), member)).andExpect(status().isNotFound());

        // 재촉: 아직 응답 안 한 멤버에게만
        assertThat(vote(owner, groupId, pollId, d1).getResponse().getStatus()).isEqualTo(200);
        long ownerBefore = notificationCount(owner), memberBefore = notificationCount(member);
        mockMvc.perform(auth(post(base(groupId) + "/" + pollId + "/remind"), owner)).andExpect(status().isNoContent());
        assertThat(notificationCount(member)).isEqualTo(memberBefore + 1);
        assertThat(notificationCount(owner)).isEqualTo(ownerBefore);

        // 목록: 진행 중 두 개
        JsonNode list = json(mockMvc.perform(auth(get(base(groupId)), member)).andExpect(status().isOk()).andReturn());
        assertThat(list.size()).isEqualTo(2);
        assertThat(list.get(0).path("id").asText()).isEqualTo(pollId);
        assertThat(list.get(0).path("respondedCount").asInt()).isEqualTo(1);
        assertThat(list.get(0).path("memberCount").asInt()).isEqualTo(2);
        assertThat(list.get(0).path("myResponded").asBoolean()).isFalse();
        assertThat(list.get(0).path("startDate").asText()).isEqualTo(d1.toString());
    }

    @Test
    @DisplayName("선택 바꾸기·집계·best 순서, '가능한 날 없음', 후보 밖 날짜 400")
    void votesAndTally() throws Exception {
        Session owner = login("방장"), a = login("가"), b = login("나"), c = login("다"), d = login("라");
        String groupId = createGroup(owner, a, b, c, d);
        String pollId = openPoll(owner, groupId);

        vote(a, groupId, pollId, d1, d2);
        vote(b, groupId, pollId, d2, d3);
        vote(owner, groupId, pollId, d3, d2);
        assertThat(vote(c, groupId, pollId).getResponse().getStatus()).isEqualTo(200); // 가능한 날 없음

        JsonNode detail = detail(a, groupId, pollId);
        assertThat(texts(detail.path("best"))).containsExactly(d2.toString(), d3.toString(), d1.toString());
        assertThat(detail.path("dates").size()).isEqualTo(5);
        assertThat(detail.path("dates").get(1).path("count").asInt()).isEqualTo(3);
        assertThat(texts(detail.path("dates").get(1).path("voters"))).containsExactly("방장", "가", "나");
        assertThat(detail.path("dates").get(4).path("count").asInt()).isZero();
        assertThat(texts(detail.path("myDates"))).containsExactly(d1.toString(), d2.toString());
        assertThat(texts(detail.path("responded"))).containsExactly("방장", "가", "나", "다");
        assertThat(texts(detail.path("notResponded"))).containsExactly("라");
        assertThat(detail.path("respondedCount").asInt()).isEqualTo(4);
        assertThat(detail.path("memberCount").asInt()).isEqualTo(5);
        assertThat(detail.path("creator").path("nickname").asText()).isEqualTo("방장");
        assertThat(detail.path("ai").isNull()).isTrue();
        assertThat(detail.path("status").asText()).isEqualTo("OPEN");

        // 통째로 바꾸기: 가 는 d4 만
        JsonNode changed = json(vote(a, groupId, pollId, d4));
        assertThat(texts(changed.path("myDates"))).containsExactly(d4.toString());
        assertThat(changed.path("dates").get(0).path("count").asInt()).isZero();
        assertThat(texts(changed.path("best"))).containsExactly(d2.toString(), d3.toString(), d4.toString());

        // '가능한 날 없음'에서 날짜로, 다시 없음으로
        assertThat(texts(json(vote(c, groupId, pollId, d5)).path("myDates"))).containsExactly(d5.toString());
        JsonNode none = json(vote(c, groupId, pollId));
        assertThat(none.path("myDates").size()).isZero();
        assertThat(none.path("myResponded").asBoolean()).isTrue();
        assertThat(votesOf(c)).isEqualTo(1);

        // 후보 밖 날짜는 400 이고 기존 선택은 그대로
        assertThat(vote(a, groupId, pollId, d4, d5.plusDays(1)).getResponse().getStatus()).isEqualTo(400);
        assertThat(vote(a, groupId, pollId, d1.minusDays(1)).getResponse().getStatus()).isEqualTo(400);
        assertThat(texts(detail(a, groupId, pollId).path("myDates"))).containsExactly(d4.toString());
        // 날짜 형식이 틀리면 400
        mockMvc.perform(jsonBody(auth(put(base(groupId) + "/" + pollId + "/votes"), a), Map.of("dates", List.of("내일"))))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("확정: 모임 일정이 생기고(참여자 = 그 날 가능한 사람), 그 뒤 선택은 409. 지워도 일정은 남는다")
    void confirmCreatesSchedule() throws Exception {
        Session owner = login("방장"), a = login("가"), b = login("나");
        String groupId = createGroup(owner, a, b);
        String pollId = openPoll(owner, groupId);
        vote(a, groupId, pollId, d2);
        vote(b, groupId, pollId, d3);

        // 후보 밖 날짜, 끝이 시작보다 빠름 → 400
        mockMvc.perform(jsonBody(auth(post(base(groupId) + "/" + pollId + "/confirm"), owner),
                Map.of("date", d5.plusDays(1).toString(), "startTime", "19:00"))).andExpect(status().isBadRequest());
        mockMvc.perform(jsonBody(auth(post(base(groupId) + "/" + pollId + "/confirm"), owner),
                Map.of("date", d2.toString(), "startTime", "19:00", "endTime", "18:00"))).andExpect(status().isBadRequest());
        mockMvc.perform(jsonBody(auth(post(base(groupId) + "/" + pollId + "/confirm"), owner),
                Map.of("date", d2.toString()))).andExpect(status().isBadRequest());

        long aBefore = notificationCount(a), bBefore = notificationCount(b);
        JsonNode confirmed = json(mockMvc.perform(jsonBody(auth(post(base(groupId) + "/" + pollId + "/confirm"), owner),
                Map.of("date", d2.toString(), "startTime", "19:00", "endTime", "21:30"))).andExpect(status().isOk()).andReturn());
        assertThat(confirmed.path("status").asText()).isEqualTo("CONFIRMED");
        assertThat(confirmed.path("confirmedDate").asText()).isEqualTo(d2.toString());
        String scheduleId = confirmed.path("scheduleId").asText();
        assertThat(scheduleId).isNotBlank();
        assertThat(notificationCount(b)).isEqualTo(bBefore + 1);
        // 참여자(가)는 일정 생성의 '참여자로 추가' 알림 하나만 받는다(확정 알림 없음)
        assertThat(notificationCount(a)).isEqualTo(aBefore + 1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT message FROM notifications WHERE recipient_id = ? ORDER BY created_at DESC LIMIT 1",
                String.class, UUID.fromString(a.userId()))).contains("참여자로 추가했어요");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT message FROM notifications WHERE recipient_id = ? ORDER BY created_at DESC LIMIT 1",
                String.class, UUID.fromString(b.userId()))).contains("로 정했어요");

        JsonNode schedules = json(mockMvc.perform(auth(get("/api/groups/" + groupId + "/schedules"), b))
                .andExpect(status().isOk()).andReturn());
        JsonNode schedule = null;
        for (JsonNode s : schedules) if (s.path("id").asText().equals(scheduleId)) schedule = s;
        assertThat(schedule).isNotNull();
        assertThat(schedule.path("title").asText()).isEqualTo("송년회");
        assertThat(schedule.path("startTime").asText()).startsWith(d2 + "T19:00");
        assertThat(schedule.path("endTime").asText()).startsWith(d2 + "T21:30");
        assertThat(texts(schedule.path("participants"))).containsExactly("가");

        // 정해진 뒤에는 선택·재촉·다시 정하기 409
        assertThat(vote(b, groupId, pollId, d2).getResponse().getStatus()).isEqualTo(409);
        mockMvc.perform(auth(post(base(groupId) + "/" + pollId + "/remind"), owner)).andExpect(status().isConflict());
        mockMvc.perform(jsonBody(auth(post(base(groupId) + "/" + pollId + "/confirm"), owner),
                Map.of("date", d3.toString(), "startTime", "19:00"))).andExpect(status().isConflict());

        // 목록: 정해진 것은 진행 중 뒤에
        String open = openPoll(owner, groupId);
        JsonNode list = json(mockMvc.perform(auth(get(base(groupId)), a)).andExpect(status().isOk()).andReturn());
        assertThat(texts(list).size()).isEqualTo(2);
        assertThat(list.get(0).path("id").asText()).isEqualTo(open);
        assertThat(list.get(1).path("status").asText()).isEqualTo("CONFIRMED");
        assertThat(list.get(1).path("scheduleId").asText()).isEqualTo(scheduleId);

        // 끝 시각을 안 주면 한 시간
        JsonNode noEnd = json(mockMvc.perform(jsonBody(auth(post(base(groupId) + "/" + open + "/confirm"), owner),
                Map.of("date", d1.toString(), "startTime", "10:00"))).andExpect(status().isOk()).andReturn());
        assertThat(count("SELECT COUNT(*) FROM schedules WHERE id = ? AND end_time = ?",
                UUID.fromString(noEnd.path("scheduleId").asText()), d1.atTime(11, 0))).isEqualTo(1);

        // 조율을 지워도 일정은 남는다
        mockMvc.perform(auth(delete(base(groupId) + "/" + pollId), owner)).andExpect(status().isNoContent());
        mockMvc.perform(auth(get(base(groupId) + "/" + pollId), owner)).andExpect(status().isNotFound());
        assertThat(count("SELECT COUNT(*) FROM schedules WHERE id = ?", UUID.fromString(scheduleId))).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM date_poll_votes WHERE poll_id = ?", UUID.fromString(pollId))).isZero();
    }

    @Test
    @DisplayName("정리: 멤버 나가기·내보내기 → 그 사람 선택 삭제, 모임 삭제 → 조율·선택 삭제, 회원 탈퇴 → 선택 삭제·만든 사람 넘김")
    void cleanup() throws Exception {
        Session owner = login("방장"), a = login("가"), b = login("나"), c = login("다");
        String groupId = createGroup(owner, a, b, c);
        String pollId = openPoll(owner, groupId);
        vote(owner, groupId, pollId, d1);
        vote(a, groupId, pollId, d1, d2);
        vote(b, groupId, pollId, d1);
        vote(c, groupId, pollId);

        mockMvc.perform(auth(delete("/api/groups/" + groupId + "/members/me"), a)).andExpect(status().isNoContent());
        assertThat(votesOf(a)).isZero();
        mockMvc.perform(auth(delete("/api/groups/" + groupId + "/members/" + b.userId()), owner))
                .andExpect(status().isOk());
        assertThat(votesOf(b)).isZero();
        JsonNode detail = detail(owner, groupId, pollId);
        assertThat(detail.path("dates").get(0).path("count").asInt()).isEqualTo(1);
        assertThat(detail.path("memberCount").asInt()).isEqualTo(2);

        // 회원 탈퇴: 멤버의 선택은 지우고, 방장이 탈퇴하면 모임과 조율이 다음 멤버에게 넘어간다
        mockMvc.perform(auth(delete("/api/users/me"), owner)).andExpect(status().isNoContent());
        assertThat(votesOf(owner)).isZero();
        assertThat(count("SELECT COUNT(*) FROM date_polls WHERE id = ? AND creator_id = ?",
                UUID.fromString(pollId), UUID.fromString(c.userId()))).isEqualTo(1);
        JsonNode after = detail(c, groupId, pollId);
        assertThat(after.path("creator").path("nickname").asText()).isEqualTo("다");
        assertThat(after.path("respondedCount").asInt()).isEqualTo(1);

        mockMvc.perform(auth(delete("/api/users/me"), c)).andExpect(status().isNoContent()); // 혼자 남은 모임 → 모임 삭제
        assertThat(count("SELECT COUNT(*) FROM date_polls WHERE id = ?", UUID.fromString(pollId))).isZero();

        // 모임 삭제 → 조율·선택 삭제
        Session o2 = login("방장2"), m2 = login("멤버2");
        String g2 = createGroup(o2, m2);
        String p2 = openPoll(o2, g2);
        vote(m2, g2, p2, d1);
        mockMvc.perform(auth(delete("/api/groups/" + g2), o2)).andExpect(status().isNoContent());
        assertThat(count("SELECT COUNT(*) FROM date_polls WHERE id = ?", UUID.fromString(p2))).isZero();
        assertThat(votesOf(m2)).isZero();
    }

    @Test
    @DisplayName("AI 추천: 정상 JSON, 후보 밖·0명인 날짜는 버리고 best 로 채움, LLM 실패 502, 응답 0명 400, 선택이 바뀌면 stale")
    void recommend() throws Exception {
        Session owner = login("방장"), a = login("가"), b = login("나");
        String groupId = createGroup(owner, a, b);
        String pollId = openPoll(owner, groupId);
        String url = base(groupId) + "/" + pollId + "/recommend";

        // 응답 0명
        mockMvc.perform(auth(post(url), a)).andExpect(status().isBadRequest());

        vote(a, groupId, pollId, d2, d3);
        vote(b, groupId, pollId, d3);

        // 정상: 후보 안 날짜만, 순서 유지. 프롬프트에 집계와 이름이 들어간다
        when(geminiService.generateJson(contains("2명 가능 - 가, 나"), any(), any())).thenReturn(Map.of(
                "summary", "3명 중 2명이 응답했어요. 셋째 날이 가장 좋아요.",
                "recommended", List.of(
                        Map.of("date", d3.toString(), "reason", "두 사람 모두 가능해요."),
                        Map.of("date", d2.toString(), "reason", "가님이 가능해요."),
                        Map.of("date", d1.toString(), "reason", "아무도 안 되는 날"))));
        JsonNode ok = json(mockMvc.perform(auth(post(url), a)).andExpect(status().isOk()).andReturn());
        assertThat(ok.path("ai").path("summary").asText()).startsWith("3명 중 2명");
        assertThat(ok.path("ai").path("recommended").get(0).path("date").asText()).isEqualTo(d3.toString());
        assertThat(ok.path("ai").path("recommended").get(0).path("reason").asText()).isEqualTo("두 사람 모두 가능해요.");
        assertThat(ok.path("ai").path("recommended").size()).isEqualTo(2);  // 0명인 d1 은 버린다
        assertThat(ok.path("ai").path("stale").asBoolean()).isFalse();
        assertThat(detail(b, groupId, pollId).path("ai").path("stale").asBoolean()).isFalse();

        // 누가 선택을 바꾸면 stale
        vote(owner, groupId, pollId, d1);
        assertThat(detail(b, groupId, pollId).path("ai").path("stale").asBoolean()).isTrue();

        // 후보 밖 날짜·형식 틀린 날짜만 → best 로 채우고 이유는 '○명 중 ○명 가능'
        when(geminiService.generateJson(any(), any(), any())).thenReturn(Map.of(
                "summary", "요약이에요.",
                "recommended", List.of(Map.of("date", d5.plusDays(10).toString(), "reason", "밖"),
                        Map.of("date", d4.toString(), "reason", "0명인 날"),
                        Map.of("date", "다음 주", "reason", "형식"), "문자열")));
        JsonNode fallback = json(mockMvc.perform(auth(post(url), owner)).andExpect(status().isOk()).andReturn());
        JsonNode recs = fallback.path("ai").path("recommended");
        assertThat(recs.size()).isEqualTo(3);
        assertThat(recs.get(0).path("date").asText()).isEqualTo(d3.toString());
        assertThat(recs.get(0).path("reason").asText()).isEqualTo("3명 중 2명 가능");
        assertThat(recs.get(1).path("date").asText()).isEqualTo(d1.toString());
        assertThat(fallback.path("ai").path("stale").asBoolean()).isFalse();

        // 한자·가나가 섞인 이유·요약은 문장 전체를 기본 문구로 바꾼다
        when(geminiService.generateJson(any(), any(), any())).thenReturn(Map.of(
                "summary", "세 분이 모두出席할 수 있어요.",
                "recommended", List.of(
                        Map.of("date", d3.toString(), "reason", "두 분이 모두出席할 수 있는 날이에요."),
                        Map.of("date", d1.toString(), "reason", "方장님이 되는 날이에요ね."),
                        Map.of("date", d2.toString(), "reason", "가님이 가능해요."))));
        JsonNode cjk = json(mockMvc.perform(auth(post(url), owner)).andExpect(status().isOk()).andReturn());
        assertThat(cjk.path("ai").path("summary").asText()).isEqualTo("3명 중 3명이 응답했어요.");
        JsonNode cjkRecs = cjk.path("ai").path("recommended");
        assertThat(cjkRecs.get(0).path("reason").asText()).isEqualTo("3명 중 2명 가능");
        assertThat(cjkRecs.get(1).path("reason").asText()).isEqualTo("3명 중 1명 가능");
        assertThat(cjkRecs.get(2).path("reason").asText()).isEqualTo("가님이 가능해요.");

        // LLM 실패 → 502, 지난 추천과 집계는 그대로
        when(geminiService.generateJson(any(), any(), any()))
                .thenThrow(new AiAnalysisException("AI 분석 중 문제가 생겼어요. 잠시 후 다시 시도해 주세요."));
        mockMvc.perform(auth(post(url), b)).andExpect(status().isBadGateway());
        JsonNode afterFail = detail(b, groupId, pollId);
        assertThat(afterFail.path("ai").path("summary").asText()).isEqualTo("3명 중 3명이 응답했어요.");
        assertThat(texts(afterFail.path("best"))).containsExactly(d3.toString(), d1.toString(), d2.toString());
    }
}
