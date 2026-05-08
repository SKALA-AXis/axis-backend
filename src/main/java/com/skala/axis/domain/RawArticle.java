package com.skala.axis.domain;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "raw_articles")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RawArticle {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "company", nullable = false, length = 50)
    private String peerId;

    @Column(nullable = false)
    private String title;

    @Column(name = "published_at")
    private LocalDateTime publishedAt;

    @Column(name = "processing_status", length = 30)
    private String processingStatus;

    @Column(name = "created_at")
    private LocalDateTime createdAt;
}
