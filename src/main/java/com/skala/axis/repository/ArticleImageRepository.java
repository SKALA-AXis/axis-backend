package com.skala.axis.repository;

import com.skala.axis.domain.ArticleImage;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ArticleImageRepository extends JpaRepository<ArticleImage, Long> {

    /** 카드별 대표 이미지 조회 — CardNewsService 가 응답 빌드 시 사용. */
    Optional<ArticleImage> findFirstByCardNewsIdOrderByCreatedAtDesc(String cardNewsId);
}
