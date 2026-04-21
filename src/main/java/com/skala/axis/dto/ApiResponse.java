package com.skala.axis.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Getter;
import java.time.Instant;

@Getter
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ApiResponse<T> {
    private final boolean success;
    private final T data;
    private final ErrorInfo error;
    private final String timestamp;

    private ApiResponse(boolean success, T data, ErrorInfo error) {
        this.success = success;
        this.data = data;
        this.error = error;
        this.timestamp = Instant.now().toString();
    }

    public static <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>(true, data, null);
    }

    public static <T> ApiResponse<T> error(String code, String message) {
        return new ApiResponse<>(false, null, new ErrorInfo(code, message));
    }

    @Getter
    public static class ErrorInfo {
        private final String code;
        private final String message;
        ErrorInfo(String code, String message) {
            this.code = code;
            this.message = message;
        }
    }
}
