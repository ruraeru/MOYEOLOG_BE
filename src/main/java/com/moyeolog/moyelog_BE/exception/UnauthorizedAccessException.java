package com.moyeolog.moyelog_BE.exception;

import org.springframework.http.HttpStatus;

public class UnauthorizedAccessException extends MoyeologException {
    public UnauthorizedAccessException(String message) {
        super(message, HttpStatus.FORBIDDEN);
    }
}
