package com.skala.axis.exception;

import com.skala.axis.dto.ApiResponse;
import jakarta.persistence.EntityNotFoundException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {
    @ExceptionHandler(AuthException.class)
    public ResponseEntity<ApiResponse<?>> handleAuth(AuthException e) {
        log.warn("인증/인가 오류: {} {}", e.getCode(), e.getMessage());
        return ResponseEntity.status(e.getStatus()).body(ApiResponse.error(e.getCode(), e.getMessage()));
    }

    @ExceptionHandler(AiServerException.class)
    public ResponseEntity<ApiResponse<?>> handleAiServer(AiServerException e) {
        log.warn("AI 서버 오류: {} {}", e.getCode(), e.getMessage());
        return ResponseEntity.status(e.getStatus())
                .body(ApiResponse.error(e.getCode(), AiServerException.CALL_FAILED_MESSAGE));
    }

    @ExceptionHandler(EntityNotFoundException.class)
    public ResponseEntity<ApiResponse<?>> handleNotFound(EntityNotFoundException e) {
        return ResponseEntity.status(404).body(ApiResponse.error("COMMON_NOT_FOUND", e.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiResponse<?>> handleBadRequest(IllegalArgumentException e) {
        log.warn("잘못된 요청: {}", e.getMessage());
        return ResponseEntity.status(400).body(ApiResponse.error("COMMON_BAD_REQUEST", e.getMessage()));
    }

    @ExceptionHandler(CannotAcquireLockException.class)
    public ResponseEntity<ApiResponse<?>> handleLockFailure(CannotAcquireLockException e) {
        log.warn("동시 요청 처리 중 DB lock 획득 실패", e);
        return ResponseEntity.status(409).body(ApiResponse.error("COMMON_CONCURRENT_REQUEST", "동시에 처리 중인 요청이 있습니다. 잠시 후 다시 시도하세요."));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<?>> handleGeneral(Exception e) {
        log.error("예상치 못한 오류", e);
        return ResponseEntity.status(500).body(ApiResponse.error("COMMON_INTERNAL_SERVER_ERROR", "서버 내부 오류"));
    }
}
