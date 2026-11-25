package com.moyeolog.moyelog_BE;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.moyeolog.moyelog_BE.event.UserDeletingEvent;
import com.moyeolog.moyelog_BE.repository.GroupTopicInsightRepository;
import com.moyeolog.moyelog_BE.repository.MemoAiInsightRepository;
import com.moyeolog.moyelog_BE.service.AiAnalysisSupport;
import com.moyeolog.moyelog_BE.service.GeminiService;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.AbstractMockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * AI 분석 흐름 통합 테스트. LLM(GeminiService)은 모킹하고 실제 서버는 부르지 않는다.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:moyeolog-ai-test;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
        "llm.base-url=http://127.0.0.1:9"
})
class AiAnalysisIntegrationTest {

    private static final String SECRET = "test-internal-secret";
    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0, 1, 2, 3};

    @MockitoBean
    private GeminiService geminiService;

    @Autowired private WebApplicationContext context;
    @Autowired private DataSource dataSource;
    @Autowired private ApplicationEventPublisher publisher;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private MemoAiInsightRepository memoAiInsightRepository;
    @Autowired private GroupTopicInsightRepository groupTopicInsightRepository;

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
        String body = mapper.writeValueAsString(Map.of("kakaoId", kakaoId, "email", kakaoId + "@example.com", "emailVerified", true, "nickname", name));
        JsonNode json = json(mockMvc.perform(post("/api/auth/sync").header("X-Internal-Secret", SECRET)
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

    private MockMultipartFile memoPart(Object memo) throws Exception {
        return new MockMultipartFile("memo", "", "application/json", mapper.writeValueAsBytes(memo));
    }

    private String createMemo(Session session, String title, String content) throws Exception {
        return json(mockMvc.perform(auth(multipart("/api/memos").file(memoPart(Map.of("title", title, "content", content))), session))
                .andExpect(status().isOk()).andReturn()).path("id").asText();
    }

    private static Map<String, Object> result(String summary, String... keywords) {
        Map<String, Object> result = new HashMap<>();
        result.put("ocrText", "");
        result.put("summary", summary);
        result.put("keywords", List.of(keywords));
        return result;
    }

    private int activeConnections() throws Exception {
        return dataSource.unwrap(HikariDataSource.class).getHikariPoolMXBean().getActiveConnections();
    }

    @Test
    @DisplayName("메모 분석: LLM 대기 중 트랜잭션·DB 커넥션을 잡지 않고, 메모를 고치면 stale=true, 다시 분석하면 false")
    void memoAnalyze_noConnectionDuringLlm_andStaleAfterEdit() throws Exception {
        Session author = login("ai-author");
        Session stranger = login("ai-stranger");
        String memoId = createMemo(author, "스터디", "매주 화요일 저녁 8시 강남역");

        AtomicInteger activeDuringCall = new AtomicInteger(-1);
        AtomicBoolean txDuringCall = new AtomicBoolean(true);
        when(geminiService.analyze(any(), any(), any())).thenAnswer(inv -> {
            activeDuringCall.set(activeConnections());
            txDuringCall.set(TransactionSynchronizationManager.isActualTransactionActive());
            return result("화요일 강남역 스터디.", "강남역");
        });

        // 작성자가 아니면 403 이고 LLM 을 부르지 않는다
        mockMvc.perform(auth(post("/api/memos/" + memoId + "/analyze"), stranger)).andExpect(status().isForbidden());
        verify(geminiService, never()).analyze(any(), any(), any());

        mockMvc.perform(auth(post("/api/memos/" + memoId + "/analyze"), author))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.summary").value("화요일 강남역 스터디."))
                .andExpect(jsonPath("$.keywords[0]").value("강남역"))
                .andExpect(jsonPath("$.analyzedAt").isNotEmpty())
                .andExpect(jsonPath("$.stale").value(false));
        verify(geminiService).analyze(eq("매주 화요일 저녁 8시 강남역"), eq(null), eq(memoId));
        assertThat(txDuringCall.get()).isFalse();
        assertThat(activeDuringCall.get()).isZero();

        mockMvc.perform(auth(get("/api/memos/" + memoId), author)).andExpect(status().isOk())
                .andExpect(jsonPath("$.insight.stale").value(false));

        // 내용을 바꾸면 예전 요약은 stale
        mockMvc.perform(auth(multipart("/api/memos/" + memoId)
                        .file(memoPart(Map.of("title", "스터디", "content", "목요일 신촌 토즈")))
                        .with(r -> { r.setMethod("PUT"); return r; }), author))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.insight.stale").value(true));
        mockMvc.perform(auth(get("/api/memos/" + memoId + "/insight"), author)).andExpect(status().isOk())
                .andExpect(jsonPath("$.stale").value(true));

        mockMvc.perform(auth(post("/api/memos/" + memoId + "/analyze"), author)).andExpect(status().isOk())
                .andExpect(jsonPath("$.stale").value(false));
        verify(geminiService).analyze(eq("목요일 신촌 토즈"), eq(null), eq(memoId));
    }

    @Test
    @DisplayName("같은 메모를 동시에 분석하면 두 번째 요청은 LLM 을 부르지 않고 409, 끝나면 다시 분석할 수 있다")
    void memoAnalyze_concurrentRequestGets409() throws Exception {
        Session author = login("ai-conc");
        String memoId = createMemo(author, "동시", "동시 분석");

        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(geminiService.analyze(any(), any(), any())).thenAnswer(inv -> {
            entered.countDown();
            release.await(10, TimeUnit.SECONDS);
            return result("요약.");
        });

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<Integer> first = executor.submit(() -> mockMvc
                    .perform(auth(post("/api/memos/" + memoId + "/analyze"), author)).andReturn().getResponse().getStatus());
            assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();

            mockMvc.perform(auth(post("/api/memos/" + memoId + "/analyze"), author))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.message").value(AiAnalysisSupport.IN_PROGRESS_MESSAGE));

            release.countDown();
            assertThat(first.get(10, TimeUnit.SECONDS)).isEqualTo(200);
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
        verify(geminiService, times(1)).analyze(any(), any(), any());

        // 잠금이 풀려 다음 분석은 정상
        mockMvc.perform(auth(post("/api/memos/" + memoId + "/analyze"), author)).andExpect(status().isOk());
        assertThat(memoAiInsightRepository.count()).isPositive();
    }

    @Test
    @DisplayName("토픽 분석: 토픽 이미지 주소를 LLM 에 넘기고, 비멤버는 403, 토픽을 고치면 stale=true")
    void topicAnalyze_passesImage_andStale() throws Exception {
        Session owner = login("ai-topic");
        Session outsider = login("ai-outsider");
        String groupId = json(mockMvc.perform(jsonBody(auth(post("/api/groups"), owner), Map.of("name", "AI 모임")))
                .andExpect(status().isOk()).andReturn()).path("id").asText();
        String dataUrl = "data:image/png;base64," + Base64.getEncoder().encodeToString(PNG);
        JsonNode topic = json(mockMvc.perform(jsonBody(auth(post("/api/groups/" + groupId + "/topics"), owner),
                        Map.of("title", "화이트보드", "content", "지난 회의 사진", "imageUrl", dataUrl)))
                .andExpect(status().isOk()).andReturn());
        String topicId = topic.path("id").asText();
        String storedImage = topic.path("imageUrl").asText();
        assertThat(storedImage).isNotBlank();

        when(geminiService.analyze(any(), any(), any())).thenReturn(result("회의 결과.", "발표"));

        mockMvc.perform(auth(post("/api/topics/" + topicId + "/analyze"), outsider)).andExpect(status().isForbidden());
        verify(geminiService, never()).analyze(any(), any(), any());

        mockMvc.perform(auth(post("/api/topics/" + topicId + "/analyze"), owner)).andExpect(status().isOk())
                .andExpect(jsonPath("$.keywords[0]").value("발표"))
                .andExpect(jsonPath("$.stale").value(false));
        verify(geminiService).analyze(eq("지난 회의 사진"), eq(storedImage), eq(topicId));

        mockMvc.perform(auth(get("/api/topics/" + topicId), owner)).andExpect(status().isOk())
                .andExpect(jsonPath("$.insight.stale").value(false));
        mockMvc.perform(jsonBody(auth(put("/api/topics/" + topicId), owner),
                        Map.of("title", "화이트보드", "content", "바뀐 본문", "imageUrl", storedImage)))
                .andExpect(status().isOk());
        mockMvc.perform(auth(get("/api/topics/" + topicId), owner)).andExpect(status().isOk())
                .andExpect(jsonPath("$.insight.stale").value(true));
    }

    @Test
    @DisplayName("회원 탈퇴 이벤트: 그 사용자 메모·토픽의 분석 결과를 지운다")
    void userDeleting_removesInsights() throws Exception {
        Session author = login("ai-leaver");
        String memoId = createMemo(author, "탈퇴", "지울 메모");
        String groupId = json(mockMvc.perform(jsonBody(auth(post("/api/groups"), author), Map.of("name", "혼자 모임")))
                .andExpect(status().isOk()).andReturn()).path("id").asText();
        String topicId = json(mockMvc.perform(jsonBody(auth(post("/api/groups/" + groupId + "/topics"), author),
                        Map.of("title", "t", "content", "c")))
                .andExpect(status().isOk()).andReturn()).path("id").asText();
        when(geminiService.analyze(any(), any(), any())).thenReturn(result("요약."));
        mockMvc.perform(auth(post("/api/memos/" + memoId + "/analyze"), author)).andExpect(status().isOk());
        mockMvc.perform(auth(post("/api/topics/" + topicId + "/analyze"), author)).andExpect(status().isOk());
        assertThat(memoAiInsightRepository.existsById(UUID.fromString(memoId))).isTrue();
        assertThat(groupTopicInsightRepository.existsById(UUID.fromString(topicId))).isTrue();

        new TransactionTemplate(transactionManager).executeWithoutResult(
                s -> publisher.publishEvent(new UserDeletingEvent(UUID.fromString(author.userId()))));

        assertThat(memoAiInsightRepository.existsById(UUID.fromString(memoId))).isFalse();
        assertThat(groupTopicInsightRepository.existsById(UUID.fromString(topicId))).isFalse();
    }
}
