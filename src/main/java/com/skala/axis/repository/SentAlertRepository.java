/*
 * 작성일: 2026-06-15
 * 작성자: 최종민
 * 변경이력:
 *   2026-06-15 최종민 — 대형 이벤트(수주·파트너십·M&A) 1회성 이메일 알림용 발송 이력 리포지토리 추가
 */
package com.skala.axis.repository;

import com.skala.axis.domain.SentAlert;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public interface SentAlertRepository extends JpaRepository<SentAlert, UUID> {

    /** 1차(주) 중복 방지 — 동일 cluster/card 사건 알림 기발송 여부. */
    boolean existsByDedupeKey(String dedupeKey);

    /** 2차(보조) 중복 방지 — 같은 peer+event_type 이 최근 N일 내 발송됐는지(재보도 억제, 선택). */
    boolean existsByPeerIdAndEventTypeAndSentAtAfter(String peerId, String eventType, LocalDateTime after);

    /** 최근 발송 이력(운영/디버그 조회용). */
    List<SentAlert> findTop100ByOrderBySentAtDesc();
}
