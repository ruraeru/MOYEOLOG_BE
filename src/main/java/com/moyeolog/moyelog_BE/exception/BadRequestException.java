package com.moyeolog.moyelog_BE.exception;

import org.springframework.http.HttpStatus;

public class BadRequestException extends MoyeologException {
    public BadRequestException(String message) {
        super(message, HttpStatus.BAD_REQUEST);
    }
}
