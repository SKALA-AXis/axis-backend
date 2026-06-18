/*
 * 작성일: 2026-04-21
 * 작성자: 최종민
 * 변경이력:
 *   2026-04-21 최종민 — axis-backend 베이스라인에 AI 서버 예외 추가
 *   2026-06-11 박진 — 챗봇 AI 연동 강화 작업에 맞춰 예외 보강
 */
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
