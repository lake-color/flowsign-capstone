package com.capstone.backend.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 오류 응답 공통 형태 {@code {error: {code, message, requestId}}} (API 명세 2.1, 5장).
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    public record ErrorBody(Error error) {
        public record Error(String code, String message, String requestId) {
        }
    }

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ErrorBody> handleApi(ApiException e) {
        return body(e.status(), e.code(), e.getMessage());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorBody> handleUnexpected(Exception e) {
        log.error("처리되지 않은 오류", e);
        return body(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "서버 오류가 발생했습니다.");
    }

    private ResponseEntity<ErrorBody> body(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status)
                .body(new ErrorBody(new ErrorBody.Error(code, message, ApiResponse.newRequestId())));
    }
}
