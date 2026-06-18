/*
 * 작성일: 2026-06-15
 * 작성자: 최종민
 * 변경이력:
 *   2026-06-15 최종민 — 대형 이벤트(수주·파트너십·M&A) 1회성 이메일 알림 엔티티 추가, AccessLevel lombok import 수정
 */
package com.skala.axis.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 대형 이벤트(수주·파트너십·M&A) 1회성 이메일 알림 발송 이력.
 *
 * <p>{@code dedupe_key} 가 UNIQUE — 같은 사건(동일 cluster_id)에 대한 알림은 1회만
 * 발송됨을 DB 레벨에서 보장한다. 발송은 {@code EventAlertService} → {@code SesMailService}
 * (AWS SES V2 + IRSA). 자세한 게이트/중복 정책: {@code EventAlertService} 참고.</p>
 */
@Entity
@Table(name = "sent_alerts")
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class SentAlert {

    @Id
    @Column(columnDefinition = "uuid")
    private UUID id;

    /** 중복 방지 키 — 보통 "cluster:{clusterId}" 또는 "card:{cardId}" / "demo:{hash}". */
    @Column(name = "dedupe_key", nullable = false, length = 200)
    private String dedupeKey;

    @Column(name = "card_news_id", length = 50)
    private String cardNewsId;

    @Column(name = "cluster_id")
    private Long clusterId;

    @Column(name = "peer_id", length = 50)
    private String peerId;

    @Column(name = "event_type", length = 50)
    private String eventType;

    @Column(columnDefinition = "text")
    private String title;

    @Column(name = "importance_score")
    private Float importanceScore;

    /** CSV — 발송 시점 수신자 스냅샷. */
    @Column(nullable = false, columnDefinition = "text")
    private String recipients;

    @Column(columnDefinition = "text")
    private String subject;

    /** auto | demo | manual. */
    @Column(name = "trigger_source", nullable = false, length = 30)
    private String triggerSource;

    @Column(name = "ses_message_id", length = 200)
    private String sesMessageId;

    /** sent | failed | skipped. */
    @Column(nullable = false, length = 20)
    private String status;

    @Column(name = "sent_at", nullable = false)
    private LocalDateTime sentAt;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
}
