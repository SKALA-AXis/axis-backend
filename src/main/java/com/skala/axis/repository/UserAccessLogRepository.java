package com.skala.axis.repository;

import com.skala.axis.domain.UserAccessLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface UserAccessLogRepository extends JpaRepository<UserAccessLog, Long> {
    List<UserAccessLog> findTop20ByUserIdOrderByOccurredAtDesc(UUID userId);

    Page<UserAccessLog> findByUserIdOrderByOccurredAtDesc(UUID userId, Pageable pageable);
}
