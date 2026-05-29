package com.skala.axis.service;

import com.skala.axis.domain.AdminAuditLog;
import com.skala.axis.repository.AdminAuditLogRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class AdminAuditLogService {
    private final AdminAuditLogRepository adminAuditLogRepository;

    @Transactional(readOnly = true)
    public Map<String, Object> listLogs() {
        List<Map<String, Object>> items = adminAuditLogRepository.findTop100ByOrderByCreatedAtDesc().stream()
                .map(this::toItem)
                .toList();
        return Map.of("items", items, "total", items.size());
    }

    private Map<String, Object> toItem(AdminAuditLog log) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", log.getId());
        item.put("actor_email", log.getActorEmail());
        item.put("action", log.getActionType());
        item.put("resource_type", log.getResourceType());
        item.put("resource_id", log.getResourceId());
        item.put("reason", log.getReason());
        item.put("payload", log.getPayload());
        item.put("created_at", log.getCreatedAt());
        return item;
    }
}
