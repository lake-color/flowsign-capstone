package com.capstone.backend.common;

import org.springframework.http.HttpStatus;

/**
 * 명세 5장의 오류 코드로 응답할 예외.
 */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    public ApiException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public static ApiException badRequest(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", message);
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }
}
