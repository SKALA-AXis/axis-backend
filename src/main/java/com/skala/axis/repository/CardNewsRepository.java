package com.skala.axis.repository;

import com.skala.axis.domain.CardNews;
import com.skala.axis.domain.CardNewsStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface CardNewsRepository extends JpaRepository<CardNews, String> {
    @Query("SELECT c FROM CardNews c WHERE c.status = :status AND c.createdAt >= :since ORDER BY c.importanceScore DESC")
    List<CardNews> findTodayCards(@Param("since") LocalDateTime since, @Param("status") CardNewsStatus status);

    List<CardNews> findByPeerIdOrderByCreatedAtDesc(String peerId);

    List<CardNews> findTop50ByStatusOrderByCreatedAtDesc(CardNewsStatus status);

    List<CardNews> findByStatusOrderByCreatedAtDesc(CardNewsStatus status);

    Optional<CardNews> findByIdAndStatus(String id, CardNewsStatus status);

    boolean existsByIdAndStatus(String id, CardNewsStatus status);
}
