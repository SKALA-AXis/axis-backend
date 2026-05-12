package com.skala.axis.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 카드 뉴스 이미지 메타. 파일은 공유 볼륨(IMAGE_STORAGE_PATH)에 저장되고,
 * 본 엔티티는 그 파일에 도달하기 위한 storage_path + 표시 메타만 보관한다.
 *
 * INSERT/UPDATE 는 axis-ai (ImageFetchAgent) 가 담당. backend 는 read-only.
 */
@Entity
@Table(name = "article_images")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ArticleImage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "article_id")
    private Long articleId;

    @Column(name = "cluster_id")
    private Long clusterId;

    // V9 (2026-05-12): card_news 테이블 rename 후에도 본 컬럼은 issue_card_id 유지.
    // 컬럼 rename 은 V10 (deploy 안정화 후) 에 분리. Java field 명은 cardNewsId 로
    // 의미 정렬, @Column.name 만 legacy column 가리킴.
    @Column(name = "issue_card_id", length = 50)
    private String cardNewsId;

    @Column(name = "source_url", nullable = false, columnDefinition = "TEXT")
    private String sourceUrl;

    @Column(name = "source_url_hash", nullable = false, unique = true, length = 64)
    private String sourceUrlHash;

    /** IMAGE_STORAGE_PATH 기준 상대 경로 (예: 'lg_cns/2026-04/abc123.jpg'). */
    @Column(name = "storage_path", nullable = false, columnDefinition = "TEXT")
    private String storagePath;

    @Column(name = "content_type", length = 50)
    private String contentType;

    @Column(name = "width")
    private Integer width;

    @Column(name = "height")
    private Integer height;

    @Column(name = "file_size_bytes")
    private Integer fileSizeBytes;

    @Column(name = "alt_text", columnDefinition = "TEXT")
    private String altText;

    @Column(name = "attribution", columnDefinition = "TEXT")
    private String attribution;

    @Column(name = "fetched_at")
    private LocalDateTime fetchedAt;

    @Column(name = "created_at")
    private LocalDateTime createdAt;
}
