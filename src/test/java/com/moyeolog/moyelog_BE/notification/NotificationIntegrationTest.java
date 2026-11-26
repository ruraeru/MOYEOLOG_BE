package com.moyeolog.moyelog_BE.notification;

import com.fasterxml.jackson.databind.JsonNode;
import com.moyeolog.moyelog_BE.event.NotificationEvent;
import com.moyeolog.moyelog_BE.event.NotificationRetractEvent;
import com.moyeolog.moyelog_BE.event.NotificationType;
import com.moyeolog.moyelog_BE.event.UserDeletingEvent;
import com.moyeolog.moyelog_BE.notification.NotifyTestSupport.Session;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;

import java.util.Map;
import java.util.UUID;

import static com.moyeolog.moyelog_BE.notification.NotifyTestSupport.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 계약 7절 알림: 이벤트 → 커밋 뒤 저장, 설정, 읽음·삭제, 회원 탈퇴 정리 */
@SpringBootTest
class NotificationIntegrationTest {

    @Autowired private WebApplicationContext context;
    @Autowired private ApplicationEventPublisher publisher;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private NotificationRepository notificationRepository;
    @Autowired private NotificationPreferenceRepository preferenceRepository;

    private MockMvc mockMvc;
    private TransactionTemplate tx;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        tx = new TransactionTemplate(transactionManager);
    }

    private void publishInTx(UUID to, UUID actor, NotificationType type, String message, String link) {
        tx.executeWithoutResult(s -> publisher.publishEvent(new NotificationEvent(to, actor, type, message, link)));
    }

    private JsonNode list(Session s) throws Exception {
        return json(mockMvc.perform(auth(get("/api/notifications"), s)).andExpect(status().isOk()).andReturn());
    }

    private long unread(Session s) throws Exception {
        return json(mockMvc.perform(auth(get("/api/notifications/unread-count"), s))
                .andExpect(status().isOk()).andReturn()).path("count").asLong();
    }

    @Test
    @DisplayName("이벤트: 커밋 뒤 저장, 롤백·자기 자신·없는 사용자·빈 문장은 저장하지 않음, 외부 링크는 버림")
    void event_savedAfterCommitOnly() throws Exception {
        Session a = login(mockMvc, "알림받는이");
        Session b = login(mockMvc, "행동한이");

        publishInTx(a.userId(), b.userId(), NotificationType.FRIEND_REQUEST, "행동한이님이 친구 요청을 보냈어요.", "/friends");
        // 롤백된 트랜잭션의 이벤트는 무시
        tx.executeWithoutResult(s -> {
            publisher.publishEvent(new NotificationEvent(a.userId(), b.userId(), NotificationType.MEMO_SHARED, "롤백", "/memo/x"));
            s.setRollbackOnly();
        });
        publishInTx(a.userId(), a.userId(), NotificationType.MEMO_SHARED, "자기 자신", "/memo/x");
        publishInTx(UUID.randomUUID(), b.userId(), NotificationType.MEMO_SHARED, "없는 사용자", "/memo/x");
        publishInTx(a.userId(), b.userId(), NotificationType.MEMO_SHARED, "   ", "/memo/x");
        // 트랜잭션 밖 발행도 처리, 외부 링크는 null
        publisher.publishEvent(new NotificationEvent(a.userId(), null, NotificationType.GROUP_KICKED,
                "모임에서 내보내졌어요.", "https://evil.example"));

        JsonNode items = list(a);
        assertThat(items.size()).isEqualTo(2);
        JsonNode latest = items.get(0);
        assertThat(latest.path("type").asText()).isEqualTo("GROUP_KICKED");
        assertThat(latest.path("link").isNull()).isTrue();
        assertThat(latest.path("actor").isNull()).isTrue();
        assertThat(latest.path("read").asBoolean()).isFalse();
        JsonNode first = items.get(1);
        assertThat(first.path("type").asText()).isEqualTo("FRIEND_REQUEST");
        assertThat(first.path("message").asText()).isEqualTo("행동한이님이 친구 요청을 보냈어요.");
        assertThat(first.path("link").asText()).isEqualTo("/friends");
        assertThat(first.path("actor").path("id").asText()).isEqualTo(b.userId().toString());
        assertThat(first.path("actor").path("nickname").asText()).isEqualTo("행동한이");
        assertThat(first.has("createdAt")).isTrue();
        assertThat(unread(a)).isEqualTo(2);
        assertThat(list(b).size()).isZero();
    }

    @Test
    @DisplayName("설정: 기본 모두 true, 보낸 필드만 바뀜, 꺼진 종류는 알림을 만들지 않음")
    void preferences() throws Exception {
        Session a = login(mockMvc, "설정");
        Session b = login(mockMvc, "상대");

        mockMvc.perform(auth(get("/api/notifications/preferences"), a))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.friendRequest").value(true))
                .andExpect(jsonPath("$.groupInvite").value(true))
                .andExpect(jsonPath("$.memoShared").value(true))
                .andExpect(jsonPath("$.topicComment").value(true))
                .andExpect(jsonPath("$.scheduleParticipant").value(true))
                .andExpect(jsonPath("$.message").value(true));

        mockMvc.perform(jsonBody(auth(put("/api/notifications/preferences"), a),
                        Map.of("memoShared", false, "friendRequest", false)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.memoShared").value(false))
                .andExpect(jsonPath("$.friendRequest").value(false))
                .andExpect(jsonPath("$.groupInvite").value(true));
        mockMvc.perform(jsonBody(auth(put("/api/notifications/preferences"), a), Map.of("topicComment", false)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.memoShared").value(false))
                .andExpect(jsonPath("$.topicComment").value(false));

        publishInTx(a.userId(), b.userId(), NotificationType.MEMO_SHARED, "공유", "/memo/1");
        publishInTx(a.userId(), b.userId(), NotificationType.FRIEND_ACCEPTED, "수락", "/friends");
        publishInTx(a.userId(), b.userId(), NotificationType.TOPIC_COMMENT, "댓글", "/groups/1?topic=2");
        publishInTx(a.userId(), b.userId(), NotificationType.SCHEDULE_PARTICIPANT, "일정", "/schedule/1");

        JsonNode items = list(a);
        assertThat(texts(items, "type")).containsExactly("SCHEDULE_PARTICIPANT");
    }

    @Test
    @DisplayName("읽음·모두 읽음·삭제: 내 알림만, 남의 알림은 404, 페이지 크기")
    void readAndDelete() throws Exception {
        Session a = login(mockMvc, "읽는이");
        Session other = login(mockMvc, "남");
        for (int i = 0; i < 3; i++) {
            publishInTx(a.userId(), null, NotificationType.GROUP_INVITE, "초대 " + i, "/notifications?tab=invites");
        }
        JsonNode items = list(a);
        assertThat(texts(items, "message")).containsExactly("초대 2", "초대 1", "초대 0");
        assertThat(json(mockMvc.perform(auth(get("/api/notifications?page=1&size=2"), a))
                .andExpect(status().isOk()).andReturn()).size()).isEqualTo(1);

        String id0 = items.get(0).path("id").asText();
        String id1 = items.get(1).path("id").asText();
        mockMvc.perform(auth(put("/api/notifications/" + id0 + "/read"), other))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("알림을 찾을 수 없어요."));
        mockMvc.perform(auth(delete("/api/notifications/" + id0), other)).andExpect(status().isNotFound());

        mockMvc.perform(auth(put("/api/notifications/" + id0 + "/read"), a)).andExpect(status().isNoContent());
        assertThat(unread(a)).isEqualTo(2);
        assertThat(list(a).get(0).path("read").asBoolean()).isTrue();

        mockMvc.perform(auth(delete("/api/notifications/" + id1), a)).andExpect(status().isNoContent());
        mockMvc.perform(auth(delete("/api/notifications/" + id1), a)).andExpect(status().isNotFound());
        assertThat(list(a).size()).isEqualTo(2);

        mockMvc.perform(auth(put("/api/notifications/read-all"), a)).andExpect(status().isNoContent());
        assertThat(unread(a)).isZero();

        // 읽은 알림 모두 지우기: 읽은 것만 지운다
        publishInTx(a.userId(), null, NotificationType.MEMO_SHARED, "새로 온 알림", "/memo/new");
        mockMvc.perform(auth(delete("/api/notifications/read"), a)).andExpect(status().isNoContent());
        assertThat(texts(list(a), "message")).containsExactly("새로 온 알림");

        mockMvc.perform(auth(put("/api/notifications/not-a-uuid/read"), a)).andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/notifications")).andExpect(status().is4xxClientError());
    }

    @Test
    @DisplayName("회원 탈퇴 이벤트: 받은·보낸 알림과 설정 삭제")
    void userDeleting_cleansUp() throws Exception {
        Session a = login(mockMvc, "탈퇴자");
        Session b = login(mockMvc, "남는이");
        publishInTx(a.userId(), b.userId(), NotificationType.MEMO_SHARED, "a 가 받음", "/memo/1");
        publishInTx(b.userId(), a.userId(), NotificationType.MEMO_SHARED, "a 가 행동", "/memo/2");
        publishInTx(b.userId(), null, NotificationType.GROUP_INVITE, "무관", "/notifications?tab=invites");
        mockMvc.perform(jsonBody(auth(put("/api/notifications/preferences"), a), Map.of("message", false)))
                .andExpect(status().isOk());

        tx.executeWithoutResult(s -> publisher.publishEvent(new UserDeletingEvent(a.userId())));

        assertThat(list(a).size()).isZero();
        assertThat(texts(list(b), "message")).containsExactly("무관");
        assertThat(preferenceRepository.existsById(a.userId())).isFalse();
    }

    @Test
    @DisplayName("링크로 지우기: 그 링크의 알림만 지우고 같은 종류의 다른 알림은 남긴다")
    void retractByLink() throws Exception {
        Session a = login(mockMvc, "초대받은이");
        publishInTx(a.userId(), null, NotificationType.GROUP_INVITE, "처리한 초대", "/notifications?tab=invites&group=g1");
        publishInTx(a.userId(), null, NotificationType.GROUP_INVITE, "대기 초대", "/notifications?tab=invites&group=g2");

        tx.executeWithoutResult(s -> publisher.publishEvent(new NotificationRetractEvent(a.userId(), null,
                NotificationType.GROUP_INVITE, "/notifications?tab=invites&group=g1")));

        assertThat(texts(list(a), "message")).containsExactly("대기 초대");
    }

    @Test
    @DisplayName("지운 대상 링크로 지우기: 모든 받는 사람에게서 그 링크 알림만 지운다")
    void retractAllByLink() throws Exception {
        Session a = login(mockMvc, "받은이1");
        Session b = login(mockMvc, "받은이2");
        publishInTx(a.userId(), null, NotificationType.MEMO_SHARED, "지운 메모", "/memo/m1");
        publishInTx(b.userId(), null, NotificationType.MEMO_SHARED, "지운 메모", "/memo/m1");
        publishInTx(a.userId(), null, NotificationType.MEMO_SHARED, "남은 메모", "/memo/m2");

        tx.executeWithoutResult(s -> publisher.publishEvent(NotificationRetractEvent.ofLink("/memo/m1")));

        assertThat(texts(list(a), "message")).containsExactly("남은 메모");
        assertThat(texts(list(b), "message")).isEmpty();
    }

    private String pendingRequestId(Session receiver) throws Exception {
        JsonNode requests = json(mockMvc.perform(auth(get("/api/friends/requests"), receiver))
                .andExpect(status().isOk()).andReturn());
        return requests.get(0).path("id").asText();
    }

    private long friendRequestNotifications(Session s) throws Exception {
        long n = 0;
        for (JsonNode item : list(s)) {
            if ("FRIEND_REQUEST".equals(item.path("type").asText())) n++;
        }
        return n;
    }

    @Test
    @DisplayName("친구 요청이 취소·거절·수락되면 받은 사람의 FRIEND_REQUEST 알림이 지워짐 (다른 알림은 유지)")
    void friendRequest_retractedWhenCancelledRejectedOrAccepted() throws Exception {
        Session a = login(mockMvc, "보내는이");
        Session b = login(mockMvc, "받는이");
        Session c = login(mockMvc, "다른이");
        publishInTx(b.userId(), a.userId(), NotificationType.MEMO_SHARED, "a 가 공유", "/memo/1");

        // 취소 (보낸 사람이 삭제) — 여러 번 반복해도 남지 않음
        for (int i = 0; i < 2; i++) {
            mockMvc.perform(jsonBody(auth(post("/api/friends/request"), a), Map.of("customId", b.customId())))
                    .andExpect(status().isOk());
            assertThat(friendRequestNotifications(b)).isEqualTo(1);
            mockMvc.perform(auth(delete("/api/friends/" + pendingRequestId(b)), a)).andExpect(status().is2xxSuccessful());
            assertThat(friendRequestNotifications(b)).isZero();
        }

        // 다른 사람이 보낸 요청 알림은 영향 없음
        mockMvc.perform(jsonBody(auth(post("/api/friends/request"), c), Map.of("customId", b.customId())))
                .andExpect(status().isOk());

        // 거절 (받은 사람이 삭제)
        mockMvc.perform(jsonBody(auth(post("/api/friends/request"), a), Map.of("customId", b.customId())))
                .andExpect(status().isOk());
        assertThat(friendRequestNotifications(b)).isEqualTo(2);
        String fromA = null;
        for (JsonNode r : json(mockMvc.perform(auth(get("/api/friends/requests"), b)).andReturn())) {
            if (a.customId().equals(r.path("customId").asText())) fromA = r.path("id").asText();
        }
        mockMvc.perform(auth(delete("/api/friends/" + fromA), b)).andExpect(status().is2xxSuccessful());
        assertThat(friendRequestNotifications(b)).isEqualTo(1);

        // 수락
        mockMvc.perform(auth(put("/api/friends/accept/" + pendingRequestId(b)), b)).andExpect(status().is2xxSuccessful());
        assertThat(friendRequestNotifications(b)).isZero();
        assertThat(texts(list(b), "message")).containsExactly("a 가 공유");
        // 수락 알림은 요청자에게 남음
        assertThat(texts(list(c), "type")).containsExactly("FRIEND_ACCEPTED");
    }

    @Test
    @DisplayName("safeLink: 앱 내부 경로만 허용")
    void safeLink() {
        assertThat(NotificationService.safeLink("/groups/1?topic=2")).isEqualTo("/groups/1?topic=2");
        assertThat(NotificationService.safeLink("//evil.example")).isNull();
        assertThat(NotificationService.safeLink("/\\evil")).isNull();
        assertThat(NotificationService.safeLink("javascript:alert(1)")).isNull();
        assertThat(NotificationService.safeLink(null)).isNull();
    }
}
