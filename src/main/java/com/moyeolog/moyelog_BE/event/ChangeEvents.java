package com.moyeolog.moyelog_BE.event;

import org.springframework.context.ApplicationEventPublisher;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 받는 사람마다 볼 수 있는 토픽이 다른 변경을 모은다(예: 모임 메모 → 모임 멤버는 memos·group:{id}, 모임 밖 공유받은 사람은 memos 만).
 * 사람별로 토픽을 합친 뒤 같은 토픽 묶음끼리 ChangeEvent 하나로 만든다. 그래서 한 사람은 한 변경에 이벤트 하나를 받는다(계약).
 * 여러 번 add 해도(변경 전·후 등) 합쳐진다. add 는 받는 사람 목록을 바로 복사하므로 변경 전 상태를 먼저 담아 두면 된다.
 */
public final class ChangeEvents {

    private final Map<UUID, Set<String>> topicsByUser = new LinkedHashMap<>();

    public ChangeEvents add(Collection<UUID> recipients, String... topics) {
        for (UUID recipient : recipients) {
            if (recipient == null) continue;
            Set<String> set = topicsByUser.computeIfAbsent(recipient, id -> new LinkedHashSet<>());
            for (String topic : topics) {
                if (topic != null) set.add(topic);
            }
        }
        return this;
    }

    public List<ChangeEvent> build() {
        Map<Set<String>, Set<UUID>> usersByTopics = new LinkedHashMap<>();
        topicsByUser.forEach((user, topics) -> {
            if (!topics.isEmpty()) usersByTopics.computeIfAbsent(topics, t -> new LinkedHashSet<>()).add(user);
        });
        List<ChangeEvent> events = new ArrayList<>();
        usersByTopics.forEach((topics, users) -> events.add(new ChangeEvent(users, topics)));
        return events;
    }

    /** 트랜잭션 안에서 부르면 live 리스너가 커밋 뒤에 보낸다. */
    public void publish(ApplicationEventPublisher publisher) {
        build().forEach(publisher::publishEvent);
    }
}
