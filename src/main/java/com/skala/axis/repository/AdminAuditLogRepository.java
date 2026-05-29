package com.skala.axis.repository;

import com.skala.axis.domain.AdminAuditLog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface AdminAuditLogRepository extends JpaRepository<AdminAuditLog, Long> {
    List<AdminAuditLog> findTop100ByOrderByCreatedAtDesc();

    List<AdminAuditLog> findByResourceTypeAndResourceIdInOrderByCreatedAtDesc(
            String resourceType,
            Collection<String> resourceIds
    );
}
