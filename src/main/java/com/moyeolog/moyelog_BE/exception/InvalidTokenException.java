package com.moyeolog.moyelog_BE.exception;

import org.springframework.http.HttpStatus;

public class InvalidTokenException extends MoyeologException {
    public InvalidTokenException(String message) {
        super(message, HttpStatus.UNAUTHORIZED);
    }
}
