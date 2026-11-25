package com.moyeolog.moyelog_BE.event;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/**
 * 실시간 갱신(SSE)용 '무엇이 바뀌었는지' 알림. 데이터 본문은 싣지 않는다(권한 검사는 기존 조회 API 가 한다).
 * 도메인 서비스가 트랜잭션 안에서 발행하면 live 패키지의 리스너가 커밋 뒤(AFTER_COMMIT)에 받는 사람의 연결로 보낸다.
 * 받는 사람은 변경 전·후에 그 데이터를 볼 수 있는 사람 전부이고, 변경한 본인도 포함한다(다른 탭·기기 반영).
 *
 * @param recipients 받는 사람
 * @param topics     바뀐 것 (LiveTopics 의 이름)
 */
public record ChangeEvent(Set<UUID> recipients, Set<String> topics) {

    public static ChangeEvent of(Collection<UUID> recipients, String... topics) {
        Set<UUID> to = new LinkedHashSet<>(recipients);
        to.remove(null);
        Set<String> names = new LinkedHashSet<>();
        for (String topic : topics) {
            if (topic != null) names.add(topic);
        }
        return new ChangeEvent(to, names);
    }
}
