package com.moyeolog.moyelog_BE.dto;

/**
 * 링크를 공유했을 때 미리보기(Open Graph)에 쓸 최소 정보. 로그인 없이 보이므로 이름·소개·인원만 담는다.
 * 프론트 서버만 부른다 (GET /api/auth/link-preview, X-Internal-Secret).
 * 모임 초대: name = 모임 이름, description, memberCount, ownerNickname. 친구 초대: name = 닉네임, 나머지는 null.
 */
public record LinkPreviewResponse(String name, String description, Integer memberCount, String ownerNickname) {
}
