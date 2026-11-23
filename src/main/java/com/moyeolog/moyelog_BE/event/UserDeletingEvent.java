package com.moyeolog.moyelog_BE.event;

import java.util.UUID;

/**
 * 회원 탈퇴 직전에(같은 트랜잭션 안에서, 동기) 발행된다.
 * 사용자 행을 지우기 전에 각 기능이 그 사용자의 데이터를 정리하도록 @EventListener 로 받는다.
 */
public record UserDeletingEvent(UUID userId) {
}
