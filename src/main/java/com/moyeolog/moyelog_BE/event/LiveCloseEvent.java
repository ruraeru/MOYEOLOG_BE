package com.moyeolog.moyelog_BE.event;

import java.util.UUID;

/**
 * 로그아웃·모든 기기 로그아웃·회원 탈퇴 때 발행한다. live 패키지의 리스너가 커밋 뒤에 그 사용자의 실시간 연결을 닫는다.
 *
 * @param userId 사용자
 * @param jti    이 액세스 토큰으로 연 연결만 닫는다(로그아웃한 기기). null 이면 그 사용자의 연결 전부.
 */
public record LiveCloseEvent(UUID userId, String jti) {
}
