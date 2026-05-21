package com.skala.axis.config;

import com.skala.axis.domain.UserRole;

import java.util.UUID;

public record AuthPrincipal(UUID userId, String email, UserRole role) {
}
