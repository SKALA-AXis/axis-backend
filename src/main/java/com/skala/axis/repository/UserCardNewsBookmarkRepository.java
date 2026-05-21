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
