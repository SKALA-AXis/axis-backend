package com.skala.axis.exception;

import org.springframework.http.HttpStatus;

public class AiServerException extends RuntimeException {
    public static final String CALL_FAILED_MESSAGE = "호출에 실패했다";

    private final String code;
    private final HttpStatus status;

    public AiServerException(String message) {
        this("AI_CALL_FAILED", message, HttpStatus.SERVICE_UNAVAILABLE);
    }

    public AiServerException(String code, String message) {
        this(code, message, HttpStatus.SERVICE_UNAVAILABLE);
    }

    public AiServerException(String code, String message, HttpStatus status) {
        super(message);
        this.code = code;
        this.status = status;
    }

    public String getCode() {
        return code;
    }

    public HttpStatus getStatus() {
        return status;
    }
}
