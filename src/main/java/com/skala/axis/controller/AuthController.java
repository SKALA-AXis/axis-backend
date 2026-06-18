/*
 * 작성일: 2026-05-06
 * 작성자: 박진
 * 변경이력:
 *   2026-05-06 박진 — 인증 컨트롤러 초안 작성, 이후 로그인/회원가입·비밀번호 찾기 추가
 */
package com.skala.axis.controller;

import com.skala.axis.config.AuthProperties;
import com.skala.axis.config.AuthPrincipal;
import com.skala.axis.config.AuthSecurity;
import com.skala.axis.dto.ApiResponse;
import com.skala.axis.dto.auth.EmailVerificationRequest;
import com.skala.axis.dto.auth.LoginRequest;
import com.skala.axis.dto.auth.PasswordResetConfirmRequest;
import com.skala.axis.dto.auth.PasswordResetConfirmResponse;
import com.skala.axis.dto.auth.PasswordResetRequest;
import com.skala.axis.dto.auth.PasswordResetRequestResponse;
import com.skala.axis.dto.auth.RefreshRequest;
import com.skala.axis.dto.auth.SignupRequest;
import com.skala.axis.service.AuthService;
import com.skala.axis.service.RequestMetadata;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {
    private final AuthProperties authProperties;
    private final AuthService authService;

    @PostMapping("/signup")
    public ResponseEntity<ApiResponse<Object>> signup(
            @RequestBody(required = false) Map<String, Object> request,
            HttpServletRequest servletRequest
    ) {
        if (!authProperties.isEnforce()) {
            return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(devAuthDisabled("signup_disabled")));
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(
                authService.signup(toSignupRequest(request), RequestMetadata.from(servletRequest))
        ));
    }

    @PostMapping("/verify-email")
    public ResponseEntity<ApiResponse<Object>> verifyEmail(
            @RequestBody(required = false) Map<String, Object> request,
            HttpServletRequest servletRequest
    ) {
        if (!authProperties.isEnforce()) {
            return ResponseEntity.ok(ApiResponse.success(devAuthDisabled("email_verification_disabled")));
        }
        return ResponseEntity.ok(ApiResponse.success(authService.confirmEmail(stringValue(request, "token"), RequestMetadata.from(servletRequest))));
    }

    @GetMapping("/email-verifications/confirm")
    public ResponseEntity<ApiResponse<Object>> confirmEmail(
            @RequestParam String token,
            HttpServletRequest servletRequest
    ) {
        if (!authProperties.isEnforce()) {
            return ResponseEntity.ok(ApiResponse.success(devAuthDisabled("email_verification_disabled")));
        }
        return ResponseEntity.ok(ApiResponse.success(authService.confirmEmail(token, RequestMetadata.from(servletRequest))));
    }

    @PostMapping("/email-verifications/resend")
    public ResponseEntity<ApiResponse<Object>> resendEmailVerification(
            @RequestBody(required = false) EmailVerificationRequest request,
            HttpServletRequest servletRequest
    ) {
        if (!authProperties.isEnforce()) {
            return ResponseEntity.ok(ApiResponse.success(devAuthDisabled("email_verification_disabled")));
        }
        return ResponseEntity.ok(ApiResponse.success(authService.resendEmailVerification(request == null ? "" : request.email(), RequestMetadata.from(servletRequest))));
    }

    @PostMapping("/password-reset/request")
    public ResponseEntity<ApiResponse<PasswordResetRequestResponse>> requestPasswordReset(
            @RequestBody(required = false) PasswordResetRequest request,
            HttpServletRequest servletRequest
    ) {
        if (!authProperties.isEnforce()) {
            return ResponseEntity.ok(ApiResponse.success(new PasswordResetRequestResponse(
                    true,
                    "입력한 이메일로 비밀번호 재설정 안내를 보냈습니다. 메일이 도착하지 않았다면 입력한 주소를 확인하세요.",
                    authProperties.getPasswordResetMinutes()
            )));
        }
        return ResponseEntity.ok(ApiResponse.success(authService.requestPasswordReset(request, RequestMetadata.from(servletRequest))));
    }

    @PostMapping("/password-reset/confirm")
    public ResponseEntity<ApiResponse<PasswordResetConfirmResponse>> confirmPasswordReset(
            @RequestBody(required = false) PasswordResetConfirmRequest request,
            HttpServletRequest servletRequest
    ) {
        if (!authProperties.isEnforce()) {
            return ResponseEntity.ok(ApiResponse.success(new PasswordResetConfirmResponse(true)));
        }
        return ResponseEntity.ok(ApiResponse.success(authService.confirmPasswordReset(request, RequestMetadata.from(servletRequest))));
    }

    @PostMapping("/login")
    public ResponseEntity<ApiResponse<Object>> login(
            @RequestBody(required = false) LoginRequest request,
            HttpServletRequest servletRequest,
            HttpServletResponse servletResponse
    ) {
        LoginRequest loginRequest = normalizeLoginRequest(request);
        if (!authProperties.isEnforce()) {
            return ResponseEntity.ok(ApiResponse.success(devLoginResponse(loginRequest)));
        }
        AuthService.AuthLoginResult result = authService.login(loginRequest, RequestMetadata.from(servletRequest));
        addRefreshCookie(servletResponse, result.refreshToken(), Boolean.TRUE.equals(loginRequest.rememberMe()));
        return ResponseEntity.ok(ApiResponse.success(result.response()));
    }

    @PostMapping("/logout")
    public ResponseEntity<ApiResponse<Map<String, Object>>> logout(
            @RequestBody(required = false) RefreshRequest request,
            Authentication authentication,
            HttpServletRequest servletRequest,
            HttpServletResponse servletResponse
    ) {
        if (!authProperties.isEnforce()) {
            return ResponseEntity.ok(ApiResponse.success(Map.of("status", "logged_out")));
        }
        UUID userId = authentication != null && authentication.getPrincipal() instanceof AuthPrincipal principal
                ? principal.userId()
                : null;
        authService.logout(resolveRefreshToken(request, servletRequest), userId, RequestMetadata.from(servletRequest));
        clearRefreshCookie(servletResponse);
        return ResponseEntity.ok(ApiResponse.success(Map.of("status", "logged_out")));
    }

    @PostMapping("/refresh")
    public ResponseEntity<ApiResponse<Object>> refreshToken(
            @RequestBody(required = false) RefreshRequest request,
            HttpServletRequest servletRequest,
            HttpServletResponse servletResponse
    ) {
        if (!authProperties.isEnforce()) {
            return ResponseEntity.ok(ApiResponse.success(devAuthDisabled("refresh_disabled")));
        }
        AuthService.AuthLoginResult result = authService.refresh(resolveRefreshToken(request, servletRequest), RequestMetadata.from(servletRequest));
        addRefreshCookie(servletResponse, result.refreshToken(), true);
        return ResponseEntity.ok(ApiResponse.success(result.response()));
    }

    @GetMapping("/me")
    public ResponseEntity<ApiResponse<Object>> getMe(Authentication authentication) {
        if (!authProperties.isEnforce()) {
            return ResponseEntity.ok(ApiResponse.success(devAuthDisabled("auth_disabled")));
        }
        return ResponseEntity.ok(ApiResponse.success(authService.me(AuthSecurity.requireUserId(authentication))));
    }

    private void addRefreshCookie(HttpServletResponse response, String refreshToken, boolean persistent) {
        ResponseCookie.ResponseCookieBuilder cookie = ResponseCookie.from(authProperties.getRefreshCookieName(), refreshToken)
                .httpOnly(true)
                .secure(authProperties.isRefreshCookieSecure())
                .sameSite(authProperties.getRefreshCookieSameSite())
                .path("/api/auth");
        if (persistent) {
            cookie.maxAge(Duration.ofDays(authProperties.getRefreshTokenDays()));
        }
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.build().toString());
    }

    private void clearRefreshCookie(HttpServletResponse response) {
        ResponseCookie cookie = ResponseCookie.from(authProperties.getRefreshCookieName(), "")
                .httpOnly(true)
                .secure(authProperties.isRefreshCookieSecure())
                .sameSite(authProperties.getRefreshCookieSameSite())
                .path("/api/auth")
                .maxAge(Duration.ZERO)
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }

    private String resolveRefreshToken(RefreshRequest request, HttpServletRequest servletRequest) {
        if (request != null && request.refreshToken() != null && !request.refreshToken().isBlank()) {
            return request.refreshToken();
        }
        Cookie[] cookies = servletRequest.getCookies();
        if (cookies == null) {
            return null;
        }
        return Arrays.stream(cookies)
                .filter(cookie -> authProperties.getRefreshCookieName().equals(cookie.getName()))
                .map(Cookie::getValue)
                .findFirst()
                .orElse(null);
    }

    private SignupRequest toSignupRequest(Map<String, Object> request) {
        return new SignupRequest(
                stringValue(request, "email"),
                stringValue(request, "password"),
                stringValue(request, "name"),
                stringValue(request, "department"),
                stringValue(request, "role")
        );
    }

    private LoginRequest normalizeLoginRequest(LoginRequest request) {
        if (request == null) {
            return new LoginRequest(null, null, Boolean.FALSE);
        }
        return new LoginRequest(
                request.email(),
                request.password(),
                Boolean.TRUE.equals(request.rememberMe())
        );
    }

    private String stringValue(Map<String, Object> request, String key) {
        Object value = request == null ? null : request.get(key);
        return value == null ? null : String.valueOf(value);
    }

    private Map<String, Object> devLoginResponse(LoginRequest request) {
        String email = request.email() == null || request.email().isBlank()
                ? "local-user"
                : request.email();
        Map<String, Object> user = new LinkedHashMap<>();
        user.put("id", "local-auth-disabled");
        user.put("email", email);
        user.put("name", email);
        user.put("role", "local");

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("access_token", "auth-disabled");
        response.put("refresh_token", "auth-disabled");
        response.put("token_type", "Bearer");
        response.put("expires_in", 0);
        response.put("user", user);
        response.put("status", "auth_disabled");
        return response;
    }

    private Map<String, Object> devAuthDisabled(String resultKind) {
        return Map.of(
                "status", "disabled",
                "result_kind", resultKind
        );
    }

}
