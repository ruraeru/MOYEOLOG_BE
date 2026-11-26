package com.moyeolog.moyelog_BE.message;

import com.fasterxml.jackson.databind.JsonNode;
import com.moyeolog.moyelog_BE.event.ChangeEvent;
import com.moyeolog.moyelog_BE.event.UserDeletingEvent;
import com.moyeolog.moyelog_BE.notification.NotifyTestSupport.Session;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
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

/** 계약 8절 메시지 */
@SpringBootTest
@RecordApplicationEvents
class MessageIntegrationTest {

    @Autowired private WebApplicationContext context;
    @Autowired private ApplicationEventPublisher publisher;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private MessageRepository messageRepository;
    @Autowired private ApplicationEvents events;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    private JsonNode send(Session from, Session to, String content, int expectedStatus) throws Exception {
        return json(mockMvc.perform(jsonBody(auth(post("/api/messages/" + to.userId()), from), Map.of("content", content)))
                .andExpect(status().is(expectedStatus)).andReturn());
    }

    private long unread(Session s) throws Exception {
        return json(mockMvc.perform(auth(get("/api/messages/unread-count"), s))
                .andExpect(status().isOk()).andReturn()).path("count").asLong();
    }

    private JsonNode conversations(Session s) throws Exception {
        return json(mockMvc.perform(auth(get("/api/messages/conversations"), s)).andExpect(status().isOk()).andReturn());
    }

    private JsonNode messages(Session me, Session friend, String query) throws Exception {
        return json(mockMvc.perform(auth(get("/api/messages/" + friend.userId() + query), me))
                .andExpect(status().isOk()).andReturn());
    }

