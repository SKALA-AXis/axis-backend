package com.skala.axis.service;

import com.skala.axis.domain.User;
import com.skala.axis.domain.UserStatus;
import com.skala.axis.dto.auth.UserProfileResponse;
import com.skala.axis.repository.UserRepository;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AdminUserService {
    private final UserRepository userRepository;
    private final AuthService authService;

    @Transactional(readOnly = true)
    public Map<String, Object> listUsers() {
        List<UserProfileResponse> items = userRepository.findAll()
                .stream()
                .sorted((left, right) -> {
                    if (left.getLastLoginAt() == null && right.getLastLoginAt() == null) {
                        return left.getEmail().compareToIgnoreCase(right.getEmail());
                    }
                    if (left.getLastLoginAt() == null) {
                        return 1;
                    }
                    if (right.getLastLoginAt() == null) {
                        return -1;
                    }
                    return right.getLastLoginAt().compareTo(left.getLastLoginAt());
                })
                .map(authService::toProfile)
                .toList();
        return Map.of("items", items, "total", items.size());
    }

    @Transactional(readOnly = true)
    public UserProfileResponse user(UUID userId) {
        return authService.toProfile(userRepository.findById(userId)
                .orElseThrow(() -> new EntityNotFoundException("user not found")));
    }

    @Transactional
    public UserProfileResponse changeStatus(UUID userId, String status) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new EntityNotFoundException("user not found"));
        user.changeStatus(parseStatus(status));
        return authService.toProfile(user);
    }

    private UserStatus parseStatus(String status) {
        if (status == null || status.isBlank()) {
            throw new IllegalArgumentException("status is required");
        }

        try {
            return UserStatus.valueOf(status.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("unsupported user status: " + status);
        }
    }
}
