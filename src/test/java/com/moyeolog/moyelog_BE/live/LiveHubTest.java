package com.moyeolog.moyelog_BE.live;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class LiveHubTest {

    @Test
    @DisplayName("상한을 넘으면 오래된 것부터 빠지고, 앱 종료(shutdown) 뒤에는 연결을 모두 닫고 새 연결도 받지 않는다")
    void limitAndShutdown() {
        LiveHub hub = new LiveHub(2, 60_000, 60_000);
        UUID user = UUID.randomUUID();
        hub.open(user, "a", Long.MAX_VALUE);
        hub.open(user, "b", Long.MAX_VALUE);
        hub.open(user, "c", Long.MAX_VALUE);
        assertThat(hub.connectionCount(user)).isEqualTo(2);

        hub.close(user, "b");
        assertThat(hub.connectionCount(user)).isEqualTo(1);

        hub.shutdown();
        assertThat(hub.connectionCount(user)).isZero();
        hub.open(user, "d", Long.MAX_VALUE);
        assertThat(hub.connectionCount(user)).isZero();
    }

    @Test
    @DisplayName("토픽 JSON")
    void json() {
        assertThat(LiveHub.toJson(List.of("schedules", "group:1"))).isEqualTo("{\"topics\":[\"schedules\",\"group:1\"]}");
    }
}
