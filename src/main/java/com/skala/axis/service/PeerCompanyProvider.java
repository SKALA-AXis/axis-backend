package com.skala.axis.service;

import com.skala.axis.domain.PeerCompany;
import com.skala.axis.repository.PeerCompanyRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * peer 표시명을 {@code peer_companies} 단일 출처에서 제공한다 (refactoring B-R1).
 *
 * <p>기존에는 {@code IssueCardController}/{@code FrontendCompatibilityController} 등 여러 곳에
 * {@code peer_id -> 회사명} if-체인이 동일하게 하드코딩돼 있어, peer 추가/변경 시 모든 사본을
 * 고쳐야 했다. 이 컴포넌트가 DB 를 단일 출처로 삼아 그 매핑을 일원화한다.
 *
 * <p>조회 결과는 메모리에 캐시한다 (peer 목록은 거의 불변). DB 가 비었거나 조회에 실패하면
 * 기존 하드코딩과 동일한 정적 폴백으로 안전하게 동작하고, 이 경우 캐시하지 않아 다음 호출에서
 * 다시 DB 를 시도한다.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class PeerCompanyProvider {

    /** peerId 가 null/blank 일 때의 표시명 — 기존 컨트롤러 동작과 동일. */
    private static final String UNKNOWN_DISPLAY = "Peer사";

    /** DB 미가용/공백 시 폴백 — 기존 컨트롤러 하드코딩과 동일. */
    private static final Map<String, String> FALLBACK_NAMES = Map.of(
            "samsung_sds", "삼성SDS",
            "lg_cns", "LG CNS",
            "hyundai_autoever", "현대오토에버",
            "posco_dx", "포스코DX");

    private final PeerCompanyRepository peerCompanyRepository;
    private final AtomicReference<Map<String, String>> nameCache = new AtomicReference<>();

    /**
     * peer_id 표시명. 미상이면 peerId 를 그대로, null/blank 면 {@value #UNKNOWN_DISPLAY} 반환.
     * (기존 {@code peerName(String)} 정적 헬퍼들의 동작을 그대로 보존한다.)
     */
    public String displayName(String peerId) {
        if (peerId == null || peerId.isBlank()) {
            return UNKNOWN_DISPLAY;
        }
        return names().getOrDefault(peerId, peerId);
    }

    private Map<String, String> names() {
        Map<String, String> cached = nameCache.get();
        if (cached != null) {
            return cached;
        }
        Map<String, String> loaded = loadFromDb();
        if (loaded != null) {
            nameCache.set(loaded);
            return loaded;
        }
        return FALLBACK_NAMES;
    }

    /** peer_companies 에서 id→name 적재. 비었거나 실패하면 null (폴백 신호, 캐시하지 않음). */
    private Map<String, String> loadFromDb() {
        try {
            Map<String, String> map = new LinkedHashMap<>();
            for (PeerCompany peer : peerCompanyRepository.findAll()) {
                if (peer.getId() != null && peer.getName() != null) {
                    map.put(peer.getId(), peer.getName());
                }
            }
            if (!map.isEmpty()) {
                return Map.copyOf(map);
            }
            log.debug("peer_companies 비어있음 — 폴백 peer 명 매핑 사용");
        } catch (Exception e) {
            log.warn("peer_companies 조회 실패 — 폴백 peer 명 매핑 사용: {}", e.getMessage());
        }
        return null;
    }
}
