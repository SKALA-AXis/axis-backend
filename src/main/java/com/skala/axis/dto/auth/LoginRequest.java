package com.skala.axis.dto.auth;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "로그인 요청")
public record LoginRequest(
        @Schema(description = "사용자 이메일", example = "axis.user@sk.com", requiredMode = Schema.RequiredMode.REQUIRED)
        String email,
        @Schema(description = "비밀번호", example = "password123", requiredMode = Schema.RequiredMode.REQUIRED)
        String password,
        @JsonProperty("remember_me")
        @JsonAlias("rememberMe")
        @Schema(description = "refresh token 쿠키를 장기 유지할지 여부", example = "false")
        Boolean rememberMe
) {
}
