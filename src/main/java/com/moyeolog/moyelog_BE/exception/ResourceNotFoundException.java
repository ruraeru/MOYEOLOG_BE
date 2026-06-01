package com.moyeolog.moyelog_BE.exception;

import org.springframework.http.HttpStatus;

public class ResourceNotFoundException extends MoyeologException {
    public ResourceNotFoundException(String resourceName, Object identifier) {
        super(String.format("%s (ID: %s)을(를) 찾을 수 없습니다.", resourceName, identifier), HttpStatus.NOT_FOUND);
    }
}
