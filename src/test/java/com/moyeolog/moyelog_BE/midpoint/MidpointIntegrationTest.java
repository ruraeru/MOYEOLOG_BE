package com.moyeolog.moyelog_BE.midpoint;

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
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 일정 중간 지점 찾기 통합 테스트. 실제 필터 체인/컨트롤러/JPA(H2) 를 거친다. */
@SpringBootTest
class MidpointIntegrationTest {

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

    private record Session(String userId, String customId, String accessToken) {
    }

    private Session login(String name) throws Exception {
        String kakaoId = name + "-" + UUID.randomUUID();
        String body = mapper.writeValueAsString(Map.of(
                "kakaoId", kakaoId, "email", kakaoId + "@example.com", "emailVerified", true, "nickname", name));
        JsonNode json = json(mockMvc.perform(post("/api/auth/sync").header(SECRET_HEADER, SECRET)
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk()).andReturn());
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

    private String createGroup(Session owner) throws Exception {
        return json(mockMvc.perform(jsonBody(auth(post("/api/groups"), owner), Map.of("name", "중간 지점 모임")))
                .andExpect(status().isOk()).andReturn()).path("id").asText();
    }

    private void joinGroup(Session member, String groupId, Session owner) throws Exception {
        String code = json(mockMvc.perform(auth(get("/api/groups/" + groupId), owner)).andReturn())
                .path("inviteCode").asText();
        mockMvc.perform(auth(post("/api/groups/join").param("code", code), member));
    }

    private String createSchedule(Session session, Map<String, Object> extra) throws Exception {
        Map<String, Object> body = new HashMap<>(Map.of(
                "title", "저녁 모임", "startTime", "2026-12-01T19:00:00", "endTime", "2026-12-01T21:00:00"));
        body.putAll(extra);
        return json(mockMvc.perform(jsonBody(auth(post("/api/schedules"), session), body))
                .andExpect(status().isOk()).andReturn()).path("id").asText();
    }

    private String base(String scheduleId) {
        return "/api/schedules/" + scheduleId + "/midpoint";
    }

    private MvcResult putDeparture(Session s, String scheduleId, String name, double lat, double lng) throws Exception {
        return mockMvc.perform(jsonBody(auth(put(base(scheduleId) + "/departure"), s),
                Map.of("name", name, "latitude", lat, "longitude", lng))).andReturn();
    }

    private long notificationCount(Session s) {
        Long n = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM notifications WHERE recipient_id = ?", Long.class, UUID.fromString(s.userId()));
        return n == null ? 0 : n;
    }

    @Test
    @DisplayName("모임 일정: 출발지를 적으면 가운데 좌표가 나오고, 다른 사람 좌표는 내려가지 않는다")
    void groupSchedule_centerAndPrivacy() throws Exception {
        Session owner = login("방장"), member = login("멤버"), third = login("셋째"), outsider = login("바깥");
        String groupId = createGroup(owner);
        joinGroup(member, groupId, owner);
        joinGroup(third, groupId, owner);
        String scheduleId = createSchedule(owner, Map.of("groupId", groupId));

        // 바깥 사람은 볼 수도 적을 수도 없다
        mockMvc.perform(auth(get(base(scheduleId)), outsider)).andExpect(status().isForbidden());
        assertThat(putDeparture(outsider, scheduleId, "어딘가", 37.5, 127.0).getResponse().getStatus()).isEqualTo(403);
        mockMvc.perform(auth(get(base(UUID.randomUUID().toString())), owner)).andExpect(status().isNotFound());

        // 한 명만 적으면 가운데 좌표가 없다
        JsonNode one = json(putDeparture(owner, scheduleId, "강남역", 37.4979, 127.0276));
        assertThat(one.path("enteredCount").asInt()).isEqualTo(1);
        assertThat(one.path("expectedCount").asInt()).isEqualTo(3);
        assertThat(one.path("center").isNull()).isTrue();
        assertThat(one.path("canConfirm").asBoolean()).isTrue();

        // 두 명이 적으면 평균 좌표
        assertThat(putDeparture(member, scheduleId, "홍대입구역", 37.5572, 126.9245).getResponse().getStatus()).isEqualTo(200);
        JsonNode seen = json(mockMvc.perform(auth(get(base(scheduleId)), member)).andExpect(status().isOk()).andReturn());
        assertThat(seen.path("center").path("latitude").asDouble()).isCloseTo((37.4979 + 37.5572) / 2, within(1e-9));
        assertThat(seen.path("center").path("longitude").asDouble()).isCloseTo((127.0276 + 126.9245) / 2, within(1e-9));
        assertThat(seen.path("canConfirm").asBoolean()).isFalse();
        assertThat(seen.path("myDeparture").path("name").asText()).isEqualTo("홍대입구역");
        // 다른 사람의 출발지는 이름만
        assertThat(seen.path("departures")).hasSize(2);
        for (JsonNode d : seen.path("departures")) {
            assertThat(d.has("latitude")).isFalse();
            assertThat(d.has("longitude")).isFalse();
        }

        // 다시 적으면 한 줄이 바뀐다 (늘지 않는다)
        JsonNode again = json(putDeparture(member, scheduleId, "합정역", 37.5495, 126.9139));
        assertThat(again.path("enteredCount").asInt()).isEqualTo(2);
        assertThat(again.path("myDeparture").path("name").asText()).isEqualTo("합정역");

        // 지우면 가운데 좌표가 다시 없어진다
        JsonNode removed = json(mockMvc.perform(auth(delete(base(scheduleId) + "/departure"), member))
                .andExpect(status().isOk()).andReturn());
        assertThat(removed.path("enteredCount").asInt()).isEqualTo(1);
        assertThat(removed.path("center").isNull()).isTrue();

        // 잘못된 입력
        mockMvc.perform(jsonBody(auth(put(base(scheduleId) + "/departure"), member),
                Map.of("name", "이름만"))).andExpect(status().isBadRequest());
        mockMvc.perform(jsonBody(auth(put(base(scheduleId) + "/departure"), member),
                Map.of("name", "범위 밖", "latitude", 123.0, "longitude", 127.0))).andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("출발지 요청·장소 확정은 일정을 만든 사람만, 알림은 나머지 사람에게")
    void requestAndConfirm_authorOnly() throws Exception {
        Session owner = login("방장"), member = login("멤버"), third = login("셋째");
        String groupId = createGroup(owner);
        joinGroup(member, groupId, owner);
        joinGroup(third, groupId, owner);
        String scheduleId = createSchedule(owner, Map.of("groupId", groupId));
        putDeparture(member, scheduleId, "홍대입구역", 37.5572, 126.9245);
        long memberBefore = notificationCount(member), thirdBefore = notificationCount(third);

        mockMvc.perform(auth(post(base(scheduleId) + "/request"), member)).andExpect(status().isForbidden());
        // 이미 적은 멤버는 빼고 아직 안 적은 사람에게만 간다
        JsonNode sent = json(mockMvc.perform(auth(post(base(scheduleId) + "/request"), owner))
                .andExpect(status().isOk()).andReturn());
        assertThat(sent.path("sent").asInt()).isEqualTo(1);
        assertThat(notificationCount(member)).isEqualTo(memberBefore);
        assertThat(notificationCount(third)).isEqualTo(thirdBefore + 1);

        mockMvc.perform(jsonBody(auth(post(base(scheduleId) + "/confirm"), member), Map.of("name", "합정역")))
                .andExpect(status().isForbidden());
        mockMvc.perform(jsonBody(auth(post(base(scheduleId) + "/confirm"), owner), Map.of("name", " ")))
                .andExpect(status().isBadRequest());
        JsonNode confirmed = json(mockMvc.perform(jsonBody(auth(post(base(scheduleId) + "/confirm"), owner),
                Map.of("name", "합정역 2호선", "latitude", 37.5495, "longitude", 126.9139))).andExpect(status().isOk()).andReturn());
        assertThat(confirmed.path("location").asText()).isEqualTo("합정역 2호선");
        assertThat(notificationCount(member)).isEqualTo(memberBefore + 1);
        assertThat(notificationCount(third)).isEqualTo(thirdBefore + 2);

        // 일정 목록에도 장소가 들어간다
        String location = null;
        for (JsonNode s : json(mockMvc.perform(auth(get("/api/schedules"), member)).andReturn())) {
            if (s.path("id").asText().equals(scheduleId)) {
                location = s.path("location").asText();
                assertThat(s.path("latitude").asDouble()).isCloseTo(37.5495, within(1e-9));
                assertThat(s.path("longitude").asDouble()).isCloseTo(126.9139, within(1e-9));
            }
        }
        assertThat(location).isEqualTo("합정역 2호선");

        // 좌표 없이 다시 정하면 예전 좌표는 지워진다 (다른 장소의 날씨가 나오지 않게)
        mockMvc.perform(jsonBody(auth(post(base(scheduleId) + "/confirm"), owner), Map.of("name", "직접 정한 곳")))
                .andExpect(status().isOk());
        for (JsonNode s : json(mockMvc.perform(auth(get("/api/schedules"), member)).andReturn())) {
            if (s.path("id").asText().equals(scheduleId)) assertThat(s.path("latitude").isNull()).isTrue();
        }
    }

    @Test
    @DisplayName("지난번 출발지를 알려 주고, 일정을 지우면 출발지도 지워진다")
    void lastDeparture_andCascadeOnScheduleDelete() throws Exception {
        Session owner = login("혼자");
        String first = createSchedule(owner, Map.of());
        String second = createSchedule(owner, Map.of());
        putDeparture(owner, first, "잠실역", 37.5133, 127.1001);

        JsonNode fresh = json(mockMvc.perform(auth(get(base(second)), owner)).andExpect(status().isOk()).andReturn());
        assertThat(fresh.path("myDeparture").isNull()).isTrue();
        assertThat(fresh.path("lastDeparture").path("name").asText()).isEqualTo("잠실역");
        assertThat(fresh.path("expectedCount").asInt()).isEqualTo(1);

        mockMvc.perform(auth(delete("/api/schedules/" + first), owner)).andExpect(status().isNoContent());
        Long rows = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM schedule_departures d JOIN users u ON u.id = d.user_id WHERE u.id = ?",
                Long.class, UUID.fromString(owner.userId()));
        assertThat(rows).isZero();
    }
}
