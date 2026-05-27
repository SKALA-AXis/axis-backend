package com.skala.axis.controller;

import com.skala.axis.dto.ApiResponse;
import com.skala.axis.dto.CardNewsResponse;
import com.skala.axis.exception.AiServerException;
import com.skala.axis.service.AiClientService;
import com.skala.axis.service.ApiContractFixtureService;
import com.skala.axis.service.CardNewsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 카드 뉴스 endpoint.
 *
 * <p>v2 변경: {@code GET /api/cards}, {@code /today}, {@code /{id}} 가 fixture
 * stub → 실 DB ({@link CardNewsService}) 조회. axis-ai ingestion 이 생성한 IC-*
 * 카드를 frontend 가 직접 받음.</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/cards")
@RequiredArgsConstructor
public class CardController {
    private final ApiContractFixtureService fixture;
    private final AiClientService aiClientService;
    private final CardNewsService cardNewsService;

    @Value("${spring.datasource.url:}")
    private String datasourceUrl;

    @GetMapping
    public ResponseEntity<ApiResponse<Map<String, Object>>> listCards(@RequestParam Map<String, String> params) {
        String peerId = params.get("peer_id");
        String importance = params.get("importance");
        String eventType = params.get("event_type");
        int limit = parseInt(params.get("limit"), -1);
        int offset = parseInt(params.get("offset"), 0);

        List<CardNewsResponse> all = cardNewsService.getAll(peerId, importance, eventType);
        if (all.isEmpty() && shouldUseFixtureFallback()) {
            log.info("listCards | DB empty on in-memory datasource — fixture fallback");
            return ResponseEntity.ok(ApiResponse.success(fixture.cardList(params)));
        }
        int total = all.size();
        var stream = all.stream().skip(Math.max(offset, 0));
        List<CardNewsResponse> page = limit > 0
                ? stream.limit(limit).toList()
                : stream.toList();

        log.info("listCards | total={} page={} peer={} importance={} event={}",
                total, page.size(), peerId, importance, eventType);
        return ResponseEntity.ok(ApiResponse.success(Map.of(
                "items", page,
                "total", total,
                "limit", limit > 0 ? limit : total,
                "offset", offset
        )));
    }

    @GetMapping("/today")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getTodayCards(@RequestParam Map<String, String> params) {
        String peerId = params.get("peer_id");
        String importance = params.get("importance");
        int limit = parseInt(params.get("limit"), -1);
        List<CardNewsResponse> all = cardNewsService.getTodayCards(peerId, importance);
        if (all.isEmpty() && shouldUseFixtureFallback()) {
            log.info("todayCards | DB empty on in-memory datasource — fixture fallback");
            return ResponseEntity.ok(ApiResponse.success(fixture.todayCards(params)));
        }
        int total = all.size();
        List<CardNewsResponse> items = all;
        if (limit > 0) {
            items = items.stream().limit(limit).toList();
        }
        log.info("todayCards | total={} page={} peer={} importance={}", total, items.size(), peerId, importance);
        return ResponseEntity.ok(ApiResponse.success(Map.of(
                "date", LocalDate.now().toString(),
                "items", items,
                "total", total
        )));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<Object>> getCardById(@PathVariable String id) {
        try {
            CardNewsResponse card = cardNewsService.getById(id);
            return ResponseEntity.ok(ApiResponse.success(card));
        } catch (jakarta.persistence.EntityNotFoundException e) {
            if (shouldUseFixtureFallback()) {
                log.info("getCardById | DB miss on in-memory datasource — fixture fallback | id={}", id);
                return ResponseEntity.ok(ApiResponse.success(fixture.card(id)));
            }
            log.info("getCardById | DB miss | id={}", id);
            return ResponseEntity.status(404)
                    .body(ApiResponse.error("COMMON_NOT_FOUND", "카드뉴스를 찾을 수 없습니다."));
        }
    }

    private boolean shouldUseFixtureFallback() {
        return datasourceUrl != null && datasourceUrl.startsWith("jdbc:h2:mem:");
    }

    private static int parseInt(String value, int defaultValue) {
        if (value == null || value.isBlank()) return defaultValue;
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    /**
     * 카드 출처 링크 검증 — axis-ai LinkVerificationAgent 위임.
     *
     * <p>response: axis-ai 의 LinkVerificationOutput (card_id / sources[] / overall_status /
     * verified_at / warning). axis-ai 미가용 / 에러 시 fixture fallback.</p>
     *
     * <p>spec: {@code axis-ai/design/30-analysis/link-verification.md}.</p>
     */
    @PostMapping("/{id}/verify-link")
    public ResponseEntity<ApiResponse<Map<String, Object>>> verifyCardLinks(@PathVariable String id) {
        try {
            Map<String, Object> result = aiClientService.verifyLink(id).block();
            if (result == null) {
                log.warn("VerifyLink | axis-ai 응답 null — fixture fallback | card={}", id);
                return ResponseEntity.ok(ApiResponse.success(fixture.verifyLinks(id)));
            }
            log.info("VerifyLink | card={} overall={}", id, result.get("overall_status"));
            return ResponseEntity.ok(ApiResponse.success(result));
        } catch (AiServerException e) {
            log.warn("VerifyLink | axis-ai 호출 실패 — fixture fallback | card={} err={}",
                    id, e.getMessage());
            return ResponseEntity.ok(ApiResponse.success(fixture.verifyLinks(id)));
        }
    }

    @PostMapping("/{id}/share")
    public ResponseEntity<ApiResponse<Map<String, Object>>> shareCard(
            @PathVariable String id,
            @RequestBody(required = false) Map<String, Object> request
    ) {
        Integer expiresInHours = request == null || request.get("expires_in_hours") == null
                ? null
                : ((Number) request.get("expires_in_hours")).intValue();
        return ResponseEntity.ok(ApiResponse.success(fixture.shareCard(id, expiresInHours)));
    }
}
