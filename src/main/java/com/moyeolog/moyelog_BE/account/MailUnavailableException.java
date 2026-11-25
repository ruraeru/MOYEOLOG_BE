package com.moyeolog.moyelog_BE.account;

import com.moyeolog.moyelog_BE.exception.MoyeologException;
import org.springframework.http.HttpStatus;

/** 메일 서버 설정(SPRING_MAIL_HOST)이 없어 메일이 필요한 요청을 처리할 수 없을 때 503. */
public class MailUnavailableException extends MoyeologException {
    public MailUnavailableException() {
        super("지금은 이메일을 보낼 수 없어요. 잠시 후 다시 시도해 주세요.", HttpStatus.SERVICE_UNAVAILABLE);
    }
}
