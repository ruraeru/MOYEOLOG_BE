package com.moyeolog.moyelog_BE.exception;

import org.springframework.http.HttpStatus;

public class ConflictException extends MoyeologException {
    public ConflictException(String message) {
        super(message, HttpStatus.CONFLICT);
    }
}
