package com.skala.axis.repository;

import com.skala.axis.domain.IssueCard;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.time.LocalDateTime;
import java.util.List;

public interface IssueCardRepository extends JpaRepository<IssueCard, String> {
    @Query("SELECT ic FROM IssueCard ic WHERE ic.createdAt >= :since ORDER BY ic.importanceScore DESC")
    List<IssueCard> findTodayIssues(@Param("since") LocalDateTime since);

    List<IssueCard> findByPeerIdOrderByCreatedAtDesc(String peerId);
}
