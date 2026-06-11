package com.moyeolog.moyelog_BE.config;

import com.moyeolog.moyelog_BE.dto.ErrorResponse;
import com.moyeolog.moyelog_BE.exception.MoyeologException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;

@ControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    @ExceptionHandler(MoyeologException.class)
    public ResponseEntity<ErrorResponse> handleMoyeologException(MoyeologException e) {
        log.error("Moyeolog exception: status={}, message={}", e.getStatus(), e.getMessage());
        return new ResponseEntity<>(
                ErrorResponse.of(e.getStatus().getReasonPhrase(), e.getMessage(), e.getStatus().value()),
                e.getStatus()
        );
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ErrorResponse> handleAccessDeniedException(AccessDeniedException e) {
        log.error("Access denied: ", e);
        return new ResponseEntity<>(
                ErrorResponse.of("Forbidden", "접근 권한이 없습니다: " + e.getMessage(), HttpStatus.FORBIDDEN.value()),
                HttpStatus.FORBIDDEN
        );
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleIllegalArgumentException(IllegalArgumentException e) {
        log.error("Invalid argument: ", e);
        return new ResponseEntity<>(
                ErrorResponse.of("Bad Request", "유효하지 않은 요청 데이터입니다: " + e.getMessage(), HttpStatus.BAD_REQUEST.value()),
                HttpStatus.BAD_REQUEST
        );
    }

    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<ErrorResponse> handleRuntimeException(RuntimeException e) {
        log.error("Runtime exception occurred: ", e);
        return new ResponseEntity<>(
                ErrorResponse.of("Internal Server Error", e.getMessage(), HttpStatus.INTERNAL_SERVER_ERROR.value()),
                HttpStatus.INTERNAL_SERVER_ERROR
        );
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleException(Exception e) {
        log.error("Unexpected error occurred: ", e);
        return new ResponseEntity<>(
                ErrorResponse.of("Unexpected Error", e.getMessage(), HttpStatus.INTERNAL_SERVER_ERROR.value()),
                HttpStatus.INTERNAL_SERVER_ERROR
        );
    }
}
