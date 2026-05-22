package com.skala.axis.service;

import com.skala.axis.domain.User;
import com.skala.axis.domain.UserAccessLog;
import com.skala.axis.domain.UserSetting;
import com.skala.axis.dto.auth.UserProfileResponse;
import com.skala.axis.exception.AuthException;
import com.skala.axis.repository.UserAccessLogRepository;
import com.skala.axis.repository.UserSettingRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class UserSettingsService {
    private final AuthService authService;
    private final UserSettingRepository userSettingRepository;
    private final UserAccessLogRepository userAccessLogRepository;

    @Transactional(readOnly = true)
    public Map<String, Object> alertRules(UUID userId) {
        return new LinkedHashMap<>(setting(userId).getAlertRules());
    }

    @Transactional
    public Map<String, Object> updateAlertRules(UUID userId, Map<String, Object> request, RequestMetadata metadata) {
        User user = authService.requireUser(userId);
        UserSetting setting = setting(userId);
        setting.updateAlertRules(request);
        authService.recordAccessLog(user, "SETTINGS_UPDATED", true, metadata, null, Map.of("target", "alert_rules"));
        return new LinkedHashMap<>(setting.getAlertRules());
    }

    @Transactional(readOnly = true)
    public Map<String, Object> notificationSettings(UUID userId) {
        return new LinkedHashMap<>(setting(userId).getNotificationSettings());
    }

    @Transactional
    public Map<String, Object> updateNotificationSettings(UUID userId, Map<String, Object> request, RequestMetadata metadata) {
        User user = authService.requireUser(userId);
        UserSetting setting = setting(userId);
        setting.updateNotificationSettings(request);
        authService.recordAccessLog(user, "SETTINGS_UPDATED", true, metadata, null, Map.of("target", "notification_settings"));
        return new LinkedHashMap<>(setting.getNotificationSettings());
    }

    @Transactional(readOnly = true)
    public UserProfileResponse profile(UUID userId) {
        return authService.me(userId);
    }

    @Transactional
    public UserProfileResponse updateProfile(UUID userId, Map<String, Object> request, RequestMetadata metadata) {
        User user = authService.requireUser(userId);
        UserSetting setting = setting(userId);
        setting.updateProfile(
                stringValue(request, "name"),
                stringValue(request, "department")
        );
        authService.recordAccessLog(user, "PROFILE_UPDATED", true, metadata, null, null);
        return authService.toProfile(user);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> viewPreferences(UUID userId) {
        return new LinkedHashMap<>(setting(userId).getViewPreferences());
    }

    @Transactional
    public Map<String, Object> updateViewPreferences(UUID userId, Map<String, Object> request, RequestMetadata metadata) {
        User user = authService.requireUser(userId);
        UserSetting setting = setting(userId);
        setting.updateViewPreferences(request);
        authService.recordAccessLog(user, "SETTINGS_UPDATED", true, metadata, null, Map.of("target", "view_preferences"));
        return new LinkedHashMap<>(setting.getViewPreferences());
    }

    @Transactional(readOnly = true)
    public Map<String, Object> accessLogs(UUID userId) {
        authService.requireUser(userId);
        List<Map<String, Object>> items = userAccessLogRepository.findTop20ByUserIdOrderByOccurredAtDesc(userId)
                .stream()
                .map(this::toAccessLogItem)
                .toList();
        return Map.of("items", items);
    }

    private Map<String, Object> toAccessLogItem(UserAccessLog log) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", log.getId());
        item.put("action", log.getActionType());
        item.put("success", log.isSuccess());
        item.put("country", accessLogCountry(log));
        item.put("ipAddress", log.getIpAddress() == null ? "" : log.getIpAddress());
        item.put("userAgent", log.getUserAgent() == null ? "" : log.getUserAgent());
        item.put("occurredAt", log.getOccurredAt().toString());
        return item;
    }

    private String accessLogCountry(UserAccessLog log) {
        Object country = log.getMetadata() == null ? null : log.getMetadata().get("country");
        if (country instanceof String value && !value.isBlank()) {
            return value;
        }
        Object countryCode = log.getMetadata() == null ? null : log.getMetadata().get("countryCode");
        return RequestMetadata.inferCountryName(
                log.getIpAddress(),
                countryCode instanceof String value ? value : null
        );
    }

    private UserSetting setting(UUID userId) {
        return userSettingRepository.findByUserId(userId)
                .orElseThrow(() -> new AuthException(HttpStatus.NOT_FOUND, "USER_SETTINGS_NOT_FOUND", "사용자 설정을 찾을 수 없습니다."));
    }

    private String stringValue(Map<String, Object> request, String key) {
        Object value = request == null ? null : request.get(key);
        return value == null ? null : String.valueOf(value);
    }

}
