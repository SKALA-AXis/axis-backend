/*
 * 작성일: 2026-05-06
 * 작성자: 박진
 * 변경이력:
 *   2026-05-06 박진 — 백엔드 초안에 카드 컨트롤러 작성 및 챗봇 연동 로직 보강
 *   2026-05-15 최종민 — /api/cards/{id}/verify-link 를 axis-ai /link/verify 와 연동하고 실 DB 조회로 전환
 *   2026-05-27 안가은 — 카드뉴스 데이터 연동
 *   2026-06-17 심유정 — 카드 전략 액션 projection 추가
 */
package com.skala.axis.controller;

import com.skala.axis.config.AuthProperties;
import com.skala.axis.config.AuthSecurity;
import com.skala.axis.dto.ApiResponse;
import com.skala.axis.dto.CardNewsResponse;
import com.skala.axis.exception.AiServerException;
import com.skala.axis.service.AgentResponseGuard;
import com.skala.axis.service.AiClientService;
import com.skala.axis.service.CardNewsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
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
import java.util.UUID;

/**
 * 카드 뉴스 endpoint.
 *
 * <p>v2 변경: {@code GET /api/cards}, {@code /today}, {@code /{id}} 가
 * 실 DB ({@link CardNewsService}) 조회. axis-ai ingestion 이 생성한 IC-*
 * 카드를 frontend 가 직접 받음.</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/cards")
@RequiredArgsConstructor
public class CardController {
    private final AuthProperties authProperties;
    private final AiClientService aiClientService;
    private final CardNewsService cardNewsService;

    @GetMapping
    public ResponseEntity<ApiResponse<Map<String, Object>>> listCards(
            @RequestParam Map<String, String> params,
            Authentication authentication
    ) {
        String peerId = params.get("peer_id");
        String importance = params.get("importance");
        String eventType = params.get("event_type");
        int limit = parseInt(params.get("limit"), -1);
        int offset = parseInt(params.get("offset"), 0);

        List<CardNewsResponse> all = cardNewsService.getAll(
                peerId,
                importance,
                eventType,
                resolveOptionalUserId(authentication)
        );
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
    public ResponseEntity<ApiResponse<Map<String, Object>>> getTodayCards(
            @RequestParam Map<String, String> params,
            Authentication authentication
    ) {
        String peerId = params.get("peer_id");
        String importance = params.get("importance");
        int limit = parseInt(params.get("limit"), -1);
        List<CardNewsResponse> all = cardNewsService.getTodayCards(
                peerId,
                importance,
                resolveOptionalUserId(authentication)
        );
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
    public ResponseEntity<ApiResponse<Object>> getCardById(
            @PathVariable String id,
            Authentication authentication
    ) {
        try {
            CardNewsResponse card = cardNewsService.getById(id, resolveOptionalUserId(authentication));
            return ResponseEntity.ok(ApiResponse.success(card));
        } catch (jakarta.persistence.EntityNotFoundException e) {
            log.info("getCardById | DB miss | id={}", id);
            return ResponseEntity.ok(ApiResponse.success(Map.of(
                    "id", id,
                    "status", "not_found",
                    "result_kind", "no_saved_card"
            )));
        }
    }

    @PostMapping("/{id}/strategy-context/apply")
    public ResponseEntity<ApiResponse<CardNewsResponse>> applyStrategyContext(
            @PathVariable String id,
            Authentication authentication
    ) {
        return ResponseEntity.ok(ApiResponse.success(
                cardNewsService.applyStrategyContext(id, resolveUserId(authentication))
        ));
    }

    @PostMapping("/{id}/strategy-context/revert")
    public ResponseEntity<ApiResponse<CardNewsResponse>> revertStrategyContext(
            @PathVariable String id,
            Authentication authentication
    ) {
        return ResponseEntity.ok(ApiResponse.success(
                cardNewsService.revertStrategyContext(id, resolveUserId(authentication))
        ));
    }

    private static int parseInt(String value, int defaultValue) {
        if (value == null || value.isBlank()) return defaultValue;
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private UUID resolveUserId(Authentication authentication) {
        if (authProperties.isEnforce()) {
            return AuthSecurity.requireUserId(authentication);
        }
        if (authentication != null && authentication.getPrincipal() instanceof com.skala.axis.config.AuthPrincipal principal) {
            return principal.userId();
        }
        return null;
    }

    private UUID resolveOptionalUserId(Authentication authentication) {
        if (authentication != null && authentication.getPrincipal() instanceof com.skala.axis.config.AuthPrincipal principal) {
            return principal.userId();
        }
        return null;
    }

    /**
     * 카드 출처 링크 검증 — axis-ai LinkVerificationAgent 위임.
     *
     * <p>response: axis-ai 의 LinkVerificationOutput (card_id / sources[] / overall_status /
     * verified_at / warning). axis-ai 미가용 / 에러 시 실패 상태를 반환한다.</p>
     *
     * <p>spec: {@code axis-ai/design/30-analysis/link-verification.md}.</p>
     */
    @PostMapping("/{id}/verify-link")
    public ResponseEntity<ApiResponse<Map<String, Object>>> verifyCardLinks(@PathVariable String id) {
        try {
            Map<String, Object> result = aiClientService.verifyLink(id).block();
            AgentResponseGuard.requireSuccess("LINK_VERIFY", result);
            log.info("VerifyLink | card={} overall={}", id, result.get("overall_status"));
            return ResponseEntity.ok(ApiResponse.success(result));
        } catch (AiServerException e) {
            log.warn("VerifyLink | axis-ai 호출 실패 | card={} code={} err={}",
                    id, e.getCode(), e.getMessage());
            return ResponseEntity.status(e.getStatus())
                    .body(ApiResponse.error(e.getCode(), AiServerException.CALL_FAILED_MESSAGE));
        }
    }

    @PostMapping("/{id}/share")
    public ResponseEntity<ApiResponse<Map<String, Object>>> shareCard(
            @PathVariable String id,
            @RequestBody(required = false) Map<String, Object> request
    ) {
        return ResponseEntity.ok(ApiResponse.success(Map.of(
                "status", "failed",
                "result_kind", "card_share_store_unavailable",
                "card_id", id
        )));
    }
}
