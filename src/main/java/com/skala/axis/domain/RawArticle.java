package com.skala.axis.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.LocalDateTime;
import java.util.List;

@Entity
@Table(name = "raw_articles")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RawArticle {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "company", nullable = false, columnDefinition = "jsonb")
    private List<String> company;

    @Column(nullable = false)
    private String title;

    @Column(name = "published_at")
    private LocalDateTime publishedAt;

    @Column(name = "processing_status", length = 30)
    private String processingStatus;

    @Column(name = "created_at")
    private LocalDateTime createdAt;
}
