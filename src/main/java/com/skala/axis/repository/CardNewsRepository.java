/*
 * 작성일: 2026-05-12
 * 작성자: 최종민
 * 변경이력:
 *   2026-05-12 최종민 — issue_cards를 card_news로 리네이밍하며 리포지토리 신설, 이후 대형 이벤트 알림 후보 조회 추가
 *   2026-05-22 박진 — 카드뉴스 대폭 수정 및 알림 설정 반영
 *   2026-05-29 안가은 — 카드뉴스 소프트삭제·관리자 감사로그 API용 조회 메서드 추가
 */
package com.skala.axis.repository;

import com.skala.axis.domain.CardNews;
import com.skala.axis.domain.CardNewsStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface CardNewsRepository extends JpaRepository<CardNews, String> {
    @Query("SELECT c FROM CardNews c WHERE c.status = :status AND c.createdAt >= :since ORDER BY c.importanceScore DESC")
    List<CardNews> findTodayCards(@Param("since") LocalDateTime since, @Param("status") CardNewsStatus status);

    /**
     * 대형 이벤트 알림 후보 — 지정 event_type(소문자) 의 최근 ACTIVE 카드.
     * 게이트/중복 판정은 {@code EventAlertService} 가 수행.
     */
    @Query("SELECT c FROM CardNews c WHERE c.status = :status AND c.createdAt >= :since "
            + "AND lower(c.eventType) IN :eventTypes ORDER BY c.createdAt DESC")
    List<CardNews> findAlertCandidates(@Param("since") LocalDateTime since,
                                       @Param("status") CardNewsStatus status,
                                       @Param("eventTypes") Collection<String> eventTypes);

    List<CardNews> findByPeerIdOrderByCreatedAtDesc(String peerId);

    List<CardNews> findTop50ByStatusOrderByCreatedAtDesc(CardNewsStatus status);

    List<CardNews> findByStatusOrderByCreatedAtDesc(CardNewsStatus status);

    Optional<CardNews> findByIdAndStatus(String id, CardNewsStatus status);

    boolean existsByIdAndStatus(String id, CardNewsStatus status);
}
