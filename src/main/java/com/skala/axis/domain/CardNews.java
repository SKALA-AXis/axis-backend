package com.skala.axis.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.LocalDateTime;
import java.util.Map;

@Entity
@Table(name = "card_news")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CardNews {
    @Id
    @Column(length = 50)
    private String id;

    @Column(name = "company", nullable = false, length = 50)
    private String peerId;

    @Column(name = "cluster_id")
    private Long clusterId;

    @Column(nullable = false)
    private String title;

    @Column(name = "event_type", length = 50)
    private String eventType;

    @Column(length = 20)
    private String importance;

    @Column(name = "importance_score")
    private Float importanceScore;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "implication", columnDefinition = "jsonb")
    private Map<String, Object> implication;

    @Column(name = "validation_pass")
    private Boolean validationPass;

    @Column(name = "validation_sc_score")
    private Float validationScScore;

    @Column(name = "created_at")
    private LocalDateTime createdAt;
}