    @Test
    @DisplayName("친구가 아니면 403, 내용 검증(trim 후 1~2000자, 코드 포인트, 보이지 않는 문자만이면 400), 없는 사용자 404")
    void send_validation() throws Exception {
        Session a = login(mockMvc, "보내는이");
        Session b = login(mockMvc, "받는이");
        Session stranger = login(mockMvc, "모르는이");
        makeFriends(mockMvc, a, b);

        mockMvc.perform(jsonBody(auth(post("/api/messages/" + stranger.userId()), a), Map.of("content", "안녕")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("친구에게만 메시지를 보낼 수 있어요."));
        mockMvc.perform(jsonBody(auth(post("/api/messages/" + a.userId()), a), Map.of("content", "나")))
                .andExpect(status().isForbidden());
        mockMvc.perform(jsonBody(auth(post("/api/messages/" + UUID.randomUUID()), a), Map.of("content", "x")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("사용자를 찾을 수 없어요."));

        send(a, b, "   ", 400);
        send(a, b, "​​", 400);
        send(a, b, "가".repeat(2001), 400);
        mockMvc.perform(auth(post("/api/messages/" + b.userId()), a).contentType("application/json").content("{}"))
                .andExpect(status().isBadRequest());

        // 이모지 2000개(코드 포인트 2000, UTF-16 4000)는 허용
        JsonNode emoji = send(a, b, "😀".repeat(2000), 200);
        assertThat(emoji.path("content").asText().codePointCount(0, emoji.path("content").asText().length())).isEqualTo(2000);

        JsonNode sent = send(a, b, "  안녕하세요  ", 200);
        assertThat(sent.path("content").asText()).isEqualTo("안녕하세요");
        assertThat(sent.path("senderId").asText()).isEqualTo(a.userId().toString());
        assertThat(sent.path("read").asBoolean()).isFalse();
        assertThat(sent.has("id")).isTrue();
        assertThat(sent.has("createdAt")).isTrue();
    }

    @Test
    @DisplayName("대화 목록·메시지 목록(오래된 순, before·size)·읽음·unread-count·알림 설정으로 배지 끔")
    void conversationFlow() throws Exception {
        Session a = login(mockMvc, "가나");
        Session b = login(mockMvc, "다라");
        Session c = login(mockMvc, "마바");
        makeFriends(mockMvc, a, b);
        makeFriends(mockMvc, c, a);

        send(a, b, "1", 200);
        send(b, a, "2", 200);
        send(b, a, "3", 200);
        send(c, a, "c1", 200);

        // a 의 대화 목록: 최근 메시지 순 (c 가 마지막에 보냄)
        JsonNode conv = conversations(a);
        assertThat(conv.size()).isEqualTo(2);
        assertThat(conv.get(0).path("friend").path("id").asText()).isEqualTo(c.userId().toString());
        assertThat(conv.get(0).path("friend").path("customId").asText()).isEqualTo(c.customId());
        assertThat(conv.get(0).path("lastMessage").path("content").asText()).isEqualTo("c1");
        assertThat(conv.get(0).path("unreadCount").asLong()).isEqualTo(1);
        assertThat(conv.get(1).path("lastMessage").path("content").asText()).isEqualTo("3");
        assertThat(conv.get(1).path("lastMessage").path("senderId").asText()).isEqualTo(b.userId().toString());
        assertThat(conv.get(1).path("unreadCount").asLong()).isEqualTo(2);
        assertThat(unread(a)).isEqualTo(3);

        // 메시지 없는 친구는 lastMessage=null 로 뒤에
        Session d = login(mockMvc, "사아");
        makeFriends(mockMvc, d, a);
        JsonNode conv2 = conversations(a);
        assertThat(conv2.size()).isEqualTo(3);
        assertThat(conv2.get(2).path("lastMessage").isNull()).isTrue();
        assertThat(conv2.get(2).path("unreadCount").asLong()).isZero();

        // 오래된 순, 마지막이 최신
        JsonNode msgs = messages(a, b, "");
        assertThat(texts(msgs, "content")).containsExactly("1", "2", "3");
        assertThat(texts(messages(b, a, ""), "content")).containsExactly("1", "2", "3");
        assertThat(texts(messages(a, b, "?size=2"), "content")).containsExactly("2", "3");
        String before = msgs.get(1).path("createdAt").asText();
        assertThat(texts(messages(a, b, "?before=" + before + "&size=50"), "content")).containsExactly("1");

        // 읽음 처리: b 가 보낸 것만 읽음, c 것은 그대로
        mockMvc.perform(auth(put("/api/messages/" + b.userId() + "/read"), a)).andExpect(status().isNoContent());
        assertThat(unread(a)).isEqualTo(1);
        JsonNode afterRead = messages(a, b, "");
        assertThat(afterRead.get(1).path("read").asBoolean()).isTrue();
        assertThat(afterRead.get(0).path("read").asBoolean()).isFalse(); // a 가 보낸 것은 b 가 아직 안 읽음
        assertThat(unread(b)).isEqualTo(1);

        // 알림 설정에서 메시지를 끄면 배지 0
        mockMvc.perform(jsonBody(auth(put("/api/notifications/preferences"), a), Map.of("message", false)))
                .andExpect(status().isOk());
        assertThat(unread(a)).isZero();
        assertThat(conversations(a).get(0).path("unreadCount").asLong()).isEqualTo(1);

        mockMvc.perform(auth(get("/api/messages/" + UUID.randomUUID()), a)).andExpect(status().isNotFound());
        mockMvc.perform(auth(get("/api/messages/" + b.userId() + "?before=nope"), a)).andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("대화 지우기: 내 쪽에서만 사라지고 상대에게는 남는다. 안 읽은 수도 빠지고, 새 메시지는 다시 보인다")
    void deleteConversation_oneSided() throws Exception {
        Session a = login(mockMvc, "지우는이");
        Session b = login(mockMvc, "남는이");
        makeFriends(mockMvc, a, b);
        send(a, b, "보낸 것", 200);
        send(b, a, "받은 것", 200);
        assertThat(unread(a)).isEqualTo(1);

        mockMvc.perform(auth(delete("/api/messages/" + b.userId()), a)).andExpect(status().isNoContent());

        // a 쪽: 메시지 없음, 배지 0, 친구라서 목록에는 남되 마지막 메시지가 없다
        assertThat(messages(a, b, "")).isEmpty();
        assertThat(unread(a)).isZero();
        assertThat(conversations(a).get(0).path("lastMessage").isNull()).isTrue();
        assertThat(conversations(a).get(0).path("unreadCount").asLong()).isZero();
        // b 쪽: 그대로
        assertThat(messages(b, a, "")).hasSize(2);
        assertThat(messageRepository.count()).isGreaterThanOrEqualTo(2);

        // 새 메시지는 a 에게 다시 보인다 (예전 것은 여전히 안 보인다)
        send(b, a, "새 메시지", 200);
        assertThat(messages(a, b, "")).hasSize(1);
        assertThat(messages(a, b, "").get(0).path("content").asText()).isEqualTo("새 메시지");
        assertThat(unread(a)).isEqualTo(1);

        // b 도 지우면 둘 다 지운 예전 메시지 두 개는 행이 없어지고, a 만 보는 새 메시지는 남는다
        long before = messageRepository.count();
        mockMvc.perform(auth(delete("/api/messages/" + a.userId()), b)).andExpect(status().isNoContent());
        assertThat(messageRepository.count()).isEqualTo(before - 2);
        assertThat(messages(b, a, "")).isEmpty();
        assertThat(messages(a, b, "")).hasSize(1);

        // 없는 사용자 404, 로그인 없이 401
        mockMvc.perform(auth(delete("/api/messages/" + java.util.UUID.randomUUID()), a)).andExpect(status().isNotFound());
        mockMvc.perform(delete("/api/messages/" + b.userId())).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("친구를 끊으면 대화는 남되 새로 보낼 수 없음(403)")
    void unfriend_keepsHistory() throws Exception {
        Session a = login(mockMvc, "끊는이");
        Session b = login(mockMvc, "끊긴이");
        makeFriends(mockMvc, a, b);
        send(a, b, "마지막 인사", 200);

        JsonNode friends = json(mockMvc.perform(auth(get("/api/friends"), a)).andExpect(status().isOk()).andReturn());
        mockMvc.perform(auth(delete("/api/friends/" + friends.get(0).path("id").asText()), a))
                .andExpect(status().is2xxSuccessful());

        send(a, b, "또 보내기", 403);
        send(b, a, "답장", 403);
        assertThat(texts(messages(b, a, ""), "content")).containsExactly("마지막 인사");
        JsonNode conv = conversations(b);
        assertThat(conv.size()).isEqualTo(1);
        assertThat(conv.get(0).path("friend").path("id").asText()).isEqualTo(a.userId().toString());
        assertThat(conv.get(0).path("unreadCount").asLong()).isEqualTo(1);
        assertThat(conv.get(0).path("canSend").asBoolean(true)).isFalse();
    }

    @Test
    @DisplayName("대화 지우기는 지운 본인에게만 messages 변경을 알린다(다른 탭·기기의 열린 대화 갱신)")
    void deleteConversation_publishesChangeToSelf() throws Exception {
        Session a = login(mockMvc, "지우기알림");
        Session b = login(mockMvc, "지우기알림상대");
        makeFriends(mockMvc, a, b);
        send(b, a, "받은 것", 200);
        events.clear();

        mockMvc.perform(auth(delete("/api/messages/" + b.userId()), a)).andExpect(status().isNoContent());

        assertThat(events.stream(ChangeEvent.class)
                .filter(e -> e.topics().contains("messages"))
                .toList())
                .singleElement()
                .satisfies(e -> assertThat(e.recipients()).containsExactly(a.userId()));
    }

    @Test
    @DisplayName("대화 목록의 canSend: 지금 친구면 true")
    void conversations_canSend() throws Exception {
        Session a = login(mockMvc, "친구표시");
        Session b = login(mockMvc, "친구표시상대");
        makeFriends(mockMvc, a, b);
        JsonNode conv = conversations(a);
        assertThat(conv.size()).isEqualTo(1);
        assertThat(conv.get(0).path("canSend").asBoolean(false)).isTrue();
    }

    @Test
    @DisplayName("회원 탈퇴 이벤트: 보내고 받은 메시지 삭제")
    void userDeleting_cleansUp() throws Exception {
        Session a = login(mockMvc, "떠나는이");
        Session b = login(mockMvc, "남는이");
        Session c = login(mockMvc, "다른이");
        makeFriends(mockMvc, a, b);
        makeFriends(mockMvc, b, c);
        send(a, b, "a→b", 200);
        send(b, a, "b→a", 200);
        send(b, c, "b→c", 200);

        new TransactionTemplate(transactionManager)
                .executeWithoutResult(s -> publisher.publishEvent(new UserDeletingEvent(a.userId())));

        assertThat(messages(b, a, "").size()).isZero();
        assertThat(texts(messages(b, c, ""), "content")).containsExactly("b→c");
        assertThat(unread(b)).isZero();
    }
}
