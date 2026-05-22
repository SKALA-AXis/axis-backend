package com.skala.axis.repository;

import com.skala.axis.domain.CardNews;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.time.LocalDateTime;
import java.util.List;

public interface CardNewsRepository extends JpaRepository<CardNews, String> {
    @Query("SELECT c FROM CardNews c WHERE c.createdAt >= :since ORDER BY c.importanceScore DESC")
    List<CardNews> findTodayCards(@Param("since") LocalDateTime since);

    List<CardNews> findByPeerIdOrderByCreatedAtDesc(String peerId);

    List<CardNews> findTop50ByOrderByCreatedAtDesc();
}
