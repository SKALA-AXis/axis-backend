/*
 * 작성일: 2026-05-21
 * 작성자: 박진
 * 변경이력:
 *   2026-05-21 박진 — 로그인/회원가입 기능 추가 시 카드뉴스 북마크 리포지토리 추가
 */
package com.skala.axis.repository;

import com.skala.axis.domain.UserCardNewsBookmark;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

public interface UserCardNewsBookmarkRepository extends JpaRepository<UserCardNewsBookmark, UUID> {
    List<UserCardNewsBookmark> findByUserIdOrderByCreatedAtDesc(UUID userId);

    boolean existsByUserIdAndCardNewsId(UUID userId, String cardNewsId);

    @Transactional
    void deleteByUserIdAndCardNewsId(UUID userId, String cardNewsId);
}
