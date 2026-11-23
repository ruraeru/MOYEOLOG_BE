package com.moyeolog.moyelog_BE.config;

import com.moyeolog.moyelog_BE.dto.ErrorResponse;
import com.moyeolog.moyelog_BE.exception.MoyeologException;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import com.moyeolog.moyelog_BE.security.SecurityConfig;
import jakarta.persistence.EntityNotFoundException;
import jakarta.persistence.OptimisticLockException;
import jakarta.persistence.PessimisticLockException;
import org.hibernate.StaleStateException;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.jpa.JpaObjectRetrievalFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.validation.ObjectError;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * 모든 예외를 고정된 형식(ErrorResponse)으로 변환합니다.
 * 클라이언트 오류는 4xx 로 매핑하고, 내부 예외 메시지(SQL, 클래스명 등)는 응답에 포함하지 않습니다.
 */
@ControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    public static final String CONFLICT_MESSAGE = "다른 곳에서 먼저 바뀌었어요. 새로고침 후 다시 시도해 주세요.";

    @ExceptionHandler(MoyeologException.class)
    public ResponseEntity<ErrorResponse> handleMoyeologException(MoyeologException e) {
        if (e.getStatus().is5xxServerError()) {
            log.error("Moyeolog exception: status={}, message={}", e.getStatus(), e.getMessage());
        } else {
            log.warn("Moyeolog exception: status={}, message={}", e.getStatus(), e.getMessage());
        }
        return build(e.getStatus(), e.getMessage());
    }

    // ─── 400 ────────────────────────────────────────────────────────

    /** @Valid 검증 실패 (MethodArgumentNotValidException 은 BindException 의 하위 타입) */
    @ExceptionHandler(BindException.class)
    public ResponseEntity<ErrorResponse> handleBindException(BindException e) {
        String message = "요청 값이 올바르지 않아요.";
        FieldError fieldError = e.getBindingResult().getFieldError();
        if (fieldError != null) {
            message = fieldMessage(fieldError);
        } else {
            ObjectError globalError = e.getBindingResult().getGlobalError();
            if (globalError != null && globalError.getDefaultMessage() != null) {
                message = globalError.getDefaultMessage();
            }
        }
        return build(HttpStatus.BAD_REQUEST, message);
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<ErrorResponse> handleHandlerMethodValidation(HandlerMethodValidationException e) {
        String message = e.getAllErrors().stream()
                .map(error -> error instanceof FieldError fe ? fieldMessage(fe) : error.getDefaultMessage())
                .filter(m -> m != null && !m.isBlank())
                .findFirst()
                .orElse("요청 값이 올바르지 않아요.");
        return build(HttpStatus.BAD_REQUEST, message);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ErrorResponse> handleConstraintViolation(ConstraintViolationException e) {
        String message = e.getConstraintViolations().stream()
                .map(ConstraintViolation::getMessage)
                .filter(m -> m != null && !m.isBlank())
                .findFirst()
                .orElse("요청 값이 올바르지 않아요.");
        return build(HttpStatus.BAD_REQUEST, message);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleNotReadable(HttpMessageNotReadableException e) {
        log.warn("Unreadable request body: {}", e.getClass().getSimpleName());
        return build(HttpStatus.BAD_REQUEST, "요청 본문 형식이 올바르지 않아요.");
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        return build(HttpStatus.BAD_REQUEST, "요청 값의 형식이 올바르지 않아요.");
    }

    /** 누락된 요청 파라미터 / 헤더 / 경로 변수 */
    @ExceptionHandler(ServletRequestBindingException.class)
    public ResponseEntity<ErrorResponse> handleMissingParameter(ServletRequestBindingException e) {
        return build(HttpStatus.BAD_REQUEST, "필요한 요청 값이 빠졌어요.");
    }

    @ExceptionHandler(MissingServletRequestPartException.class)
    public ResponseEntity<ErrorResponse> handleMissingPart(MissingServletRequestPartException e) {
        return build(HttpStatus.BAD_REQUEST, "필요한 요청 항목이 빠졌어요.");
    }

    @ExceptionHandler(MultipartException.class)
    public ResponseEntity<ErrorResponse> handleMultipart(MultipartException e) {
        log.warn("Malformed multipart request: {}", e.getClass().getSimpleName());
        return build(HttpStatus.BAD_REQUEST, "요청 형식이 올바르지 않아요.");
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleIllegalArgumentException(IllegalArgumentException e) {
        log.warn("Invalid argument: ", e);
        return build(HttpStatus.BAD_REQUEST, "요청 값이 올바르지 않아요.");
    }

    // ─── 403 / 404 / 405 / 406 / 409 / 413 / 415 ────────────────────

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ErrorResponse> handleAccessDeniedException(AccessDeniedException e) {
        log.warn("Access denied: {}", e.getMessage());
        return build(HttpStatus.FORBIDDEN, SecurityConfig.FORBIDDEN_MESSAGE);
    }

    @ExceptionHandler({NoResourceFoundException.class, NoHandlerFoundException.class})
    public ResponseEntity<ErrorResponse> handleNotFound(Exception e) {
        return build(HttpStatus.NOT_FOUND, "요청한 항목을 찾을 수 없어요.");
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ErrorResponse> handleMethodNotSupported(HttpRequestMethodNotSupportedException e) {
        return build(HttpStatus.METHOD_NOT_ALLOWED, "지원하지 않는 요청 방식이에요.");
    }

    @ExceptionHandler(HttpMediaTypeNotAcceptableException.class)
    public ResponseEntity<ErrorResponse> handleNotAcceptable(HttpMediaTypeNotAcceptableException e) {
        return build(HttpStatus.NOT_ACCEPTABLE, "지원하지 않는 응답 형식이에요.");
    }

    /**
     * 안전망: 동시 요청 경합(낙관적/비관적 잠금 실패, 이미 지워진 행 갱신, 유니크 제약 위반)은 500 이 아니라 409.
     * 각 서비스가 상황에 맞는 문장으로 먼저 처리하는 것이 원칙이고, 여기는 놓친 경우를 받는다.
     */
    @ExceptionHandler({
            ConcurrencyFailureException.class,          // ObjectOptimisticLockingFailureException, CannotAcquireLockException 등
            OptimisticLockException.class,
            PessimisticLockException.class,
            StaleStateException.class,
            DataIntegrityViolationException.class
    })
    public ResponseEntity<ErrorResponse> handleConflict(Exception e) {
        log.warn("Concurrent modification or constraint violation: {}", e.getClass().getSimpleName());
        return build(HttpStatus.CONFLICT, CONFLICT_MESSAGE);
    }

    /** 안전망: 처리 도중 다른 요청이 지운 엔티티를 읽으려 한 경우 */
    @ExceptionHandler({EntityNotFoundException.class, JpaObjectRetrievalFailureException.class})
    public ResponseEntity<ErrorResponse> handleEntityGone(Exception e) {
        log.warn("Entity disappeared during request: {}", e.getClass().getSimpleName());
        return build(HttpStatus.NOT_FOUND, "요청한 항목을 찾을 수 없어요. 이미 삭제됐을 수 있어요.");
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ErrorResponse> handleMaxUploadSize(MaxUploadSizeExceededException e) {
        return build(HttpStatus.PAYLOAD_TOO_LARGE, "파일이 너무 커요. 10MB 이하로 올려 주세요.");
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ErrorResponse> handleMediaTypeNotSupported(HttpMediaTypeNotSupportedException e) {
        return build(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "지원하지 않는 요청 형식이에요.");
    }

    // ─── 500 ────────────────────────────────────────────────────────

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleException(Exception e) {
        // Spring MVC 가 상태 코드를 지정한 예외(ResponseStatusException 등)는 그 코드를 유지한다.
        if (e instanceof org.springframework.web.ErrorResponse errorResponse) {
            HttpStatusCode statusCode = errorResponse.getStatusCode();
            HttpStatus status = HttpStatus.resolve(statusCode.value());
            if (status != null && status.is4xxClientError()) {
                log.warn("Client error: status={}, type={}", status, e.getClass().getSimpleName());
                return build(status, "요청을 처리할 수 없어요.");
            }
        }
        log.error("Unexpected error occurred: ", e);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "서버에서 문제가 생겼어요. 잠시 후 다시 시도해 주세요.");
    }

    // ─── helpers ────────────────────────────────────────────────────

    private String fieldMessage(FieldError fieldError) {
        // 타입 변환 실패(예: 잘못된 UUID)는 기본 메시지에 클래스명이 들어가므로 고정 문구로 대체한다.
        if (fieldError.isBindingFailure() || fieldError.getDefaultMessage() == null) {
            return "요청 값의 형식이 올바르지 않아요.";
        }
        return fieldError.getDefaultMessage();
    }

    private ResponseEntity<ErrorResponse> build(HttpStatus status, String message) {
        return new ResponseEntity<>(ErrorResponse.of(status.getReasonPhrase(), message, status.value()), status);
    }
}
