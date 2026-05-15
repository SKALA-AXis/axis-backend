package com.skala.axis.controller;

import com.skala.axis.dto.ApiResponse;
import com.skala.axis.exception.AiServerException;
import com.skala.axis.service.AiClientService;
import com.skala.axis.service.ApiContractFixtureService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/cards")
@RequiredArgsConstructor
public class CardController {
    private final ApiContractFixtureService fixture;
    private final AiClientService aiClientService;

    @GetMapping
    public ResponseEntity<ApiResponse<Map<String, Object>>> listCards(@RequestParam Map<String, String> params) {
        return ResponseEntity.ok(ApiResponse.success(fixture.cardList(params)));
    }

    @GetMapping("/today")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getTodayCards(@RequestParam Map<String, String> params) {
        return ResponseEntity.ok(ApiResponse.success(fixture.todayCards(params)));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getCardById(@PathVariable String id) {
        return ResponseEntity.ok(ApiResponse.success(fixture.card(id)));
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
