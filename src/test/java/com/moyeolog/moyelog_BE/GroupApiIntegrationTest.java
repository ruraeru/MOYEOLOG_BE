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

/** 2차 QA 모임·초대·토픽 수정 사항 통합 테스트 (H2) */
@SpringBootTest
class GroupApiIntegrationTest {

    private static final String SECRET_HEADER = "X-Internal-Secret";
    private static final String SECRET = "test-internal-secret";

    @Autowired private WebApplicationContext context;
    @Autowired private JdbcTemplate jdbcTemplate;

    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0, 1, 2, 3};

    @Value("${file.upload-dir}")
    private String uploadDir;

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
        MvcResult result = mockMvc.perform(post("/api/auth/sync").header(SECRET_HEADER, SECRET)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("kakaoId", kakaoId, "email", kakaoId + "@example.com",
                                "nickname", name, "emailVerified", true))))
                .andExpect(status().isOk()).andReturn();
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
        JsonNode requests = json(mockMvc.perform(auth(get("/api/friends/requests"), b)).andReturn());
        String requestId = null;
        for (JsonNode r : requests) {
            requestId = r.path("id").asText();
        }
        mockMvc.perform(auth(put("/api/friends/accept/" + requestId), b)).andExpect(status().isOk());
    }

    private JsonNode createGroup(Session owner, Map<String, Object> body) throws Exception {
        return json(mockMvc.perform(jsonBody(auth(post("/api/groups"), owner), body))
                .andExpect(status().isOk()).andReturn());
    }

    @Test
    @DisplayName("모임 만들기: 이모지 30자 허용, 이름 trim, 색은 정해진 값만, myRole·멤버 customId")
    void createGroupValidation() throws Exception {
        Session owner = login("gv");
        JsonNode group = createGroup(owner, Map.of("name", "  " + "📚".repeat(30) + "  "));
        assertThat(group.path("name").asText()).isEqualTo("📚".repeat(30));
        assertThat(group.path("colorTheme").asText()).isEqualTo("indigo");
        assertThat(group.path("myRole").asText()).isEqualTo("OWNER");
        assertThat(group.path("members").get(0).path("customId").asText()).isEqualTo(owner.customId());

        mockMvc.perform(jsonBody(auth(post("/api/groups"), owner), Map.of("name", "가".repeat(51))))
                .andExpect(status().isBadRequest());
        mockMvc.perform(jsonBody(auth(post("/api/groups"), owner), Map.of("name", "​")))
                .andExpect(status().isBadRequest());
        mockMvc.perform(jsonBody(auth(post("/api/groups"), owner), Map.of("name", "n", "colorTheme", "url(javascript:alert(1))")))
                .andExpect(status().isBadRequest());
        mockMvc.perform(jsonBody(auth(post("/api/groups"), owner), Map.of("name", "n", "colorTheme", "rose")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.colorTheme").value("rose"));
    }

    private MockMultipartHttpServletRequestBuilder putGroup(String groupId, Object group, boolean image, boolean bgImage)
            throws Exception {
        MockMultipartHttpServletRequestBuilder builder = multipart("/api/groups/" + groupId);
        builder.file(new MockMultipartFile("group", "", "application/json", mapper.writeValueAsBytes(group)));
        if (image) builder.file(new MockMultipartFile("image", "p.png", "image/png", PNG));
        if (bgImage) builder.file(new MockMultipartFile("bgImage", "b.png", "image/png", PNG));
        builder.with(r -> {
            r.setMethod("PUT");
            return r;
        });
        return builder;
    }

    private Path uploaded(String url) {
        return Paths.get(uploadDir).resolve(url.substring("/uploads/".length()));
    }

    @Test
    @DisplayName("모임 사진·색: 올리기 → 목록·상세·미리보기에 반영 → 교체 시 이전 파일 삭제 → 지우기 플래그 → 방장 아니면 403")
    void groupIdentity() throws Exception {
        Session owner = login("gi-owner");
        Session member = login("gi-member");
        JsonNode group = createGroup(owner, Map.of("name", "표지 모임"));
        String groupId = group.path("id").asText();
        String code = group.path("inviteCode").asText();
        mockMvc.perform(auth(post("/api/groups/join").param("code", code), member)).andExpect(status().isOk());

        JsonNode first = json(mockMvc.perform(auth(putGroup(groupId, Map.of("name", "표지 모임", "colorTheme", "Amber"), true, true), owner))
                .andExpect(status().isOk()).andReturn());
        String profile = first.path("profileImage").asText();
        String background = first.path("backgroundImage").asText();
        assertThat(profile).startsWith("/uploads/");
        assertThat(background).startsWith("/uploads/");
        assertThat(first.path("colorTheme").asText()).isEqualTo("amber");
        assertThat(Files.exists(uploaded(profile))).isTrue();
        assertThat(Files.exists(uploaded(background))).isTrue();

        // 멤버가 보는 목록·상세, 코드 미리보기에 모두 나온다
        JsonNode mine = json(mockMvc.perform(auth(get("/api/groups"), member)).andExpect(status().isOk()).andReturn());
        JsonNode listed = null;
        for (JsonNode g : mine) {
            if (g.path("id").asText().equals(groupId)) listed = g;
        }
        assertThat(listed).isNotNull();
        assertThat(listed.path("profileImage").asText()).isEqualTo(profile);
        assertThat(listed.path("backgroundImage").asText()).isEqualTo(background);
        assertThat(listed.path("colorTheme").asText()).isEqualTo("amber");
        mockMvc.perform(auth(get("/api/groups/" + groupId), member))
                .andExpect(jsonPath("$.profileImage").value(profile))
                .andExpect(jsonPath("$.backgroundImage").value(background))
                .andExpect(jsonPath("$.colorTheme").value("amber"));
        mockMvc.perform(auth(get("/api/groups/preview").param("code", code), login("gi-guest")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.profileImage").value(profile))
                .andExpect(jsonPath("$.backgroundImage").value(background))
                .andExpect(jsonPath("$.colorTheme").value("amber"));

        // 방장이 아니면 사진을 지울 수 없다. 잘못된 색은 400 이고 사진도 그대로
        mockMvc.perform(auth(putGroup(groupId, Map.of("name", "x", "removeProfileImage", true), false, false), member))
                .andExpect(status().isForbidden());
        mockMvc.perform(auth(putGroup(groupId, Map.of("name", "x", "colorTheme", "#ff0000", "removeProfileImage", true), false, false), owner))
                .andExpect(status().isBadRequest());
        assertThat(Files.exists(uploaded(profile))).isTrue();

        // 프로필 교체: 이전 파일은 지워지고 배경은 그대로
        JsonNode replaced = json(mockMvc.perform(auth(putGroup(groupId, Map.of("name", "표지 모임"), true, false), owner))
                .andExpect(status().isOk()).andReturn());
        String newProfile = replaced.path("profileImage").asText();
        assertThat(newProfile).isNotEqualTo(profile).startsWith("/uploads/");
        assertThat(replaced.path("backgroundImage").asText()).isEqualTo(background);
        assertThat(Files.exists(uploaded(profile))).isFalse();
        assertThat(Files.exists(uploaded(newProfile))).isTrue();

        // 지우기 플래그: 두 사진 모두 null, 파일도 삭제. 색은 그대로
        mockMvc.perform(auth(putGroup(groupId, Map.of("name", "표지 모임",
                        "removeProfileImage", true, "removeBackgroundImage", true), false, false), owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.profileImage").doesNotExist())
                .andExpect(jsonPath("$.backgroundImage").doesNotExist())
                .andExpect(jsonPath("$.colorTheme").value("amber"));
        assertThat(Files.exists(uploaded(newProfile))).isFalse();
        assertThat(Files.exists(uploaded(background))).isFalse();
        mockMvc.perform(auth(get("/api/groups/preview").param("code", code), member))
                .andExpect(jsonPath("$.backgroundImage").doesNotExist());

        // 지우기와 새 파일이 같이 오면 새 파일이 이긴다
        mockMvc.perform(auth(putGroup(groupId, Map.of("name", "표지 모임", "removeBackgroundImage", true), false, true), owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.backgroundImage").value(org.hamcrest.Matchers.startsWith("/uploads/")));
    }

    @Test
    @DisplayName("초대 코드: 미리 보기 → 가입 {groupId} → 다시 가입 409(groupId), 먼저 받은 초대 정리, 강퇴 후 403")
    void previewAndJoin() throws Exception {
        Session owner = login("pj-owner");
        Session joiner = login("pj-joiner");
        makeFriends(owner, joiner);
        JsonNode group = createGroup(owner, Map.of("name", "캡스톤"));
        String groupId = group.path("id").asText();
        String code = group.path("inviteCode").asText();

        mockMvc.perform(auth(get("/api/groups/preview").param("code", "NOPE00"), joiner))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("초대 코드가 맞지 않거나 만료됐어요."));
        mockMvc.perform(auth(get("/api/groups/preview").param("code", code.toLowerCase()), joiner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("캡스톤"))
                .andExpect(jsonPath("$.memberCount").value(1))
                .andExpect(jsonPath("$.alreadyMember").value(false))
                .andExpect(jsonPath("$.blocked").value(false))
                .andExpect(jsonPath("$.ownerNickname").value("pj-owner"));

        // 먼저 초대를 받아 둔 상태에서 코드로 가입하면 대기 초대가 정리된다
        mockMvc.perform(jsonBody(auth(post("/api/groups/" + groupId + "/invitations"), owner),
                Map.of("userIds", List.of(joiner.userId())))).andExpect(status().isOk());
        mockMvc.perform(auth(post("/api/groups/join").param("code", code), joiner))
                .andExpect(status().isOk()).andExpect(jsonPath("$.groupId").value(groupId));
        assertThat(json(mockMvc.perform(auth(get("/api/groups/invitations"), joiner)).andReturn())).isEmpty();

        mockMvc.perform(auth(post("/api/groups/join").param("code", code), joiner))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("이미 참여한 모임이에요."))
                .andExpect(jsonPath("$.groupId").value(groupId));
        mockMvc.perform(auth(get("/api/groups/" + groupId), joiner))
                .andExpect(jsonPath("$.myRole").value("MEMBER"));

        mockMvc.perform(auth(delete("/api/groups/" + groupId + "/members/" + joiner.userId()), owner)).andExpect(status().isOk());
        String newCode = json(mockMvc.perform(auth(get("/api/groups/" + groupId), owner)).andReturn()).path("inviteCode").asText();
        mockMvc.perform(auth(get("/api/groups/preview").param("code", newCode), joiner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.alreadyMember").value(false))
                .andExpect(jsonPath("$.blocked").value(true));
        mockMvc.perform(auth(post("/api/groups/join").param("code", newCode), joiner))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("이 모임에 다시 들어갈 수 없어요."));
    }

    @Test
    @DisplayName("초대: 친구만(아니면 400, 없는 사용자도 같은 400), 이메일 400, 내보낸 사람은 일반 멤버가 다시 초대 못 함, 남아 있던 초대 정리")
    void invitations() throws Exception {
        Session owner = login("iv-owner");
        Session member = login("iv-member");
        Session kicked = login("iv-kicked");
        Session stranger = login("iv-stranger");
        makeFriends(owner, member);
        makeFriends(owner, kicked);
        makeFriends(member, kicked);
        String groupId = createGroup(owner, Map.of("name", "초대")).path("id").asText();
        String url = "/api/groups/" + groupId + "/invitations";

        mockMvc.perform(jsonBody(auth(post(url), owner), Map.of("userIds", List.of(stranger.userId()))))
                .andExpect(status().isBadRequest());
        mockMvc.perform(jsonBody(auth(post(url), owner), Map.of("userIds", List.of(UUID.randomUUID().toString()))))
                .andExpect(status().isBadRequest());
        mockMvc.perform(jsonBody(auth(post(url), owner), Map.of("emails", List.of("nobody@example.com"))))
                .andExpect(status().isBadRequest());

        // member, kicked 가입
        for (Session s : List.of(member, kicked)) {
            mockMvc.perform(jsonBody(auth(post(url), owner), Map.of("userIds", List.of(s.userId())))).andExpect(status().isOk());
            String id = json(mockMvc.perform(auth(get("/api/groups/invitations"), s)).andReturn()).get(0).path("id").asText();
            mockMvc.perform(auth(post("/api/groups/invitations/" + id + "/accept"), s)).andExpect(status().isOk());
        }
        // kicked 가 member 의 친구 stranger 를... 대신 kicked 가 보낸 대기 초대가 정리되는지 확인
        makeFriends(kicked, stranger);
        mockMvc.perform(jsonBody(auth(post(url), kicked), Map.of("userIds", List.of(stranger.userId())))).andExpect(status().isOk());
        assertThat(json(mockMvc.perform(auth(get("/api/groups/invitations"), stranger)).andReturn())).hasSize(1);

        mockMvc.perform(auth(delete("/api/groups/" + groupId + "/members/" + kicked.userId()), owner)).andExpect(status().isOk());
        assertThat(json(mockMvc.perform(auth(get("/api/groups/invitations"), stranger)).andReturn())).isEmpty();

        // 일반 멤버는 내보낸 사람을 다시 초대할 수 없다
        mockMvc.perform(jsonBody(auth(post(url), member), Map.of("userIds", List.of(kicked.userId()))))
                .andExpect(status().isForbidden());
        // 방장이 초대하면 차단이 풀린다
        mockMvc.perform(jsonBody(auth(post(url), owner), Map.of("userIds", List.of(kicked.userId())))).andExpect(status().isOk());
        String reinvite = json(mockMvc.perform(auth(get("/api/groups/invitations"), kicked)).andReturn()).get(0).path("id").asText();
        mockMvc.perform(auth(post("/api/groups/invitations/" + reinvite + "/reject"), kicked)).andExpect(status().isOk());
        // 거절 직후 일반 멤버의 재초대는 409
        mockMvc.perform(jsonBody(auth(post(url), member), Map.of("userIds", List.of(kicked.userId()))))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("동시성: 같은 사람 동시 초대 → 대기 1건, 동시 나가기·삭제 → 500 없음")
    void concurrency() throws Exception {
        Session owner = login("cc-owner");
        Session friend = login("cc-friend");
        makeFriends(owner, friend);
        String groupId = createGroup(owner, Map.of("name", "동시")).path("id").asText();

        List<Integer> invites = runConcurrently(6, () -> mockMvc.perform(jsonBody(auth(post("/api/groups/" + groupId + "/invitations"), owner),
                Map.of("userIds", List.of(friend.userId())))).andReturn().getResponse().getStatus());
        assertThat(invites).doesNotContain(500);
        assertThat(json(mockMvc.perform(auth(get("/api/groups/invitations"), friend)).andReturn())).hasSize(1);

        String invitationId = json(mockMvc.perform(auth(get("/api/groups/invitations"), friend)).andReturn()).get(0).path("id").asText();
        mockMvc.perform(auth(post("/api/groups/invitations/" + invitationId + "/accept"), friend)).andExpect(status().isOk());

        List<Integer> leaves = runConcurrently(3, () -> mockMvc.perform(auth(delete("/api/groups/" + groupId + "/members/me"), friend))
                .andReturn().getResponse().getStatus());
        assertThat(leaves).doesNotContain(500).contains(204);

        List<Integer> deletes = runConcurrently(2, () -> mockMvc.perform(auth(delete("/api/groups/" + groupId), owner))
                .andReturn().getResponse().getStatus());
        assertThat(deletes).doesNotContain(500).contains(204);
    }

    private List<Integer> runConcurrently(int n, Callable<Integer> task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(n);
        try {
            List<Future<Integer>> futures = new java.util.ArrayList<>();
            for (int i = 0; i < n; i++) futures.add(pool.submit(task));
            List<Integer> result = new java.util.ArrayList<>();
            for (Future<Integer> f : futures) result.add(f.get());
            return result;
        } finally {
            pool.shutdown();
        }
    }

    @Test
    @DisplayName("토픽: data URL 은 /uploads 파일로, 이미지 아닌 내용·남의 파일 400, 나간 사람은 수정 403·삭제 가능, 보이지 않는 댓글 400")
    void topics() throws Exception {
        Session owner = login("tp-owner");
        Session member = login("tp-member");
        makeFriends(owner, member);
        String groupId = createGroup(owner, Map.of("name", "토픽")).path("id").asText();
        mockMvc.perform(jsonBody(auth(post("/api/groups/" + groupId + "/invitations"), owner),
                Map.of("userIds", List.of(member.userId())))).andExpect(status().isOk());
        String inv = json(mockMvc.perform(auth(get("/api/groups/invitations"), member)).andReturn()).get(0).path("id").asText();
        mockMvc.perform(auth(post("/api/groups/invitations/" + inv + "/accept"), member)).andExpect(status().isOk());

        String url = "/api/groups/" + groupId + "/topics";
        JsonNode topic = json(mockMvc.perform(jsonBody(auth(post(url), member),
                        Map.of("title", "t", "content", "c", "imageUrl", "data:image/png;base64,iVBORw0KGgo=")))
                .andExpect(status().isOk()).andReturn());
        String imageUrl = topic.path("imageUrl").asText();
        assertThat(imageUrl).matches("/uploads/[0-9a-f-]{36}\\.png");
        assertThat(topic.path("authorCustomId").asText()).isEqualTo(member.customId());
        String topicId = topic.path("id").asText();

        // 이미지가 아닌 내용, 없는 파일, 남의 파일
        mockMvc.perform(jsonBody(auth(post(url), owner), Map.of("title", "t", "content", "c",
                "imageUrl", "data:image/png;base64,PHNjcmlwdD5hbGVydCgxKTwvc2NyaXB0Pg=="))).andExpect(status().isBadRequest());
        mockMvc.perform(jsonBody(auth(post(url), owner), Map.of("title", "t", "content", "c",
                "imageUrl", "/uploads/00000000-0000-0000-0000-000000000000.png"))).andExpect(status().isBadRequest());
        mockMvc.perform(jsonBody(auth(post(url), owner), Map.of("title", "t", "content", "c",
                "imageUrl", imageUrl))).andExpect(status().isBadRequest());
        // 자기 이미지는 그대로 두고 수정 가능
        mockMvc.perform(jsonBody(auth(put("/api/topics/" + topicId), member), Map.of("title", "t2", "content", "c",
                "imageUrl", imageUrl))).andExpect(status().isOk()).andExpect(jsonPath("$.imageUrl").value(imageUrl));

        String image = jdbcTemplate.queryForObject("select image_url from group_topics where title = 't2'", String.class);
        assertThat(image).isEqualTo(imageUrl);

        mockMvc.perform(jsonBody(auth(post("/api/topics/" + topicId + "/comments"), owner), Map.of("content", "​")))
                .andExpect(status().isBadRequest());
        String commentId = json(mockMvc.perform(jsonBody(auth(post("/api/topics/" + topicId + "/comments"), member),
                Map.of("content", "댓글"))).andExpect(status().isOk()).andReturn()).path("id").asText();

        // 나가면 수정 403, 삭제는 가능
        mockMvc.perform(auth(delete("/api/groups/" + groupId + "/members/me"), member)).andExpect(status().isNoContent());
        mockMvc.perform(jsonBody(auth(put("/api/topics/" + topicId), member), Map.of("title", "나간 뒤", "content", "c")))
                .andExpect(status().isForbidden());
        mockMvc.perform(jsonBody(auth(put("/api/topics/comments/" + commentId), member), Map.of("content", "나간 뒤")))
                .andExpect(status().isForbidden());
        mockMvc.perform(auth(delete("/api/topics/comments/" + commentId), member)).andExpect(status().isOk());
        mockMvc.perform(auth(delete("/api/topics/comments/" + commentId), member)).andExpect(status().isNotFound());
        mockMvc.perform(auth(delete("/api/topics/" + topicId), member)).andExpect(status().isOk());
        mockMvc.perform(auth(delete("/api/topics/" + topicId), member)).andExpect(status().isNotFound());
    }
}
