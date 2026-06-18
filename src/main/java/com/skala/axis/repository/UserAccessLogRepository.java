/*
 * 작성일: 2026-05-21
 * 작성자: 박진
 * 변경이력:
 *   2026-05-21 박진 — 로그인/회원가입 기능 추가 시 사용자 접근 로그 리포지토리 추가, 이후 챗봇 백엔드 지원 보강
 */
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
