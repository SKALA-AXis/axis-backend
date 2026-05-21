package com.skala.axis.service;

import com.skala.axis.config.AuthProperties;
import com.skala.axis.domain.AuthToken;
import com.skala.axis.domain.AuthTokenType;
import com.skala.axis.domain.User;
import com.skala.axis.domain.UserAccessLog;
import com.skala.axis.domain.UserRole;
import com.skala.axis.domain.UserSetting;
import com.skala.axis.domain.UserStatus;
import com.skala.axis.dto.auth.AuthResponse;
import com.skala.axis.dto.auth.EmailVerificationResponse;
import com.skala.axis.dto.auth.LoginRequest;
import com.skala.axis.dto.auth.PasswordChangeRequest;
import com.skala.axis.dto.auth.SignupRequest;
import com.skala.axis.dto.auth.SignupResponse;
import com.skala.axis.dto.auth.UserProfileResponse;
import com.skala.axis.exception.AuthException;
import com.skala.axis.repository.AuthTokenRepository;
import com.skala.axis.repository.UserAccessLogRepository;
import com.skala.axis.repository.UserRepository;
import com.skala.axis.repository.UserSettingRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {
    private static final Pattern EMAIL_PATTERN = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final AuthProperties authProperties;
    private final UserRepository userRepository;
    private final UserSettingRepository userSettingRepository;
    private final AuthTokenRepository authTokenRepository;
    private final UserAccessLogRepository userAccessLogRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final SesMailService sesMailService;

    @Transactional
    public SignupResponse signup(SignupRequest request, RequestMetadata metadata) {
        String email = normalizeEmail(request.email());
        validateEmail(email);
        validatePassword(request.password());
        if (userRepository.existsByEmail(email)) {
            throw new AuthException(HttpStatus.CONFLICT, "EMAIL_ALREADY_EXISTS", "이미 가입된 이메일입니다.");
        }

        User user = userRepository.save(User.pending(email, passwordEncoder.encode(request.password())));
        userSettingRepository.save(UserSetting.defaults(user, blankToNull(request.name()), blankToNull(request.department())));

        TokenIssue tokenIssue = issueEmailVerificationToken(user, metadata);
        recordAccessLog(user, "SIGNUP", true, metadata, null, Map.of("email_domain", user.getEmailDomain()));
        sendVerificationMail(user, tokenIssue.rawToken());
        return new SignupResponse(toProfile(user), true, tokenIssue.expiresAt());
    }

    @Transactional
    public EmailVerificationResponse confirmEmail(String rawToken, RequestMetadata metadata) {
        AuthToken token = findToken(rawToken, "INVALID_OR_EXPIRED_EMAIL_TOKEN", "유효하지 않거나 만료된 인증 링크입니다.");
        Instant now = Instant.now();
        if (token.getType() != AuthTokenType.EMAIL_VERIFICATION || token.isExpired(now) || token.isUsed() || token.isRevoked()) {
            throw new AuthException(HttpStatus.BAD_REQUEST, "INVALID_OR_EXPIRED_EMAIL_TOKEN", "유효하지 않거나 만료된 인증 링크입니다.");
        }

        User user = token.getUser();
        if (user.getStatus() == UserStatus.WITHDRAWN || user.getStatus() == UserStatus.SUSPENDED) {
            throw new AuthException(HttpStatus.FORBIDDEN, "ACCOUNT_SUSPENDED", "사용할 수 없는 계정입니다.");
        }
        if (!user.isEmailVerified()) {
            user.activateEmail();
        }
        token.markUsed();
        revokeUnusedEmailTokens(user);
        recordAccessLog(user, "EMAIL_VERIFIED", true, metadata, null, null);
        return new EmailVerificationResponse(true);
    }

    @Transactional
    public EmailVerificationResponse resendEmailVerification(String email, RequestMetadata metadata) {
        User user = userRepository.findByEmail(normalizeEmail(email))
                .orElseThrow(() -> new AuthException(HttpStatus.NOT_FOUND, "INVALID_CREDENTIALS", "이메일 또는 비밀번호가 올바르지 않습니다."));
        if (user.isEmailVerified()) {
            return new EmailVerificationResponse(true);
        }
        if (user.getStatus() != UserStatus.PENDING) {
            throw new AuthException(HttpStatus.FORBIDDEN, "ACCOUNT_SUSPENDED", "사용할 수 없는 계정입니다.");
        }
        revokeUnusedEmailTokens(user);
        TokenIssue tokenIssue = issueEmailVerificationToken(user, metadata);
        recordAccessLog(user, "EMAIL_VERIFICATION_RESENT", true, metadata, null, null);
        sendVerificationMail(user, tokenIssue.rawToken());
        return new EmailVerificationResponse(false);
    }

    @Transactional
    public AuthLoginResult login(LoginRequest request, RequestMetadata metadata) {
        String email = normalizeEmail(request.email());
        if (email.isBlank() || nullToEmpty(request.password()).isBlank()) {
            recordAccessLog(null, "LOGIN_FAILURE", false, metadata, null, Map.of("reason", "missing_credentials", "email", email));
            throw invalidCredentials();
        }
        User user = userRepository.findByEmailForUpdate(email).orElse(null);
        if (user == null) {
            recordAccessLog(null, "LOGIN_FAILURE", false, metadata, null, Map.of("reason", "not_found", "email", email));
            throw invalidCredentials();
        }

        Instant now = Instant.now();
        if (user.isLocked(now)) {
            recordAccessLog(user, "LOGIN_FAILURE", false, metadata, null, Map.of("reason", "locked"));
            throw new AuthException(HttpStatus.LOCKED, "ACCOUNT_LOCKED", "로그인 실패가 반복되어 계정이 일시 잠금되었습니다.");
        }

        if (!passwordEncoder.matches(nullToEmpty(request.password()), user.getPasswordHash())) {
            user.recordLoginFailure(authProperties.getMaxFailedLoginCount(), authProperties.getLockMinutes());
            recordAccessLog(user, "LOGIN_FAILURE", false, metadata, null, Map.of("reason", "password_mismatch"));
            throw invalidCredentials();
        }

        ensureLoginAllowed(user);
        user.recordLoginSuccess();

        JwtService.AccessToken accessToken = jwtService.generateAccessToken(user);
        TokenIssue refreshIssue = issueRefreshToken(user, null, null, metadata);
        recordAccessLog(user, "LOGIN_SUCCESS", true, metadata, refreshIssue.familyId(), null);

        return new AuthLoginResult(
                new AuthResponse(accessToken.token(), accessToken.expiresAt(), accessToken.expiresInSeconds(), toProfile(user)),
                refreshIssue.rawToken()
        );
    }

    @Transactional
    public AuthLoginResult refresh(String rawRefreshToken, RequestMetadata metadata) {
        if (rawRefreshToken == null || rawRefreshToken.isBlank()) {
            throw new AuthException(HttpStatus.UNAUTHORIZED, "REFRESH_TOKEN_EXPIRED", "refresh token이 필요합니다.");
        }

        AuthToken current = findToken(rawRefreshToken, "REFRESH_TOKEN_EXPIRED", "유효하지 않은 refresh token입니다.");
        if (current.getType() != AuthTokenType.REFRESH) {
            throw new AuthException(HttpStatus.UNAUTHORIZED, "REFRESH_TOKEN_EXPIRED", "유효하지 않은 refresh token입니다.");
        }

        if (current.isRevoked()) {
            UUID familyId = current.getTokenFamilyId();
            if (familyId != null) {
                authTokenRepository.revokeFamily(familyId, Instant.now());
            }
            recordAccessLog(current.getUser(), "REFRESH_REUSE_DETECTED", false, metadata, familyId, null);
            throw new AuthException(HttpStatus.UNAUTHORIZED, "REFRESH_TOKEN_REUSE_DETECTED", "폐기된 refresh token이 다시 사용되었습니다.");
        }

        if (current.isExpired(Instant.now())) {
            current.revoke();
            throw new AuthException(HttpStatus.UNAUTHORIZED, "REFRESH_TOKEN_EXPIRED", "refresh token이 만료되었습니다.");
        }

        User user = current.getUser();
        ensureLoginAllowed(user);
        current.revoke();

        JwtService.AccessToken accessToken = jwtService.generateAccessToken(user);
        TokenIssue nextRefresh = issueRefreshToken(user, current.getTokenFamilyId(), current.getId(), metadata);
        recordAccessLog(user, "REFRESH_ROTATED", true, metadata, nextRefresh.familyId(), null);

        return new AuthLoginResult(
                new AuthResponse(accessToken.token(), accessToken.expiresAt(), accessToken.expiresInSeconds(), toProfile(user)),
                nextRefresh.rawToken()
        );
    }

    @Transactional
    public void logout(String rawRefreshToken, UUID userId, RequestMetadata metadata) {
        User user = userId == null ? null : userRepository.findById(userId).orElse(null);
        if (rawRefreshToken != null && !rawRefreshToken.isBlank()) {
            authTokenRepository.findByTokenHash(hashToken(rawRefreshToken)).ifPresent(AuthToken::revoke);
        }
        recordAccessLog(user, "LOGOUT", true, metadata, null, null);
    }

    @Transactional(readOnly = true)
    public UserProfileResponse me(UUID userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new AuthException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "로그인이 필요합니다."));
        return toProfile(user);
    }

    @Transactional
    public UserProfileResponse changePassword(UUID userId, PasswordChangeRequest request, RequestMetadata metadata) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new AuthException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "로그인이 필요합니다."));
        if (!passwordEncoder.matches(nullToEmpty(request.currentPassword()), user.getPasswordHash())) {
            throw invalidCredentials();
        }
        validatePassword(request.newPassword());
        user.changePasswordHash(passwordEncoder.encode(request.newPassword()));
        authTokenRepository.findByUserAndTypeAndRevokedAtIsNull(user, AuthTokenType.REFRESH)
                .forEach(AuthToken::revoke);
        recordAccessLog(user, "PASSWORD_CHANGED", true, metadata, null, null);
        return toProfile(user);
    }

    public User requireUser(UUID userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new AuthException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "로그인이 필요합니다."));
    }

    public UserProfileResponse toProfile(User user) {
        UserSetting setting = userSettingRepository.findByUserId(user.getId()).orElse(null);
        return new UserProfileResponse(
                user.getId(),
                user.getEmail(),
                setting == null || setting.getDisplayName() == null ? user.getEmail().split("@")[0] : setting.getDisplayName(),
                setting == null ? null : setting.getDepartment(),
                user.getRole().name(),
                user.getStatus().name(),
                user.isEmailVerified(),
                user.getLastLoginAt()
        );
    }

    public void recordAccessLog(User user, String actionType, boolean success, RequestMetadata metadata, UUID sessionId, Map<String, Object> extra) {
        userAccessLogRepository.save(UserAccessLog.create(
                user,
                actionType,
                success,
                metadata == null ? null : metadata.ipAddress(),
                metadata == null ? null : metadata.userAgent(),
                sessionId,
                extra
        ));
    }

    private TokenIssue issueEmailVerificationToken(User user, RequestMetadata metadata) {
        String rawToken = newRawToken();
        Instant expiresAt = Instant.now().plus(Duration.ofMinutes(authProperties.getEmailVerificationMinutes()));
        AuthToken token = authTokenRepository.save(AuthToken.emailVerification(
                user,
                hashToken(rawToken),
                expiresAt,
                metadata == null ? null : metadata.userAgent(),
                metadata == null ? null : metadata.ipAddress()
        ));
        return new TokenIssue(rawToken, expiresAt, null, token.getId());
    }

    private TokenIssue issueRefreshToken(User user, UUID tokenFamilyId, UUID previousTokenId, RequestMetadata metadata) {
        UUID familyId = tokenFamilyId == null ? UUID.randomUUID() : tokenFamilyId;
        String rawToken = newRawToken();
        Instant expiresAt = Instant.now().plus(Duration.ofDays(authProperties.getRefreshTokenDays()));
        AuthToken token = authTokenRepository.save(AuthToken.refresh(
                user,
                hashToken(rawToken),
                familyId,
                previousTokenId,
                expiresAt,
                metadata == null ? null : metadata.userAgent(),
                metadata == null ? null : metadata.ipAddress()
        ));
        return new TokenIssue(rawToken, expiresAt, familyId, token.getId());
    }

    private void revokeUnusedEmailTokens(User user) {
        authTokenRepository.findByUserAndTypeAndRevokedAtIsNull(user, AuthTokenType.EMAIL_VERIFICATION)
                .stream()
                .filter(token -> !token.isUsed())
                .forEach(AuthToken::revoke);
    }

    private AuthToken findToken(String rawToken, String code, String message) {
        if (rawToken == null || rawToken.isBlank()) {
            throw new AuthException(HttpStatus.BAD_REQUEST, code, message);
        }
        return authTokenRepository.findByTokenHash(hashToken(rawToken))
                .orElseThrow(() -> new AuthException(HttpStatus.BAD_REQUEST, code, message));
    }

    private void ensureLoginAllowed(User user) {
        if (user.getStatus() == UserStatus.PENDING || !user.isEmailVerified()) {
            throw new AuthException(HttpStatus.FORBIDDEN, "EMAIL_VERIFICATION_REQUIRED", "이메일 인증이 필요합니다.");
        }
        if (user.getStatus() == UserStatus.SUSPENDED || user.getStatus() == UserStatus.WITHDRAWN) {
            throw new AuthException(HttpStatus.FORBIDDEN, "ACCOUNT_SUSPENDED", "사용할 수 없는 계정입니다.");
        }
        if (user.getRole() != UserRole.USER && user.getRole() != UserRole.ADMIN) {
            throw new AuthException(HttpStatus.FORBIDDEN, "FORBIDDEN", "접근 권한이 없습니다.");
        }
    }

    private void validateEmail(String email) {
        if (!EMAIL_PATTERN.matcher(email).matches()) {
            throw new AuthException(HttpStatus.BAD_REQUEST, "INVALID_EMAIL_FORMAT", "이메일 형식이 올바르지 않습니다.");
        }
        String domain = email.substring(email.indexOf('@') + 1);
        var allowedDomains = authProperties.allowedEmailDomainSet();
        if (!allowedDomains.contains("*") && !allowedDomains.contains(domain)) {
            throw new AuthException(HttpStatus.BAD_REQUEST, "NOT_ALLOWED_EMAIL_DOMAIN", "허용되지 않은 이메일 도메인입니다.");
        }
    }

    private void validatePassword(String password) {
        String value = nullToEmpty(password);
        if (value.length() < 8 || value.length() > 64) {
            throw new AuthException(HttpStatus.BAD_REQUEST, "INVALID_PASSWORD_POLICY", "비밀번호는 8자 이상 64자 이하로 입력하세요.");
        }
    }

    private AuthException invalidCredentials() {
        return new AuthException(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "이메일 또는 비밀번호가 올바르지 않습니다.");
    }

    private void sendVerificationMail(User user, String rawToken) {
        String encodedToken = URLEncoder.encode(rawToken, StandardCharsets.UTF_8);
        String link = trimTrailingSlash(authProperties.getAppBaseUrl()) + "/auth/email-verifications/confirm?token=" + encodedToken;
        String delivery = nullToEmpty(authProperties.getEmailVerificationDelivery()).trim().toLowerCase();
        if ("log".equals(delivery)) {
            log.info("개발용 이메일 인증 링크 생성 email={} tokenHash={} link={}", user.getEmail(), hashToken(rawToken), link);
            return;
        }
        if (!delivery.isBlank() && !"ses".equals(delivery)) {
            log.warn("알 수 없는 이메일 인증 발송 방식입니다. SES로 발송합니다. delivery={}", delivery);
        }

        try {
            sesMailService.sendTextMail(
                    user.getEmail(),
                    "[AXIS] 이메일 인증",
                    "AXIS 가입을 완료하려면 아래 링크를 열어주세요.\n\n" + link
            );
        } catch (Exception e) {
            log.warn("이메일 인증 메일 발송 실패 email={}", user.getEmail(), e);
            throw new AuthException(HttpStatus.SERVICE_UNAVAILABLE, "EMAIL_DELIVERY_FAILED", "인증 메일 발송에 실패했습니다. 메일 발송 설정을 확인하세요.");
        }
    }

    private String newRawToken() {
        byte[] token = new byte[32];
        SECURE_RANDOM.nextBytes(token);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(token);
    }

    private String hashToken(String rawToken) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(rawToken.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required for token hashing", e);
        }
    }

    private String normalizeEmail(String email) {
        return nullToEmpty(email).trim().toLowerCase();
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private String trimTrailingSlash(String value) {
        return value == null || value.isBlank() ? "" : value.replaceAll("/+$", "");
    }

    private record TokenIssue(String rawToken, Instant expiresAt, UUID familyId, UUID tokenId) {
    }

    public record AuthLoginResult(AuthResponse response, String refreshToken) {
    }
}
