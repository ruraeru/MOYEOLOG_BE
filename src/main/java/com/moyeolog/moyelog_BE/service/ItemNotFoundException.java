package com.moyeolog.moyelog_BE.service;

import com.moyeolog.moyelog_BE.exception.MoyeologException;
import org.springframework.http.HttpStatus;

/**
 * 메모·일정·친구 쪽 404. 사용자에게 그대로 보여 줄 해요체 문장만 담는다 (입력값·ID 를 넣지 않는다).
 */
public class ItemNotFoundException extends MoyeologException {
    public ItemNotFoundException(String message) {
        super(message, HttpStatus.NOT_FOUND);
    }
}
