package com.skala.axis.service;

import com.skala.axis.config.AuthProperties;
import com.skala.axis.domain.AuthToken;
import com.skala.axis.domain.User;
import com.skala.axis.dto.auth.SignupRequest;
import com.skala.axis.repository.AuthTokenRepository;
import com.skala.axis.repository.UserAccessLogRepository;
import com.skala.axis.repository.UserRepository;
import com.skala.axis.repository.UserSettingRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
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
}
