package com.moyeolog.moyelog_BE.exception;

import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
public abstract class MoyeologException extends RuntimeException {
    private final HttpStatus status;

    protected MoyeologException(String message, HttpStatus status) {
        super(message);
        this.status = status;
    }
}
