/*
 * 작성일: 2026-05-21
 * 작성자: 박진
 * 변경이력:
 *   2026-05-21 박진 — 로그인/회원가입 로직개선·로그인 기능 구현과 함께 추가, 이후 비밀번호 찾기 업데이트·수정 반영
 */
package com.skala.axis.service;

import com.skala.axis.config.AuthProperties;
import com.skala.axis.domain.AuthToken;
import com.skala.axis.domain.AuthTokenType;
import com.skala.axis.domain.User;
import com.skala.axis.dto.auth.LoginRequest;
import com.skala.axis.dto.auth.PasswordChangeRequest;
import com.skala.axis.dto.auth.PasswordResetConfirmRequest;
import com.skala.axis.dto.auth.PasswordResetRequest;
import com.skala.axis.dto.auth.SignupRequest;
import com.skala.axis.exception.AuthException;
import com.skala.axis.repository.AuthTokenRepository;
import com.skala.axis.repository.UserAccessLogRepository;
import com.skala.axis.repository.UserRepository;
import com.skala.axis.repository.UserSettingRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {
    @Mock
    private UserRepository userRepository;
    @Mock
    private UserSettingRepository userSettingRepository;
    @Mock
    private AuthTokenRepository authTokenRepository;
    @Mock
    private UserAccessLogRepository userAccessLogRepository;
    @Mock
    private PasswordEncoder passwordEncoder;
    @Mock
    private JwtService jwtService;
    @Mock
    private SesMailService sesMailService;

    @Test
    void loginRejectsMissingCredentialsWithoutLocalFallback() {
        AuthService authService = authService(authProperties());

        assertThatThrownBy(() -> authService.login(new LoginRequest("", "", false), null))
                .isInstanceOfSatisfying(AuthException.class, exception -> {
                    assertThat(exception.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED);
                    assertThat(exception.getCode()).isEqualTo("INVALID_CREDENTIALS");
                });

        verify(userRepository, never()).findByEmail(any());
        verify(userRepository, never()).findByEmailForUpdate(any());
    }

    @Test
    void signupSendsVerificationMailThroughExistingSesMailerWithConfiguredPublicBaseUrl() {
        AuthProperties authProperties = authProperties();
        authProperties.setAppBaseUrl("https://axis.skala.ai/");
        AuthService authService = authService(authProperties);
        stubSignupPersistence("owner@sk.com");
        when(userSettingRepository.findByUserId(nullable(UUID.class))).thenReturn(Optional.empty());

        authService.signup(signupRequest(), null);

        ArgumentCaptor<String> bodyCaptor = ArgumentCaptor.forClass(String.class);
        verify(sesMailService).sendTextMail(eq("owner@sk.com"), eq("[AXIS] 이메일 인증"), bodyCaptor.capture());
        assertThat(bodyCaptor.getValue())
                .contains("https://axis.skala.ai/auth/email-verifications/confirm?token=");
    }

    @Test
    void signupAllowsAnyEmailDomainWhenWildcardDomainIsConfigured() {
        AuthProperties authProperties = authProperties();
        authProperties.setAllowedEmailDomains("*");
        AuthService authService = authService(authProperties);
        stubSignupPersistence("developer@example.com");
        when(userSettingRepository.findByUserId(nullable(UUID.class))).thenReturn(Optional.empty());

        authService.signup(signupRequest("developer@example.com"), null);

        verify(sesMailService).sendTextMail(eq("developer@example.com"), eq("[AXIS] 이메일 인증"), any());
    }

    @Test
    void signupCanUseLogDeliveryWithoutSesCredentialsInLocalDevelopment() {
        AuthProperties authProperties = authProperties();
        authProperties.setAllowedEmailDomains("*");
        authProperties.setEmailVerificationDelivery("log");
        AuthService authService = authService(authProperties);
        stubSignupPersistence("developer@example.com");
        when(userSettingRepository.findByUserId(nullable(UUID.class))).thenReturn(Optional.empty());

        authService.signup(signupRequest("developer@example.com"), null);

        verify(sesMailService, never()).sendTextMail(any(), any(), any());
    }

    @Test
    void signupReturnsServiceUnavailableWhenVerificationMailDeliveryFails() {
        AuthProperties authProperties = authProperties();
        AuthService authService = authService(authProperties);
        stubSignupPersistence("owner@sk.com");
        doThrow(new IllegalStateException("ses unavailable"))
                .when(sesMailService)
                .sendTextMail(eq("owner@sk.com"), eq("[AXIS] 이메일 인증"), any());

        assertThatThrownBy(() -> authService.signup(signupRequest(), null))
                .isInstanceOfSatisfying(AuthException.class, exception -> {
                    assertThat(exception.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                    assertThat(exception.getCode()).isEqualTo("EMAIL_DELIVERY_FAILED");
                    assertThat(exception.getMessage()).isEqualTo("인증 메일 발송에 실패했습니다. 메일 발송 설정을 확인하세요.");
                });
    }

    @Test
    void passwordResetRequestDoesNotRevealMissingEmailAndDoesNotSendMail() {
        AuthService authService = authService(authProperties());
        when(userRepository.findByEmailForUpdate("missing@sk.com")).thenReturn(Optional.empty());

        var response = authService.requestPasswordReset(new PasswordResetRequest("missing@sk.com"), null);

        assertThat(response.accepted()).isTrue();
        assertThat(response.message()).contains("비밀번호 재설정 안내");
        verify(sesMailService, never()).sendTextMail(any(), any(), any());
    }

    @Test
    void passwordResetRequestSendsResetMailThroughExistingSesMailer() {
        AuthProperties authProperties = authProperties();
        authProperties.setAppBaseUrl("https://axis.skala.ai/");
        AuthService authService = authService(authProperties);
        User user = activeUser("owner@sk.com");
        when(userRepository.findByEmailForUpdate("owner@sk.com")).thenReturn(Optional.of(user));
        when(authTokenRepository.findByUserAndTypeAndRevokedAtIsNull(user, AuthTokenType.PASSWORD_RESET)).thenReturn(List.of());
        when(authTokenRepository.save(any(AuthToken.class))).thenAnswer(invocation -> invocation.getArgument(0));

        authService.requestPasswordReset(new PasswordResetRequest("owner@sk.com"), null);

        ArgumentCaptor<String> bodyCaptor = ArgumentCaptor.forClass(String.class);
        verify(sesMailService).sendTextMail(eq("owner@sk.com"), eq("[AXIS] 비밀번호 재설정"), bodyCaptor.capture());
        assertThat(bodyCaptor.getValue())
                .contains("https://axis.skala.ai/auth/password-reset/confirm?token=")
                .contains("30분");
    }

    @Test
    void passwordResetConfirmChangesPasswordAndRevokesRefreshTokens() {
        AuthService authService = authService(authProperties());
        User user = activeUser("owner@sk.com");
        AuthToken resetToken = AuthToken.passwordReset(user, "reset-hash", Instant.now().plusSeconds(600), null, null);
        AuthToken refreshToken = AuthToken.refresh(user, "refresh-hash", UUID.randomUUID(), null, Instant.now().plusSeconds(3600), null, null);
        when(authTokenRepository.findByTokenHash(any())).thenReturn(Optional.of(resetToken));
        when(passwordEncoder.encode("newpassword123")).thenReturn("encoded-new-password");
        when(authTokenRepository.findByUserAndTypeAndRevokedAtIsNull(user, AuthTokenType.PASSWORD_RESET)).thenReturn(List.of(resetToken));
        when(authTokenRepository.findByUserAndTypeAndRevokedAtIsNull(user, AuthTokenType.REFRESH)).thenReturn(List.of(refreshToken));

        var response = authService.confirmPasswordReset(new PasswordResetConfirmRequest("raw-reset-token", "newpassword123"), null);

        assertThat(response.passwordReset()).isTrue();
        assertThat(user.getPasswordHash()).isEqualTo("encoded-new-password");
        assertThat(resetToken.isUsed()).isTrue();
        assertThat(refreshToken.isRevoked()).isTrue();
    }

    @Test
    void changePasswordRejectsMissingRequestWithKoreanValidation() {
        AuthService authService = authService(authProperties());

        assertThatThrownBy(() -> authService.changePassword(UUID.randomUUID(), null, null))
                .isInstanceOfSatisfying(AuthException.class, exception -> {
                    assertThat(exception.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(exception.getCode()).isEqualTo("INVALID_PASSWORD_CHANGE_REQUEST");
                    assertThat(exception.getMessage()).isEqualTo("현재 비밀번호와 새 비밀번호를 입력하세요.");
                });

        verify(userRepository, never()).findById(any());
    }

    @Test
    void changePasswordRejectsWrongCurrentPassword() {
        AuthService authService = authService(authProperties());
        UUID userId = UUID.randomUUID();
        User user = activeUser("owner@sk.com");
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("wrongpassword", "encoded-password")).thenReturn(false);

        assertThatThrownBy(() -> authService.changePassword(userId, new PasswordChangeRequest("wrongpassword", "newpassword123"), null))
                .isInstanceOfSatisfying(AuthException.class, exception -> {
                    assertThat(exception.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED);
                    assertThat(exception.getCode()).isEqualTo("INVALID_CURRENT_PASSWORD");
                    assertThat(exception.getMessage()).isEqualTo("현재 비밀번호가 올바르지 않습니다.");
                });
    }

    @Test
    void changePasswordChangesPasswordAndRevokesRefreshTokens() {
        AuthService authService = authService(authProperties());
        UUID userId = UUID.randomUUID();
        User user = activeUser("owner@sk.com");
        AuthToken refreshToken = AuthToken.refresh(user, "refresh-hash", UUID.randomUUID(), null, Instant.now().plusSeconds(3600), null, null);
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("password123", "encoded-password")).thenReturn(true);
        when(passwordEncoder.encode("newpassword123")).thenReturn("encoded-new-password");
        when(authTokenRepository.findByUserAndTypeAndRevokedAtIsNull(user, AuthTokenType.REFRESH)).thenReturn(List.of(refreshToken));
        when(userSettingRepository.findByUserId(nullable(UUID.class))).thenReturn(Optional.empty());

        var response = authService.changePassword(userId, new PasswordChangeRequest("password123", "newpassword123"), null);

        assertThat(response.email()).isEqualTo("owner@sk.com");
        assertThat(user.getPasswordHash()).isEqualTo("encoded-new-password");
        assertThat(refreshToken.isRevoked()).isTrue();
    }

    private AuthService authService(AuthProperties authProperties) {
        return new AuthService(
                authProperties,
                userRepository,
                userSettingRepository,
                authTokenRepository,
                userAccessLogRepository,
                passwordEncoder,
                jwtService,
                sesMailService
        );
    }

    private AuthProperties authProperties() {
        AuthProperties authProperties = new AuthProperties();
        authProperties.setAllowedEmailDomains("sk.com");
        authProperties.setEmailVerificationMinutes(60);
        authProperties.setRefreshTokenDays(14);
        authProperties.setMaxFailedLoginCount(5);
        authProperties.setLockMinutes(10);
        authProperties.setAppBaseUrl("https://axis.local");
        return authProperties;
    }

    private void stubSignupPersistence(String email) {
        when(userRepository.existsByEmail(email)).thenReturn(false);
        when(passwordEncoder.encode("password123")).thenReturn("encoded-password");
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(authTokenRepository.save(any(AuthToken.class))).thenAnswer(invocation -> invocation.getArgument(0));
    }

    private SignupRequest signupRequest() {
        return signupRequest("owner@sk.com");
    }

    private SignupRequest signupRequest(String email) {
        return new SignupRequest(email, "password123", "Owner", "DX", "USER");
    }

    private User activeUser(String email) {
        User user = User.pending(email, "encoded-password");
        user.activateEmail();
        return user;
    }
}
