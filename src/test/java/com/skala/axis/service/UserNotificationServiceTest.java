package com.skala.axis.service;

import com.skala.axis.domain.User;
import com.skala.axis.repository.CardNewsRepository;
import com.skala.axis.repository.UserNotificationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserNotificationServiceTest {
    @Mock
    private AuthService authService;
    @Mock
    private CardNewsRepository cardNewsRepository;
    @Mock
    private UserNotificationRepository notificationRepository;

    private UserNotificationService service;

    @BeforeEach
    void setUp() {
        service = new UserNotificationService(authService, cardNewsRepository, notificationRepository);
        ReflectionTestUtils.setField(service, "importantKeywords", List.of("수주", "계약", "실적", "투자"));
        ReflectionTestUtils.setField(service, "maxKeywords", 20);
        ReflectionTestUtils.setField(service, "maxKeywordLength", 30);
    }

    @Test
    void preferencesBackfillDefaultImportantKeywordsForLegacyUsers() {
        UUID userId = UUID.randomUUID();
        User user = User.pending("owner@sk.com", "encoded-password");
        Map<String, Object> legacyPreferences = new LinkedHashMap<>();
        legacyPreferences.put("enabled", true);
        legacyPreferences.put("importantEnabled", true);
        legacyPreferences.put("keywords", List.of("클라우드"));
        user.updateNotificationPreferences(legacyPreferences);
        when(authService.requireUser(userId)).thenReturn(user);

        Map<String, Object> preferences = service.preferences(userId);

        assertThat(preferences.get("importantKeywords")).isEqualTo(User.DEFAULT_IMPORTANT_KEYWORDS);
        assertThat(preferences.get("keywords")).isEqualTo(List.of("클라우드"));
    }

    @Test
    void updatePreferencesStoresCustomizedImportantKeywords() {
        UUID userId = UUID.randomUUID();
        User user = User.pending("owner@sk.com", "encoded-password");
        when(authService.requireUser(userId)).thenReturn(user);
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("enabled", true);
        request.put("importantEnabled", true);
        request.put("importantKeywords", List.of("수주", "우선협상", "수주"));
        request.put("keywords", List.of("AI agent"));

        Map<String, Object> preferences = service.updatePreferences(userId, request);

        assertThat(preferences.get("importantKeywords")).isEqualTo(List.of("수주", "우선협상"));
        assertThat(user.getNotificationPreferences().get("importantKeywords")).isEqualTo(List.of("수주", "우선협상"));
    }

    @Test
    void updatePreferencesAllowsRemovingAllImportantKeywords() {
        UUID userId = UUID.randomUUID();
        User user = User.pending("owner@sk.com", "encoded-password");
        when(authService.requireUser(userId)).thenReturn(user);
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("enabled", true);
        request.put("importantEnabled", true);
        request.put("importantKeywords", List.of());
        request.put("keywords", List.of());

        Map<String, Object> preferences = service.updatePreferences(userId, request);

        assertThat(preferences.get("importantKeywords")).isEqualTo(List.of());
    }
}
