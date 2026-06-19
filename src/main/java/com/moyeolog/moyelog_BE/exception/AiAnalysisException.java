package com.moyeolog.moyelog_BE.exception;

import org.springframework.http.HttpStatus;

public class AiAnalysisException extends MoyeologException {
    public AiAnalysisException(String message) {
        super(message, HttpStatus.BAD_GATEWAY);
    }
}
