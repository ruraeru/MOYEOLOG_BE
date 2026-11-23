package com.moyeolog.moyelog_BE.message;

/** POST /api/messages/{friendId} 본문. 길이 검사는 서비스에서(trim 후 1~2000자, 코드 포인트). */
public record MessageSendRequest(String content) {
}
