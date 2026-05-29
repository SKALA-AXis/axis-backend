package com.skala.axis.service;

import com.skala.axis.config.AuthPrincipal;
import com.skala.axis.domain.AdminAuditLog;
import com.skala.axis.domain.CardNews;
import com.skala.axis.domain.CardNewsStatus;
import com.skala.axis.exception.AuthException;
import com.skala.axis.repository.AdminAuditLogRepository;
import com.skala.axis.repository.CardNewsRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class AdminCardNewsService {
    private static final String CARD_NEWS_RESOURCE_TYPE = "card_news";

    private final CardNewsRepository cardNewsRepository;
    private final AdminAuditLogRepository adminAuditLogRepository;

    @Transactional(readOnly = true)
    public Map<String, Object> listCards(String status) {
        CardNewsStatus filterStatus = parseStatus(status, null);
        List<CardNews> cards = filterStatus == null
                ? cardNewsRepository.findAll(Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id")))
                : cardNewsRepository.findByStatusOrderByCreatedAtDesc(filterStatus);
        Map<String, AdminAuditLog> latestDeleteLogs = latestLogsByCardId(cards, "card_news.delete");
        Map<String, AdminAuditLog> latestRestoreLogs = latestLogsByCardId(cards, "card_news.restore");

        List<Map<String, Object>> items = cards.stream()
                .map(card -> toItem(card, latestDeleteLogs.get(card.getId()), latestRestoreLogs.get(card.getId())))
                .toList();
        return Map.of("items", items, "total", items.size());
    }

    @Transactional
    public Map<String, Object> updateStatus(String cardId, String status, String reason, AuthPrincipal principal) {
        CardNewsStatus nextStatus = parseStatus(status, CardNewsStatus.ACTIVE);
        CardNews card = cardNewsRepository.findById(cardId)
                .orElseThrow(() -> new AuthException(HttpStatus.NOT_FOUND, "CARD_NEWS_NOT_FOUND", "카드뉴스를 찾을 수 없습니다."));

        CardNewsStatus currentStatus = card.getStatusOrDefault();
        if (currentStatus == nextStatus) {
            return toItem(card, latestLog(card.getId(), "card_news.delete"), latestLog(card.getId(), "card_news.restore"));
        }
        if (nextStatus == CardNewsStatus.DELETED && (reason == null || reason.isBlank())) {
            throw new AuthException(HttpStatus.BAD_REQUEST, "CARD_DELETE_REASON_REQUIRED", "삭제 사유를 입력해주세요.");
        }
        if (currentStatus == CardNewsStatus.DELETED
                && nextStatus == CardNewsStatus.ACTIVE
                && (reason == null || reason.isBlank())) {
            throw new AuthException(HttpStatus.BAD_REQUEST, "CARD_RESTORE_REASON_REQUIRED", "복구 사유를 입력해주세요.");
        }

        card.updateStatus(nextStatus);
        CardNews saved = cardNewsRepository.save(card);
        String normalizedReason = normalizeReason(reason);
        String actionType = actionType(currentStatus, nextStatus);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("title", saved.getTitle());
        payload.put("before_status", currentStatus.name());
        payload.put("after_status", nextStatus.name());
        payload.put("peer_id", saved.getPeerCompanyId() == null || saved.getPeerCompanyId().isBlank() ? saved.getPeerId() : saved.getPeerCompanyId());
        payload.put("occurred_at", Instant.now());

        AdminAuditLog auditLog = adminAuditLogRepository.save(AdminAuditLog.create(
                principal.userId(),
                principal.email(),
                actionType,
                CARD_NEWS_RESOURCE_TYPE,
                saved.getId(),
                normalizedReason,
                payload
        ));

        AdminAuditLog deleteLog = "card_news.delete".equals(actionType)
                ? auditLog
                : latestLog(saved.getId(), "card_news.delete");
        AdminAuditLog restoreLog = "card_news.restore".equals(actionType)
                ? auditLog
                : latestLog(saved.getId(), "card_news.restore");
        return toItem(saved, deleteLog, restoreLog);
    }

    private Map<String, AdminAuditLog> latestLogsByCardId(List<CardNews> cards, String actionType) {
        Set<String> cardIds = cards.stream()
                .map(CardNews::getId)
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (cardIds.isEmpty()) {
            return Map.of();
        }
        return adminAuditLogRepository.findByResourceTypeAndResourceIdInOrderByCreatedAtDesc(CARD_NEWS_RESOURCE_TYPE, cardIds).stream()
                .filter(log -> actionType.equals(log.getActionType()))
                .collect(Collectors.toMap(
                        AdminAuditLog::getResourceId,
                        Function.identity(),
                        (left, right) -> left,
                        LinkedHashMap::new
                ));
    }

    private AdminAuditLog latestLog(String cardId, String actionType) {
        return adminAuditLogRepository.findByResourceTypeAndResourceIdInOrderByCreatedAtDesc(
                        CARD_NEWS_RESOURCE_TYPE,
                        List.of(cardId)
                ).stream()
                .filter(log -> actionType.equals(log.getActionType()))
                .findFirst()
                .orElse(null);
    }

    private Map<String, Object> toItem(CardNews card, AdminAuditLog deleteLog, AdminAuditLog restoreLog) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", card.getId());
        item.put("title", card.getTitle());
        item.put("peer_id", card.getPeerCompanyId() == null || card.getPeerCompanyId().isBlank() ? card.getPeerId() : card.getPeerCompanyId());
        item.put("created_at", card.getCreatedAt());
        item.put("status", card.getStatusOrDefault().name());
        item.put("deleted_at", deleteLog == null ? null : deleteLog.getCreatedAt());
        item.put("deleted_by", deleteLog == null ? null : deleteLog.getActorEmail());
        item.put("deletion_reason", deleteLog == null ? null : deleteLog.getReason());
        item.put("restored_at", restoreLog == null ? null : restoreLog.getCreatedAt());
        item.put("restored_by", restoreLog == null ? null : restoreLog.getActorEmail());
        item.put("restored_reason", restoreLog == null ? null : restoreLog.getReason());
        return item;
    }

    private String actionType(CardNewsStatus before, CardNewsStatus after) {
        if (after == CardNewsStatus.DELETED) {
            return "card_news.delete";
        }
        if (before == CardNewsStatus.DELETED && after == CardNewsStatus.ACTIVE) {
            return "card_news.restore";
        }
        return "card_news.status_change";
    }

    private CardNewsStatus parseStatus(String rawStatus, CardNewsStatus defaultStatus) {
        if (rawStatus == null || rawStatus.isBlank()) {
            return defaultStatus;
        }
        try {
            return CardNewsStatus.valueOf(rawStatus.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw new AuthException(HttpStatus.BAD_REQUEST, "INVALID_CARD_STATUS", "카드뉴스 상태 값이 올바르지 않습니다.");
        }
    }

    private String normalizeReason(String reason) {
        if (reason == null) {
            return null;
        }
        String trimmed = reason.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
