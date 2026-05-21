package com.skala.axis.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "axis.auth")
public class AuthProperties {
    private boolean enforce = true;
    private String allowedEmailDomains = "sk.com";
    private String jwtSecret = "axis-local-dev-secret-change-me-32-bytes-minimum";
    private long accessTokenMinutes = 15;
    private long refreshTokenDays = 14;
    private long emailVerificationMinutes = 60;
    private int maxFailedLoginCount = 5;
    private long lockMinutes = 10;
    private String refreshCookieName = "axis_refresh";
    private boolean refreshCookieSecure = false;
    private String refreshCookieSameSite = "Lax";
    private String appBaseUrl = "http://localhost:3100";
    private String bootstrapAdminEmails = "";

    public Set<String> allowedEmailDomainSet() {
        return Arrays.stream(allowedEmailDomains.split(","))
                .map(String::trim)
                .map(String::toLowerCase)
                .filter(value -> !value.isBlank())
                .collect(Collectors.toUnmodifiableSet());
    }

    public Set<String> bootstrapAdminEmailSet() {
        return Arrays.stream(bootstrapAdminEmails.split(","))
                .map(String::trim)
                .map(String::toLowerCase)
                .filter(value -> !value.isBlank())
                .collect(Collectors.toUnmodifiableSet());
    }
}
