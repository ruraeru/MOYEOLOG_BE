package com.moyeolog.moyelog_BE.notification;

import com.moyeolog.moyelog_BE.exception.MoyeologException;
import org.springframework.http.HttpStatus;

/** 404. 메시지는 사용자에게 그대로 보여 줄 해요체 문장(입력값·ID 를 넣지 않음). */
public class NotFoundException extends MoyeologException {
    public NotFoundException(String message) {
        super(message, HttpStatus.NOT_FOUND);
    }
}
