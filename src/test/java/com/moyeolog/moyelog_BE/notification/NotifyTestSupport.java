package com.moyeolog.moyelog_BE.notification;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.AbstractMockHttpServletRequestBuilder;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 알림·메시지 통합 테스트 공용 도우미 (sync 로 로그인, 친구 맺기) */
public final class NotifyTestSupport {

    public static final ObjectMapper MAPPER = new ObjectMapper();

    public record Session(UUID userId, String customId, String accessToken) {
    }

    private NotifyTestSupport() {
    }

    public static Session login(MockMvc mockMvc, String name) throws Exception {
        String kakaoId = name + "-" + UUID.randomUUID();
        String body = MAPPER.writeValueAsString(Map.of(
                "kakaoId", kakaoId, "email", kakaoId + "@example.com", "nickname", name,
                "emailVerified", true));
        JsonNode json = json(mockMvc.perform(post("/api/auth/sync")
                        .header("X-Internal-Secret", "test-internal-secret")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn());
        return new Session(UUID.fromString(json.path("user").path("id").asText()),
                json.path("user").path("customId").asText(), json.path("accessToken").asText());
    }

    public static JsonNode json(MvcResult result) throws Exception {
        String content = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        return MAPPER.readTree(content.isEmpty() ? "null" : content);
    }

    public static <B extends AbstractMockHttpServletRequestBuilder<B>> B auth(B builder, Session session) {
        return builder.header("Authorization", "Bearer " + session.accessToken());
    }

    public static <B extends AbstractMockHttpServletRequestBuilder<B>> B jsonBody(B builder, Object body) throws Exception {
        return builder.contentType(MediaType.APPLICATION_JSON).content(MAPPER.writeValueAsString(body));
    }

    /** a 가 b 에게 친구 요청, b 가 수락. 관계 ID 를 돌려준다. */
    public static String makeFriends(MockMvc mockMvc, Session a, Session b) throws Exception {
        mockMvc.perform(jsonBody(auth(post("/api/friends/request"), a), Map.of("customId", b.customId())))
                .andExpect(status().isOk());
        JsonNode requests = json(mockMvc.perform(auth(get("/api/friends/requests"), b))
                .andExpect(status().isOk()).andReturn());
        String requestId = null;
        for (JsonNode r : requests) {
            if (a.customId().equals(r.path("customId").asText())) requestId = r.path("id").asText();
        }
        if (requestId == null) requestId = requests.get(0).path("id").asText();
        mockMvc.perform(auth(put("/api/friends/accept/" + requestId), b)).andExpect(status().is2xxSuccessful());
        return requestId;
    }

    public static List<String> texts(JsonNode array, String field) {
        java.util.ArrayList<String> out = new java.util.ArrayList<>();
        array.forEach(n -> out.add(n.path(field).asText()));
        return out;
    }
}
